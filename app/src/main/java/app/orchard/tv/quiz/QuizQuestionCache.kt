package app.orchard.tv.quiz

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.planyourtv.policy.quiz.QuizAnswerScope
import com.planyourtv.policy.quiz.QuizItem
import com.planyourtv.policy.quiz.QuizLevelPackage
import com.planyourtv.policy.quiz.QuizPackageSource
import com.planyourtv.policy.quiz.QuizSession
import com.planyourtv.policy.quiz.QuizSessionCodec
import com.planyourtv.policy.quiz.QuizTypeFilter
import org.json.JSONArray
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * The bounded, source-agnostic question pool. The web (local) and the cloud
 * preparer each own one scope; the engine only reserves from the scope chosen
 * by the policy mode. Local rows are the product's own bank and survive
 * binding/unbinding; cloud rows are disposable attempt data.
 */
object QuizQuestionCache {
    const val LOCAL_SCOPE = "local"

    /**
     * The bank is local-only and user-owned. Large manual imports are accepted
     * in batches; storage and speed are the user's responsibility. The web
     * lists and previews with paging, so the larger bound never means a larger
     * DOM or one huge transaction.
     */
    const val BANK_CAPACITY = 100_000
    /** Seed rows are inserted in small chunks so streaming import stays small. */
    private const val SEED_IMPORT_BATCH = 200

    private const val TABLE = "quiz_question_cache"
    private const val DB_NAME = "quiz_question_cache.db"
    private const val DB_VERSION = 4

    private const val STATE_AVAILABLE = "AVAILABLE"
    private const val STATE_RESERVED = "RESERVED"
    private const val STATE_CONSUMED = "CONSUMED"
    private const val STATE_EXPIRED = "EXPIRED"
    private const val SOURCE_LOCAL = "LOCAL"
    private const val SOURCE_CLOUD = "CLOUD"
    private const val RESULT_CORRECT = "CORRECT"
    private const val RESULT_WRONG = "WRONG"
    private const val TAG = "QuizQuestionCache"

    /**
     * Column order shared by every row reader. `collection_id` sits after the
     * scope key so both [readQuestion] and [readBankRow] stay in sync when the
     * import feature adds its nullable ownership column.
     */
    private const val SELECT_COLUMNS =
        "cache_id, question_json, source_kind, source_ref, scope_key, collection_id, " +
            "received_at, expires_at, state, last_result, wrong_count, answered_at, " +
            "last_timed_out, last_selected_index, last_selected_indices"

    private const val ROUND_EXCLUSION_TABLE = "qc_round_exclusion"
    private const val PICKED_EXCLUSION =
        " AND NOT EXISTS (SELECT 1 FROM $ROUND_EXCLUSION_TABLE e " +
            "WHERE e.kind = 'id' AND e.value = $TABLE.question_id)" +
            " AND NOT EXISTS (SELECT 1 FROM $ROUND_EXCLUSION_TABLE e " +
            "WHERE e.kind = 'fp' AND e.value = $TABLE.fingerprint)"
    private const val NORMAL_SOURCE_EXCLUSION = PICKED_EXCLUSION +
        " AND NOT EXISTS (SELECT 1 FROM $ROUND_EXCLUSION_TABLE e " +
        "WHERE e.kind = 'wrong-id' AND e.value = $TABLE.question_id)" +
        " AND NOT EXISTS (SELECT 1 FROM $ROUND_EXCLUSION_TABLE e " +
        "WHERE e.kind = 'wrong-fp' AND e.value = $TABLE.fingerprint)"

    private val lock = Any()
    @Volatile private var helper: Helper? = null

    data class CachedQuestion(
        val cacheId: String,
        val question: QuizItem,
        val sourceKind: String,
        val sourceRef: String?,
        val scopeKey: String,
        val collectionId: String?,
        val receivedAt: Long,
        val expiresAt: Long?,
        val state: String,
    )

    data class Counts(
        val available: Int = 0,
        val reserved: Int = 0,
        val consumed: Int = 0,
        val expired: Int = 0,
    ) {
        val total: Int get() = available + reserved + consumed + expired
    }

    data class BankRow(
        val cacheId: String,
        val question: QuizItem,
        val sourceKind: String,
        val sourceRef: String?,
        val scopeKey: String,
        val collectionId: String?,
        val receivedAt: Long,
        val expiresAt: Long?,
        val state: String,
        val lastResult: String?,
        val wrongCount: Int,
        val answeredAt: Long?,
        val timedOut: Boolean,
        val selectedIndex: Int?,
        /** Published-order multiple-choice selection; empty for single rows. */
        val selectedIndices: List<Int> = emptyList(),
    ) {
        fun status(nowMs: Long = System.currentTimeMillis()): String = when {
            state == STATE_AVAILABLE && expiresAt != null && expiresAt <= nowMs -> "expired"
            state == STATE_AVAILABLE -> "unused"
            state == STATE_RESERVED -> "in_round"
            state == STATE_CONSUMED && lastResult == RESULT_CORRECT -> "correct"
            state == STATE_CONSUMED && lastResult == RESULT_WRONG -> "wrong"
            state == STATE_CONSUMED -> "legacy"
            else -> "expired"
        }
    }

    data class BankCounts(
        val total: Int = 0,
        val unused: Int = 0,
        val inRound: Int = 0,
        val correct: Int = 0,
        val wrong: Int = 0,
        val legacy: Int = 0,
        val expired: Int = 0,
    ) {
        val answered: Int get() = correct + wrong + legacy
    }

    data class AnswerWrite(
        val cacheId: String,
        val questionId: String,
        val selectedIndex: Int?,
        val correct: Boolean,
        val timedOut: Boolean,
        val answeredAt: Long,
        /** Published-order multiple-choice selection; empty for single rows. */
        val selectedIndices: List<Int> = emptyList(),
    )

    data class AppendResult(
        val appended: Int = 0,
        val duplicates: Int = 0,
        val rejected: Int = 0,
        val capacityReached: Boolean = false,
        val items: List<CachedQuestion> = emptyList(),
    )

    /** Counts a manual import preview/inspection needs, split by namespace. */
    data class ImportPlan(
        val total: Int = 0,
        val fileDuplicates: Int = 0,
        val collectionDuplicates: Int = 0,
        val crossSourceDuplicates: Int = 0,
        val rejected: Int = 0,
        val wouldAppend: Int = 0,
        val remaining: Int = 0,
        val preview: List<QuizItem> = emptyList(),
    )

    data class ImportResult(
        val appended: Int = 0,
        val fileDuplicates: Int = 0,
        val collectionDuplicates: Int = 0,
        val crossSourceDuplicates: Int = 0,
        val rejected: Int = 0,
        val capacityReached: Boolean = false,
        val storageError: Boolean = false,
        val remaining: Int = 0,
    )

    data class SourceCounts(
        val course: Int = 0,
        val custom: Int = 0,
    )

    data class CollectionCounts(
        val collectionId: String,
        val counts: BankCounts,
    )

    /** Raw available counts for one course scope; the filter never widens. */
    data class AnswerKindCounts(
        val total: Int = 0,
        val single: Int = 0,
        val multiple: Int = 0,
    )

    /**
     * A package's sources overlap, so [available] is a distinct union rather
     * than the sum of [sourceCounts]. Wrong-review rows are added because they
     * live in CONSUMED, outside the normal available set.
     */
    data class PackageAvailability(
        val sourceCounts: List<Int> = emptyList(),
        val available: Int = 0,
    )

    /**
     * One distinct scope combination. The faceted picker only needs counts,
     * not question text, so grouping the indexed scope columns avoids decoding
     * every row at a 100,000-question bank.
     */
    data class FacetGroup(
        val grade: Int?,
        val semester: Int?,
        val subject: String,
        val textbook: String,
        val scopeType: String,
        val unitNo: Int?,
        val difficulty: String,
        val answerKind: String,
        val count: Int,
    )

    data class FacetSnapshot(
        val total: Int = 0,
        val groups: List<FacetGroup> = emptyList(),
    )

    enum class ClearMode {
        UNUSED,
        ANSWERED,
        CORRECT,
        WRONG,
        ALL,
    }

    /** Where a maintenance clear is allowed to delete. */
    enum class BankSource {
        COURSE_AI,
        CUSTOM,
        ALL,
    }

    class CacheException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun init(context: Context): Boolean = initDb(context, DB_NAME)

    @VisibleForTesting
    internal fun initForTest(context: Context, dbName: String): Boolean = initDb(context, dbName)

    private fun initDb(context: Context, dbName: String): Boolean = synchronized(lock) {
        if (helper?.dbName == dbName) return true
        helper?.close()
        helper = null
        return runCatching {
            helper = Helper(context.applicationContext, dbName)
            helper!!.writableDatabase
            true
        }.getOrElse { error ->
            Log.e(TAG, "question cache init failed", error)
            helper = null
            false
        }
    }

    @VisibleForTesting
    internal fun closeForTest() = synchronized(lock) {
        helper?.close()
        helper = null
    }

    fun isReady(): Boolean = helper != null

    fun cloudScope(deviceNo: String): String = "cloud:$deviceNo"

    /**
     * Legacy path retained for old callers. V17 production code appends to the
     * bank through [appendLocalQuestions] instead.
     */
    fun replaceLocalSession(session: QuizSession, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val db = database() ?: return false
            db.beginTransaction()
            try {
                db.delete(
                    TABLE,
                    "scope_key = ? AND state IN (?, ?) AND collection_id IS NULL",
                    arrayOf(LOCAL_SCOPE, STATE_AVAILABLE, STATE_RESERVED),
                )
                session.items.forEach { item ->
                    val sourceRef = "local:${session.id}:${item.questionId}"
                    db.delete(TABLE, "source_ref = ?", arrayOf(sourceRef))
                    val stored = item.copy(cacheId = "", sourceKind = SOURCE_LOCAL, sourceRef = "", optionOrder = emptyList())
                    val values = ContentValues().apply {
                        put("cache_id", UUID.randomUUID().toString())
                        put("question_json", QuizSessionCodec.encodeItem(stored).toString())
                        put("question_id", stored.questionId)
                        put("source_kind", SOURCE_LOCAL)
                        put("source_ref", sourceRef)
                        put("scope_key", LOCAL_SCOPE)
                        putNull("collection_id")
                        putScopeFields(stored)
                        put("fingerprint", fingerprint(stored))
                        put("state", STATE_AVAILABLE)
                        putNull("reserved_by")
                        put("received_at", nowMs)
                        putNull("expires_at")
                        putNull("reserved_at")
                        putNull("consumed_at")
                        put("last_result", null as String?)
                        put("wrong_count", 0)
                        put("answered_at", null as Long?)
                        put("last_round_id", null as String?)
                        put("last_timed_out", 0)
                        put("last_selected_index", null as Int?)
                    }
                    db.insertOrThrow(TABLE, null, values)
                }
                db.setTransactionSuccessful()
                true
            } catch (error: Exception) {
                Log.e(TAG, "replace local session failed", error)
                false
            } finally {
                db.endTransaction()
            }
        }

    fun replaceCloudSet(
        attemptUid: String,
        items: List<QuizItem>,
        expiresAt: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = replaceCloudSet(attemptUid, items, expiresAt, cloudScope("legacy"), nowMs)

    /**
     * Replaces one cloud scope with one attempt. Local rows are never touched.
     * Callers must have flushed the previous attempt first.
     */
    fun replaceCloudSet(
        attemptUid: String,
        items: List<QuizItem>,
        expiresAt: Long,
        scopeKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (attemptUid.isBlank() || items.isEmpty() || expiresAt <= nowMs || scopeKey == LOCAL_SCOPE) return false
        val db = database() ?: return false
        db.beginTransaction()
        try {
            db.delete(
                TABLE,
                "scope_key = ? AND state IN (?, ?)",
                arrayOf(scopeKey, STATE_AVAILABLE, STATE_RESERVED),
            )
            items.forEach { item ->
                val sourceRef = "cloud:$attemptUid:${item.questionId}"
                val stored = item.copy(cacheId = "", sourceKind = SOURCE_CLOUD, sourceRef = sourceRef, optionOrder = emptyList())
                val values = ContentValues().apply {
                    put("cache_id", UUID.randomUUID().toString())
                    put("question_json", QuizSessionCodec.encodeItem(stored).toString())
                    put("question_id", stored.questionId)
                    put("source_kind", SOURCE_CLOUD)
                    put("source_ref", sourceRef)
                    put("scope_key", scopeKey)
                    putNull("collection_id")
                    putScopeFields(stored)
                    put("fingerprint", fingerprint(stored))
                    put("state", STATE_AVAILABLE)
                    putNull("reserved_by")
                    put("received_at", nowMs)
                    put("expires_at", expiresAt)
                    putNull("reserved_at")
                    putNull("consumed_at")
                    put("last_result", null as String?)
                    put("wrong_count", 0)
                    put("answered_at", null as Long?)
                    put("last_round_id", null as String?)
                    put("last_timed_out", 0)
                    put("last_selected_index", null as Int?)
                }
                db.insertOrThrow(TABLE, null, values)
            }
            db.setTransactionSuccessful()
            true
        } catch (error: Exception) {
            Log.e(TAG, "replace cloud set failed", error)
            false
        } finally {
            db.endTransaction()
        }
    }

    fun cloudAttemptUid(scopeKey: String = LOCAL_SCOPE): String? = synchronized(lock) {
        val db = database() ?: return null
        runCatching {
            val attempts = mutableSetOf<String>()
            db.rawQuery(
                "SELECT source_ref FROM $TABLE " +
                    "WHERE scope_key = ? AND source_kind = ? AND state IN (?, ?) " +
                    "AND source_ref IS NOT NULL",
                arrayOf(scopeKey, SOURCE_CLOUD, STATE_AVAILABLE, STATE_RESERVED),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val ref = cursor.getString(0) ?: continue
                    if (!ref.startsWith("cloud:")) continue
                    val rest = ref.removePrefix("cloud:")
                    val uid = rest.substringBefore(':', missingDelimiterValue = "")
                    if (uid.isNotEmpty()) attempts += uid
                }
            }
            attempts.singleOrNull()
        }.onFailure { Log.e(TAG, "cloud attempt lookup failed", it) }.getOrNull()
    }

    /**
     * Atomically selects up to [count] available rows. Partial results are
     * returned as-is; an empty list means the pool has no usable question.
     */
    fun reserve(
        count: Int,
        sessionId: String,
        scopeKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CachedQuestion> = reserveForRound(count, sessionId, scopeKey, 1, nowMs)

    /**
     * Reserves a round only when at least [minRequired] questions are available.
     * A short pool returns no rows and leaves every row available, so a failed
     * start never strands reservations.
     */
    fun reserveForRound(
        count: Int,
        sessionId: String,
        scopeKey: String,
        minRequired: Int,
        nowMs: Long = System.currentTimeMillis(),
        answerScope: QuizAnswerScope? = null,
        collectionId: String? = null,
    ): List<CachedQuestion> = synchronized(lock) {
        if (count <= 0 || minRequired <= 0) return emptyList()
        val db = database() ?: throw CacheException("cache not initialized")
        db.beginTransaction()
        try {
            expire(db, scopeKey, nowMs)
            val (scopeWhere, scopeArgs) = answerScopeWhere(answerScope)
            val collectionWhere = if (collectionId != null) " AND collection_id = ?" else ""
            val baseArgs = arrayOf(scopeKey, STATE_AVAILABLE, nowMs.toString()) + scopeArgs +
                listOfNotNull(collectionId)
            val where = "scope_key = ? AND state = ? " +
                "AND (expires_at IS NULL OR expires_at > ?)$scopeWhere$collectionWhere"
            val total = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE $where",
                baseArgs,
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (total < minRequired) {
                db.setTransactionSuccessful()
                return emptyList()
            }
            val picked = if (collectionId == null &&
                answerScope?.subject.equals(QuizAnswerScope.SUBJECT_ALL, ignoreCase = true)
            ) {
                readBalancedCandidates(db, where, baseArgs, count, sessionId)
            } else {
                readCandidates(db, where, baseArgs, count)
            }
            picked.forEach { item ->
                db.update(
                    TABLE,
                    ContentValues().apply {
                        put("state", STATE_RESERVED)
                        put("reserved_by", sessionId)
                        put("reserved_at", nowMs)
                    },
                    "cache_id = ? AND state = ?",
                    arrayOf(item.cacheId, STATE_AVAILABLE),
                )
            }
            db.setTransactionSuccessful()
            picked.map { it.copy(state = STATE_RESERVED) }
        } catch (e: CacheException) {
            throw e
        } catch (e: Exception) {
            throw CacheException("reserve failed", e)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Custom-tag round path. It keeps the same state machine and
     * `scope_key = local` as course rounds, but selects rows by tag instead of
     * curriculum scope; difficulty never filters an imported tag.
     */
    fun reserveCollectionForRound(
        count: Int,
        sessionId: String,
        collectionId: String,
        minRequired: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CachedQuestion> =
        reserveForRound(count, sessionId, LOCAL_SCOPE, minRequired, nowMs, collectionId = collectionId)

    /**
     * Round-robin package sampling.
     *
     * Every source contributes at most one question per cycle, starting at a
     * random offset so the first source cannot dominate a round. Course sources
     * honor their type filter; wildcard-subject sources rotate subjects so one
     * subject cannot fill the round. Wrong-review rows are drawn before normal
     * sources can reuse their content, so a correct answer actually clears the
     * mistake. All row reservations happen in this one transaction: a short
     * round rolls back and leaves the bank untouched.
     *
     * [random] is injectable so tests can replay a distribution.
     */
    fun reservePackageForRound(
        level: QuizLevelPackage,
        count: Int,
        sessionId: String,
        minRequired: Int,
        nowMs: Long = System.currentTimeMillis(),
        scopeKey: String = LOCAL_SCOPE,
        random: Random = Random(),
    ): List<CachedQuestion> = synchronized(lock) {
        if (count <= 0 || minRequired <= 0) return emptyList()
        val db = database() ?: throw CacheException("cache not initialized")
        db.beginTransaction()
        try {
            expire(db, scopeKey, nowMs)
            val sources = level.sources
            if (sources.isEmpty()) {
                db.setTransactionSuccessful()
                return emptyList()
            }
            resetRoundExclusions(db)
            val wrongSource = sources.any { it is QuizPackageSource.WrongReview }
            if (wrongSource) blockWrongReviewCandidates(db, scopeKey)

            val sourceOrder = IntArray(sources.size) { it }
            val offset = random.nextInt(sourceOrder.size)
            val order = IntArray(sourceOrder.size) { sourceOrder[(offset + it) % sourceOrder.size] }
            val exhausted = BooleanArray(sources.size)
            val picked = ArrayList<CachedQuestion>(count)
            val pickedIds = HashSet<String>(count * 2 + 8)
            val pickedFingerprints = HashSet<String>(count * 2 + 8)
            val wrongPicked = HashSet<String>(count)
            val subjectUsage = HashMap<Int, MutableMap<String, Int>>()

            while (picked.size < count) {
                var progressed = false
                for (index in order) {
                    if (picked.size >= count || exhausted[index]) continue
                    val source = sources[index]
                    val drawn = when (source) {
                        is QuizPackageSource.Course -> drawCourseSource(
                            db = db,
                            scopeKey = scopeKey,
                            nowMs = nowMs,
                            source = source,
                            random = random,
                            usage = subjectUsage.getOrPut(index) { mutableMapOf() },
                        )
                        is QuizPackageSource.Collection -> drawCollectionSource(
                            db = db,
                            scopeKey = scopeKey,
                            nowMs = nowMs,
                            collectionId = source.collectionId,
                            random = random,
                        )
                        QuizPackageSource.WrongReview -> drawWrongSource(
                            db = db,
                            scopeKey = scopeKey,
                            random = random,
                        )
                    }
                    if (drawn == null) {
                        exhausted[index] = true
                        continue
                    }
                    val questionId = drawn.question.questionId
                    val fp = fingerprint(drawn.question)
                    // The SQL exclusion should make this impossible; failing
                    // closed here avoids a short or duplicated round.
                    if ((questionId.isNotBlank() && !pickedIds.add(questionId)) ||
                        !pickedFingerprints.add(fp)
                    ) {
                        exhausted[index] = true
                        continue
                    }
                    picked += drawn
                    if (source is QuizPackageSource.WrongReview) wrongPicked += drawn.cacheId
                    addRoundExclusion(db, "id", questionId)
                    addRoundExclusion(db, "fp", fp)
                    progressed = true
                    if (picked.size >= count) break
                }
                if (!progressed) break
            }

            if (picked.size < minRequired) {
                // Expired rows should stay expired; no reservation has been
                // written yet.
                db.setTransactionSuccessful()
                return emptyList()
            }
            picked.forEach { row ->
                val updated = if (row.cacheId in wrongPicked) {
                    db.update(
                        TABLE,
                        ContentValues().apply { putNull("last_round_id") },
                        "cache_id = ? AND state = ? AND last_result = ?",
                        arrayOf(row.cacheId, STATE_CONSUMED, RESULT_WRONG),
                    )
                } else {
                    db.update(
                        TABLE,
                        ContentValues().apply {
                            put("state", STATE_RESERVED)
                            put("reserved_by", sessionId)
                            put("reserved_at", nowMs)
                        },
                        "cache_id = ? AND state = ?",
                        arrayOf(row.cacheId, STATE_AVAILABLE),
                    )
                }
                if (updated != 1) throw CacheException("package row changed during reservation")
            }
            db.setTransactionSuccessful()
            picked.map { row ->
                if (row.cacheId in wrongPicked) row else row.copy(state = STATE_RESERVED)
            }
        } catch (e: CacheException) {
            throw e
        } catch (e: Exception) {
            throw CacheException("package reserve failed", e)
        } finally {
            db.endTransaction()
        }
    }

    /** Every local row whose most recent answer was wrong, across sources and tags. */
    fun wrongReviewCount(scopeKey: String = LOCAL_SCOPE): Int = synchronized(lock) {
        val db = database() ?: return 0
        runCatching { wrongReviewCount(db, scopeKey) }
            .onFailure { Log.e(TAG, "wrong-review count failed", it) }
            .getOrDefault(0)
    }

    /**
     * Per-source raw counts plus a distinct package total. The runtime and the
     * web both read this one snapshot, so a parent never sees two different
     * availability numbers for the same package.
     */
    fun packageAvailability(
        level: QuizLevelPackage,
        nowMs: Long = System.currentTimeMillis(),
        scopeKey: String = LOCAL_SCOPE,
    ): PackageAvailability = synchronized(lock) {
        val empty = PackageAvailability(List(level.sources.size) { 0 }, 0)
        val db = database() ?: return empty
        runCatching {
            expire(db, scopeKey, nowMs)
            val perSource = level.sources.map { source ->
                when (source) {
                    is QuizPackageSource.Course -> countAvailable(
                        db,
                        courseWhere(scopeKey, nowMs, source.scope, source.answerKind),
                    )
                    is QuizPackageSource.Collection ->
                        countAvailable(db, collectionWhere(scopeKey, nowMs, source.collectionId))
                    QuizPackageSource.WrongReview -> wrongReviewCount(db, scopeKey)
                }
            }
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            level.sources.forEach { source ->
                val pair = when (source) {
                    is QuizPackageSource.Course ->
                        courseWhere(scopeKey, nowMs, source.scope, source.answerKind)
                    is QuizPackageSource.Collection ->
                        collectionWhere(scopeKey, nowMs, source.collectionId)
                    QuizPackageSource.WrongReview -> null
                } ?: return@forEach
                clauses += "(" + pair.first + ")"
                args += pair.second
            }
            val normal = if (clauses.isEmpty()) {
                0
            } else {
                db.rawQuery(
                    "SELECT COUNT(DISTINCT question_id || ':' || fingerprint) FROM $TABLE " +
                        "WHERE " + clauses.joinToString(" OR "),
                    args.toTypedArray(),
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            }
            val wrong = if (level.sources.any { it is QuizPackageSource.WrongReview }) {
                wrongReviewCount(db, scopeKey)
            } else {
                0
            }
            PackageAvailability(perSource, normal + wrong)
        }.onFailure { Log.e(TAG, "package availability failed", it) }.getOrDefault(empty)
    }

    /**
     * A wrong-review round re-asks consumed wrong rows without making them
     * available again. Clearing `last_round_id` reuses the existing answerable
     * hook in [writeAnswersLocked]; the row stays CONSUMED+WRONG, so normal and
     * custom levels (which only reserve AVAILABLE rows) can never see it.
     *
     * Least-recently-attempted rows go first so a repeatedly failed question
     * cannot starve the rest of the pool. Duplicate question ids and identical
     * content within one round are collapsed because a session accepts only one
     * answer per question id.
     */
    fun reserveWrongForRound(
        count: Int,
        sessionId: String,
        scopeKey: String,
        minRequired: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CachedQuestion> = synchronized(lock) {
        if (count <= 0 || minRequired <= 0) return emptyList()
        val db = database() ?: throw CacheException("cache not initialized")
        db.beginTransaction()
        try {
            val picked = ArrayList<CachedQuestion>(count)
            val questionIds = HashSet<String>(count)
            val fingerprints = HashSet<String>(count)
            db.rawQuery(
                "SELECT $SELECT_COLUMNS, question_id, fingerprint FROM $TABLE " +
                    "WHERE scope_key = ? AND state = ? AND last_result = ? " +
                    "ORDER BY COALESCE(answered_at, 0) ASC, received_at ASC, rowid ASC",
                arrayOf(scopeKey, STATE_CONSUMED, RESULT_WRONG),
            ).use { cursor ->
                // Look the two appended columns up by name; their position
                // changes whenever SELECT_COLUMNS grows.
                val questionIdColumn = cursor.getColumnIndexOrThrow("question_id")
                val fingerprintColumn = cursor.getColumnIndexOrThrow("fingerprint")
                while (cursor.moveToNext() && picked.size < count) {
                    val cached = readQuestion(cursor)
                    val questionId = cursor.getString(questionIdColumn)
                        .orEmpty()
                        .ifBlank { cached.question.questionId }
                    val fingerprint = cursor.getString(fingerprintColumn).orEmpty()
                    if (!questionIds.add(questionId)) continue
                    if (fingerprint.isNotEmpty() && !fingerprints.add(fingerprint)) continue
                    picked += cached
                }
            }
            if (picked.size < minRequired) {
                db.setTransactionSuccessful()
                return emptyList()
            }
            picked.forEach { item ->
                db.update(
                    TABLE,
                    ContentValues().apply { putNull("last_round_id") },
                    "cache_id = ? AND state = ? AND last_result = ?",
                    arrayOf(item.cacheId, STATE_CONSUMED, RESULT_WRONG),
                )
            }
            db.setTransactionSuccessful()
            picked
        } catch (e: CacheException) {
            throw e
        } catch (e: Exception) {
            throw CacheException("wrong-review reserve failed", e)
        } finally {
            db.endTransaction()
        }
    }

    private fun readCandidates(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
        count: Int,
    ): List<CachedQuestion> {
        val rows = mutableListOf<CachedQuestion>()
        db.rawQuery(
            "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $where " +
                "ORDER BY received_at ASC, rowid ASC LIMIT ?",
            args + count.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += readQuestion(cursor)
        }
        return rows
    }

    /**
     * A wildcard subject must not serve one subject's contiguous import block
     * just because that block was written first. Keep the existing FIFO order
     * inside each subject and take one row per subject in turn. Specific
     * subject scopes and the cloud path keep [readCandidates] unchanged.
     */
    private fun readBalancedCandidates(
        db: SQLiteDatabase,
        where: String,
        args: Array<String>,
        count: Int,
        sessionId: String,
    ): List<CachedQuestion> {
        val subjects = mutableListOf<String>()
        db.rawQuery(
            "SELECT UPPER(scope_subject) FROM $TABLE WHERE $where " +
                "GROUP BY UPPER(scope_subject)",
            args,
        ).use { cursor ->
            while (cursor.moveToNext()) subjects += cursor.getString(0).orEmpty()
        }
        if (subjects.size <= 1) return readCandidates(db, where, args, count)
        val buckets = subjects.map { subject ->
            val rows = mutableListOf<CachedQuestion>()
            db.rawQuery(
                "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $where " +
                    "AND UPPER(scope_subject) = ? " +
                    "ORDER BY received_at ASC, rowid ASC LIMIT ?",
                args + subject + count.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) rows += readQuestion(cursor)
            }
            rows
        }
        val picked = mutableListOf<CachedQuestion>()
        val cursors = IntArray(buckets.size)
        // Vary which subject receives the extra question so repeated rounds do
        // not always give the same subject one more slot.
        val start = Math.floorMod(sessionId.hashCode(), buckets.size)
        while (picked.size < count) {
            var progressed = false
            for (step in buckets.indices) {
                if (picked.size >= count) break
                val index = (start + step) % buckets.size
                val bucket = buckets[index]
                val next = cursors[index]
                if (next < bucket.size) {
                    picked += bucket[next]
                    cursors[index] = next + 1
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return picked
    }

    /**
     * Appends generated questions to the local bank. Duplicates are matched by
     * content fingerprint across all states, so retrying a generation batch is
     * idempotent. The bank never deletes an existing row to make room.
     */
    fun appendLocalQuestions(
        batchId: String,
        items: List<QuizItem>,
        nowMs: Long = System.currentTimeMillis(),
        capacity: Int = BANK_CAPACITY,
    ): AppendResult = synchronized(lock) {
        if (batchId.isBlank() || items.isEmpty()) return AppendResult(rejected = items.size)
        val db = database() ?: return AppendResult(rejected = items.size)
        db.beginTransaction()
        try {
            val result = appendLocalQuestionsLocked(db, batchId, items, nowMs, capacity = capacity)
            db.setTransactionSuccessful()
            result
        } catch (error: Exception) {
            Log.e(TAG, "append local questions failed", error)
            AppendResult(rejected = items.size)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces the bundled seed rows in one transaction. Only rows whose source
     * ref starts with `local:<sourceRefPrefix>` are removed, so questions
     * generated by the user keep both their content and their answer history,
     * and the single- and multiple-choice banks never delete each other. Rows
     * reserved by a running round are kept: deleting them would strand that
     * round's answer writes during an APK upgrade.
     */
    fun replaceSeedQuestions(
        batchId: String,
        items: List<QuizItem>,
        nowMs: Long = System.currentTimeMillis(),
        capacity: Int = BANK_CAPACITY,
        sourceRefPrefix: String = "preset-v",
    ): AppendResult = replaceSeedQuestionsStreaming(
        batchId = batchId,
        items = items.asSequence(),
        nowMs = nowMs,
        capacity = capacity,
        sourceRefPrefix = sourceRefPrefix,
    )

    /**
     * Streaming form of [replaceSeedQuestions]. The caller supplies a lazy
     * sequence so the seed asset can be parsed with `android.util.JsonReader`
     * instead of building the whole JSON tree and question list in memory.
     *
     * The whole import still runs in one SQLite transaction; a malformed asset
     * or a read failure rolls back the old rows. If the new asset yields no
     * usable row at all, the transaction is also rolled back so a broken bank
     * cannot erase the previous one.
     */
    fun replaceSeedQuestionsStreaming(
        batchId: String,
        items: Sequence<QuizItem>,
        nowMs: Long = System.currentTimeMillis(),
        capacity: Int = BANK_CAPACITY,
        sourceRefPrefix: String = "preset-v",
        batchSize: Int = SEED_IMPORT_BATCH,
    ): AppendResult = synchronized(lock) {
        if (batchId.isBlank()) return AppendResult()
        val db = database() ?: return AppendResult(rejected = 1)
        val iterator = items.iterator()
        // Parse the first item before touching the old rows. An empty or
        // unreadable asset must leave the current bank untouched.
        val first = try {
            if (!iterator.hasNext()) return AppendResult()
            iterator.next()
        } catch (error: Exception) {
            Log.e(TAG, "replace seed questions failed while reading", error)
            return AppendResult(rejected = 1)
        }
        db.beginTransaction()
        try {
            db.delete(
                TABLE,
                "scope_key = ? AND source_kind = ? AND source_ref LIKE ? AND state != ?",
                arrayOf(LOCAL_SCOPE, SOURCE_LOCAL, "local:$sourceRefPrefix%", STATE_RESERVED),
            )
            val chunkSize = batchSize.coerceAtLeast(1)
            val batch = ArrayList<QuizItem>(chunkSize)
            var appended = 0
            var duplicates = 0
            var rejected = 0
            var capacityReached = false
            fun flush() {
                if (batch.isEmpty()) return
                val result = appendLocalQuestionsLocked(
                    db,
                    batchId,
                    batch,
                    nowMs,
                    capacity = capacity,
                )
                appended += result.appended
                duplicates += result.duplicates
                rejected += result.rejected
                capacityReached = capacityReached || result.capacityReached
                batch.clear()
            }
            batch += first
            while (iterator.hasNext()) {
                batch += iterator.next()
                if (batch.size >= chunkSize) flush()
            }
            flush()
            if (appended == 0 && duplicates == 0) {
                // Nothing usable made it in; roll back the delete instead of
                // leaving an empty seed bank behind.
                return AppendResult(
                    appended = appended,
                    duplicates = duplicates,
                    rejected = rejected,
                    capacityReached = capacityReached,
                )
            }
            db.setTransactionSuccessful()
            AppendResult(
                appended = appended,
                duplicates = duplicates,
                rejected = rejected,
                capacityReached = capacityReached,
            )
        } catch (error: Exception) {
            Log.e(TAG, "replace seed questions failed", error)
            AppendResult(rejected = 1)
        } finally {
            db.endTransaction()
        }
    }

    private fun appendLocalQuestionsLocked(
        db: SQLiteDatabase,
        batchId: String,
        items: List<QuizItem>,
        nowMs: Long,
        collectionId: String? = null,
        capacity: Int = BANK_CAPACITY,
    ): AppendResult {
        var remaining = (capacity - rowCount(db, LOCAL_SCOPE)).coerceAtLeast(0)
        val seen = mutableSetOf<String>()
        val appended = mutableListOf<CachedQuestion>()
        var duplicates = 0
        var rejected = 0
        var capacityReached = false
        for (candidate in items) {
            val item = candidate.copy(
                cacheId = "",
                sourceKind = SOURCE_LOCAL,
                sourceRef = "",
                optionOrder = emptyList(),
            )
            if (!isValid(item)) {
                rejected++
                continue
            }
            val fp = fingerprint(item)
            if (!seen.add(fp)) {
                duplicates++
                continue
            }
            // The AI/seed namespace is `collection_id IS NULL`; an import never
            // compares against another tag, so 100 imported questions stay 100.
            if (fingerprintExists(db, LOCAL_SCOPE, fp, collectionId)) {
                duplicates++
                continue
            }
            if (remaining <= 0) {
                rejected++
                capacityReached = true
                continue
            }
            val ref = sourceRef(batchId, collectionId, item.questionId)
            if (sourceRefExists(db, SOURCE_LOCAL, ref)) {
                duplicates++
                continue
            }
            appended += insertLocalRowLocked(db, batchId, collectionId, item, fp, nowMs)
            remaining--
        }
        return AppendResult(appended.size, duplicates, rejected, capacityReached, appended)
    }

    private fun insertLocalRowLocked(
        db: SQLiteDatabase,
        batchId: String,
        collectionId: String?,
        item: QuizItem,
        fp: String,
        nowMs: Long,
    ): CachedQuestion {
        val ref = sourceRef(batchId, collectionId, item.questionId)
        val cacheId = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("cache_id", cacheId)
            put("question_json", QuizSessionCodec.encodeItem(item).toString())
            put("question_id", item.questionId)
            put("source_kind", SOURCE_LOCAL)
            put("source_ref", ref)
            put("scope_key", LOCAL_SCOPE)
            if (collectionId == null) putNull("collection_id") else put("collection_id", collectionId)
            putScopeFields(item)
            put("fingerprint", fp)
            put("state", STATE_AVAILABLE)
            putNull("reserved_by")
            put("received_at", nowMs)
            putNull("expires_at")
            putNull("reserved_at")
            putNull("consumed_at")
            put("last_result", null as String?)
            put("wrong_count", 0)
            put("answered_at", null as Long?)
            put("last_round_id", null as String?)
            put("last_timed_out", 0)
            put("last_selected_index", null as Int?)
        }
        db.insertOrThrow(TABLE, null, values)
        return CachedQuestion(
            cacheId = cacheId,
            question = item,
            sourceKind = SOURCE_LOCAL,
            sourceRef = ref,
            scopeKey = LOCAL_SCOPE,
            collectionId = collectionId,
            receivedAt = nowMs,
            expiresAt = null,
            state = STATE_AVAILABLE,
        )
    }

    private fun sourceRef(batchId: String, collectionId: String?, questionId: String): String =
        if (collectionId == null) {
            "local:$batchId:$questionId"
        } else {
            "local:$collectionId:$batchId:$questionId"
        }

    /**
     * 生成结果暂存时用来标记“已在题库”的题。返回 true 表示该条不可再加入：
     * 非法内容、与题库重复或与本批前面的题重复。
     */
    fun duplicateFlags(items: List<QuizItem>): List<Boolean> = synchronized(lock) {
        val db = database()
        val seen = mutableSetOf<String>()
        items.map { candidate ->
            val item = candidate.copy(
                cacheId = "",
                sourceKind = SOURCE_LOCAL,
                sourceRef = "",
                optionOrder = emptyList(),
            )
            if (!isValid(item)) return@map true
            val fp = fingerprint(item)
            val fresh = seen.add(fp)
            !fresh || (db != null && fingerprintExists(db, LOCAL_SCOPE, fp, null))
        }
    }

    /**
     * Preview of a manual import. It runs the same namespace-aware dedupe as
     * [importLocalQuestions] but never writes. Cross-source duplicates are
     * informational: those rows will still be appended to the target tag.
     */
    fun previewLocalImport(
        collectionId: String,
        items: List<QuizItem>,
        nowMs: Long = System.currentTimeMillis(),
        capacity: Int = BANK_CAPACITY,
    ): ImportPlan = synchronized(lock) {
        if (collectionId.isBlank() || items.isEmpty()) return ImportPlan()
        val db = database() ?: return ImportPlan(total = items.size)
        runCatching {
            expire(db, LOCAL_SCOPE, nowMs)
            val plan = planLocalImportLocked(db, collectionId, items)
            val remaining = (capacity - rowCount(db, LOCAL_SCOPE)).coerceAtLeast(0)
            ImportPlan(
                total = items.size,
                fileDuplicates = plan.fileDuplicates,
                collectionDuplicates = plan.collectionDuplicates,
                crossSourceDuplicates = plan.crossSourceDuplicates,
                rejected = plan.rejected,
                wouldAppend = plan.items.size,
                remaining = remaining,
                preview = plan.items.take(20).map { it.first },
            )
        }.onFailure { Log.e(TAG, "preview local import failed", it) }
            .getOrDefault(ImportPlan(total = items.size))
    }

    /**
     * Strict, all-or-nothing import into one tag. A capacity shortfall or an
     * invalid row leaves the bank untouched. Duplicate rules match the preview:
     * same-file and same-tag rows are skipped, other sources are kept.
     */
    fun importLocalQuestions(
        collectionId: String,
        batchId: String,
        items: List<QuizItem>,
        nowMs: Long = System.currentTimeMillis(),
        capacity: Int = BANK_CAPACITY,
    ): ImportResult = synchronized(lock) {
        if (collectionId.isBlank() || batchId.isBlank() || items.isEmpty()) {
            return ImportResult(rejected = items.size)
        }
        val db = database() ?: return ImportResult(rejected = items.size, storageError = true)
        db.beginTransaction()
        try {
            val plan = planLocalImportLocked(db, collectionId, items)
            val remaining = (capacity - rowCount(db, LOCAL_SCOPE)).coerceAtLeast(0)
            val result = ImportResult(
                fileDuplicates = plan.fileDuplicates,
                collectionDuplicates = plan.collectionDuplicates,
                crossSourceDuplicates = plan.crossSourceDuplicates,
                rejected = plan.rejected,
                remaining = remaining,
            )
            if (plan.rejected > 0) {
                db.setTransactionSuccessful()
                return result
            }
            if (plan.items.size > remaining) {
                db.setTransactionSuccessful()
                return result.copy(capacityReached = true)
            }
            plan.items.forEach { (item, fp) ->
                insertLocalRowLocked(db, batchId, collectionId, item, fp, nowMs)
            }
            db.setTransactionSuccessful()
            result.copy(appended = plan.items.size, remaining = remaining - plan.items.size)
        } catch (error: Exception) {
            Log.e(TAG, "import local questions failed", error)
            ImportResult(rejected = items.size, storageError = true)
        } finally {
            db.endTransaction()
        }
    }

    private data class LocalImportPlan(
        val items: List<Pair<QuizItem, String>>,
        val fileDuplicates: Int,
        val collectionDuplicates: Int,
        val crossSourceDuplicates: Int,
        val rejected: Int,
    )

    private fun planLocalImportLocked(
        db: SQLiteDatabase,
        collectionId: String,
        items: List<QuizItem>,
    ): LocalImportPlan {
        val seen = mutableSetOf<String>()
        val fresh = mutableListOf<Pair<QuizItem, String>>()
        var fileDuplicates = 0
        var collectionDuplicates = 0
        var crossSourceDuplicates = 0
        var rejected = 0
        for (candidate in items) {
            val item = candidate.copy(
                cacheId = "",
                sourceKind = SOURCE_LOCAL,
                sourceRef = "",
                optionOrder = emptyList(),
            )
            if (!isValid(item)) {
                rejected++
                continue
            }
            val fp = fingerprint(item)
            if (!seen.add(fp)) {
                fileDuplicates++
                continue
            }
            if (fingerprintExists(db, LOCAL_SCOPE, fp, collectionId)) {
                collectionDuplicates++
                continue
            }
            if (fingerprintExistsOutsideCollection(db, LOCAL_SCOPE, fp, collectionId)) {
                crossSourceDuplicates++
            }
            fresh += item to fp
        }
        return LocalImportPlan(
            items = fresh,
            fileDuplicates = fileDuplicates,
            collectionDuplicates = collectionDuplicates,
            crossSourceDuplicates = crossSourceDuplicates,
            rejected = rejected,
        )
    }

    /**
     * Scope counts for the faceted custom-range picker. Grouping in SQLite
     * keeps the 100,000-row bank off the heap; the caller only re-expands the
     * distinct combinations through the existing curriculum validation.
     */
    fun facetSnapshot(
        scopeKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): FacetSnapshot = synchronized(lock) {
        val db = database() ?: return FacetSnapshot()
        runCatching {
            expire(db, scopeKey, nowMs)
            val total = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE scope_key = ?",
                arrayOf(scopeKey),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val groups = mutableListOf<FacetGroup>()
            db.rawQuery(
                "SELECT scope_grade, scope_semester, scope_subject, scope_textbook, " +
                    "scope_type, scope_unit_no, difficulty, answer_kind, COUNT(*) FROM $TABLE " +
                    "WHERE scope_key = ? " +
                    "GROUP BY scope_grade, scope_semester, scope_subject, scope_textbook, " +
                    "scope_type, scope_unit_no, difficulty, answer_kind",
                arrayOf(scopeKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    groups += FacetGroup(
                        grade = if (cursor.isNull(0)) null else cursor.getInt(0),
                        semester = if (cursor.isNull(1)) null else cursor.getInt(1),
                        subject = cursor.getString(2).orEmpty(),
                        textbook = cursor.getString(3).orEmpty(),
                        scopeType = cursor.getString(4).orEmpty(),
                        unitNo = if (cursor.isNull(5)) null else cursor.getInt(5),
                        difficulty = cursor.getString(6).orEmpty(),
                        answerKind = cursor.getString(7).orEmpty(),
                        count = cursor.getInt(8),
                    )
                }
            }
            FacetSnapshot(total, groups)
        }.onFailure { Log.e(TAG, "facet snapshot failed", it) }
            .getOrDefault(FacetSnapshot())
    }

    /** Every local bank row, used by legacy callers; the faceted picker uses [facetSnapshot]. */
    fun listAllBank(
        scopeKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<BankRow> = synchronized(lock) {
        val db = database() ?: return emptyList()
        runCatching {
            expire(db, scopeKey, nowMs)
            val rows = mutableListOf<BankRow>()
            db.rawQuery(
                "SELECT $SELECT_COLUMNS FROM $TABLE WHERE scope_key = ? " +
                    "ORDER BY received_at DESC, rowid DESC",
                arrayOf(scopeKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += readBankRow(cursor)
                }
            }
            rows
        }.onFailure { Log.e(TAG, "list all bank failed", it) }.getOrDefault(emptyList())
    }

    fun listBank(
        scopeKey: String,
        filter: String = "all",
        offset: Int = 0,
        limit: Int = 50,
        nowMs: Long = System.currentTimeMillis(),
        source: BankSource? = null,
        collectionId: String? = null,
    ): List<BankRow> = synchronized(lock) {
        val db = database() ?: return emptyList()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, 100)
        return runCatching {
            expire(db, scopeKey, nowMs)
            val (where, args) = when (filter) {
                "unused" -> "scope_key = ? AND state = ?" to arrayOf(scopeKey, STATE_AVAILABLE)
                "answered" -> "scope_key = ? AND state = ?" to arrayOf(scopeKey, STATE_CONSUMED)
                "wrong" -> "scope_key = ? AND state = ? AND last_result = ?" to
                    arrayOf(scopeKey, STATE_CONSUMED, RESULT_WRONG)
                "correct" -> "scope_key = ? AND state = ? AND last_result = ?" to
                    arrayOf(scopeKey, STATE_CONSUMED, RESULT_CORRECT)
                else -> "scope_key = ?" to arrayOf(scopeKey)
            }
            // The maintenance page filters by source; a named tag wins over
            // the coarse CUSTOM bucket. The default stays unfiltered so legacy
            // callers keep seeing the whole local bank.
            val (sourceWhere, sourceArgs) = when {
                !collectionId.isNullOrBlank() -> " AND collection_id = ?" to arrayOf(collectionId)
                source == BankSource.COURSE_AI -> " AND collection_id IS NULL" to emptyArray()
                source == BankSource.CUSTOM -> " AND collection_id IS NOT NULL" to emptyArray()
                else -> "" to emptyArray<String>()
            }
            val order = if (filter == "wrong" || filter == "answered") {
                "answered_at DESC, received_at DESC, rowid DESC"
            } else {
                "received_at DESC, rowid DESC"
            }
            val rows = mutableListOf<BankRow>()
            db.rawQuery(
                "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $where$sourceWhere " +
                    "ORDER BY $order LIMIT ? OFFSET ?",
                args + sourceArgs + arrayOf(safeLimit.toString(), safeOffset.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += readBankRow(cursor)
                }
            }
            rows
        }.onFailure { Log.e(TAG, "list bank failed", it) }.getOrDefault(emptyList())
    }

    fun bankCounts(
        scopeKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): BankCounts = synchronized(lock) {
        val db = database() ?: return BankCounts()
        runCatching {
            expire(db, scopeKey, nowMs)
            var total = 0
            var unused = 0
            var inRound = 0
            var correct = 0
            var wrong = 0
            var legacy = 0
            var expired = 0
            db.rawQuery(
                "SELECT state, last_result, COUNT(*) FROM $TABLE " +
                    "WHERE scope_key = ? GROUP BY state, last_result",
                arrayOf(scopeKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val state = cursor.getString(0)
                    val result = if (cursor.isNull(1)) null else cursor.getString(1)
                    val count = cursor.getInt(2)
                    total += count
                    when (state) {
                        STATE_AVAILABLE -> unused += count
                        STATE_RESERVED -> inRound += count
                        STATE_CONSUMED -> when (result) {
                            RESULT_CORRECT -> correct += count
                            RESULT_WRONG -> wrong += count
                            else -> legacy += count
                        }
                        STATE_EXPIRED -> expired += count
                    }
                }
            }
            BankCounts(total, unused, inRound, correct, wrong, legacy, expired)
        }.onFailure { Log.e(TAG, "bank counts failed", it) }.getOrDefault(BankCounts())
    }

    /**
     * Writes per-question outcomes. Repeating the same round is a no-op for
     * statistics, which makes recovery and retries safe.
     */
    fun writeAnswers(
        roundId: String,
        scopeKey: String,
        answers: List<AnswerWrite>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val db = database() ?: return false
        db.beginTransaction()
        try {
            writeAnswersLocked(db, roundId, scopeKey, answers, nowMs)
            db.setTransactionSuccessful()
            true
        } catch (error: Exception) {
            Log.e(TAG, "write answers failed", error)
            false
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Finalizes a round: writes every answer, then releases questions that were
     * reserved but never reached. Already answered rows stay consumed.
     */
    fun settleRound(
        roundId: String,
        scopeKey: String,
        answers: List<AnswerWrite>,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        val db = database() ?: return false
        db.beginTransaction()
        try {
            writeAnswersLocked(db, roundId, scopeKey, answers, nowMs)
            db.update(
                TABLE,
                ContentValues().apply {
                    put("state", STATE_AVAILABLE)
                    putNull("reserved_by")
                    putNull("reserved_at")
                },
                "scope_key = ? AND state = ? AND reserved_by = ?",
                arrayOf(scopeKey, STATE_RESERVED, roundId),
            )
            db.setTransactionSuccessful()
            true
        } catch (error: Exception) {
            Log.e(TAG, "settle round failed", error)
            false
        } finally {
            db.endTransaction()
        }
    }

    fun releaseOrphans(activeSessionId: String? = null): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            val values = ContentValues().apply {
                put("state", STATE_AVAILABLE)
                putNull("reserved_by")
                putNull("reserved_at")
            }
            if (activeSessionId == null) {
                db.update(TABLE, values, "state = ?", arrayOf(STATE_RESERVED))
            } else {
                db.update(
                    TABLE,
                    values,
                    "state = ? AND (reserved_by IS NULL OR reserved_by != ?)",
                    arrayOf(STATE_RESERVED, activeSessionId),
                )
            }
            true
        }.onFailure { Log.e(TAG, "release orphans failed", it) }.getOrDefault(false)
    }

    /**
     * Source-aware cleanup. The default is the AI/seed namespace
     * (`collection_id IS NULL`), so a legacy caller can never erase imported
     * rows. A custom clear must name one tag unless the caller explicitly
     * chose the "all custom" confirmation path.
     */
    fun clearBank(
        scopeKey: String,
        mode: ClearMode,
        source: BankSource = BankSource.COURSE_AI,
        collectionId: String? = null,
    ): Boolean = synchronized(lock) {
        val db = database() ?: return false
        val (stateWhere, stateArgs) = when (mode) {
            ClearMode.UNUSED -> " AND state IN (?, ?)" to
                arrayOf(STATE_AVAILABLE, STATE_EXPIRED)
            ClearMode.ANSWERED -> " AND state = ?" to arrayOf(STATE_CONSUMED)
            ClearMode.CORRECT -> " AND state = ? AND last_result = ?" to
                arrayOf(STATE_CONSUMED, RESULT_CORRECT)
            ClearMode.WRONG -> " AND state = ? AND last_result = ?" to
                arrayOf(STATE_CONSUMED, RESULT_WRONG)
            ClearMode.ALL -> "" to emptyArray()
        }
        val (sourceWhere, sourceArgs) = when (source) {
            BankSource.COURSE_AI -> " AND collection_id IS NULL" to emptyArray<String>()
            BankSource.CUSTOM -> if (collectionId.isNullOrBlank()) {
                " AND collection_id IS NOT NULL" to emptyArray<String>()
            } else {
                " AND collection_id = ?" to arrayOf(collectionId)
            }
            BankSource.ALL -> "" to emptyArray<String>()
        }
        val where = "scope_key = ?" + stateWhere + sourceWhere
        val args = arrayOf(scopeKey) + stateArgs + sourceArgs
        return runCatching {
            db.delete(TABLE, where, args)
            true
        }.onFailure { Log.e(TAG, "clear bank failed", it) }.getOrDefault(false)
    }

    fun importLegacyReady(session: QuizSession, nowMs: Long = System.currentTimeMillis()): AppendResult {
        if (session.status != com.planyourtv.policy.quiz.QuizSessionStatus.READY) {
            return AppendResult(rejected = session.items.size)
        }
        if (session.source.startsWith("cloud")) return AppendResult(rejected = session.items.size)
        return appendLocalQuestions(session.id, session.items, nowMs)
    }

    fun migrateLegacyCloudScope(deviceNo: String): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            db.update(
                TABLE,
                ContentValues().apply { put("scope_key", cloudScope(deviceNo)) },
                "scope_key = ? AND source_kind = ?",
                arrayOf("cloud:legacy", SOURCE_CLOUD),
            )
            true
        }.onFailure { Log.e(TAG, "migrate cloud scope failed", it) }.getOrDefault(false)
    }

    fun clearCloudScopes(): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            db.delete(TABLE, "scope_key LIKE ?", arrayOf("cloud:%"))
            true
        }.onFailure { Log.e(TAG, "clear cloud scopes failed", it) }.getOrDefault(false)
    }

    fun release(sessionId: String): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            db.update(
                TABLE,
                ContentValues().apply {
                    put("state", STATE_AVAILABLE)
                    putNull("reserved_by")
                    putNull("reserved_at")
                },
                "state = ? AND reserved_by = ?",
                arrayOf(STATE_RESERVED, sessionId),
            )
            true
        }.onFailure { Log.e(TAG, "release failed", it) }.getOrDefault(false)
    }

    /** Legacy helper: marks reserved rows consumed without a per-question result. */
    fun consume(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            db.update(
                TABLE,
                ContentValues().apply {
                    put("state", STATE_CONSUMED)
                    put("consumed_at", nowMs)
                },
                "state = ? AND reserved_by = ?",
                arrayOf(STATE_RESERVED, sessionId),
            )
            true
        }.onFailure { Log.e(TAG, "consume failed", it) }.getOrDefault(false)
    }

    fun removePublished(sessionId: String): Boolean = synchronized(lock) {
        val db = database() ?: return false
        return runCatching {
            db.delete(
                TABLE,
                "source_ref LIKE ? AND state = ?",
                arrayOf("local:$sessionId:%", STATE_AVAILABLE),
            )
            true
        }.onFailure { Log.e(TAG, "remove published failed", it) }.getOrDefault(false)
    }

    fun clearAvailable(scopeKey: String = LOCAL_SCOPE): Boolean = synchronized(lock) {
        val db = database() ?: return false
        val legacyOnly = if (scopeKey == LOCAL_SCOPE) " AND collection_id IS NULL" else ""
        return runCatching {
            db.delete(
                TABLE,
                "scope_key = ? AND state = ?$legacyOnly",
                arrayOf(scopeKey, STATE_AVAILABLE),
            )
            true
        }.onFailure { Log.e(TAG, "clear available failed", it) }.getOrDefault(false)
    }

    fun clearUnconsumed(scopeKey: String = LOCAL_SCOPE): Boolean = synchronized(lock) {
        val db = database() ?: return false
        val legacyOnly = if (scopeKey == LOCAL_SCOPE) " AND collection_id IS NULL" else ""
        return runCatching {
            db.delete(
                TABLE,
                "scope_key = ? AND state IN (?, ?)$legacyOnly",
                arrayOf(scopeKey, STATE_AVAILABLE, STATE_RESERVED),
            )
            true
        }.onFailure { Log.e(TAG, "clear unconsumed failed", it) }.getOrDefault(false)
    }

    fun counts(scopeKey: String = LOCAL_SCOPE, nowMs: Long = System.currentTimeMillis()): Counts =
        synchronized(lock) {
            val db = database() ?: return Counts()
            runCatching {
                expire(db, scopeKey, nowMs)
                val result = IntArray(4)
                db.rawQuery(
                    "SELECT state, COUNT(*) FROM $TABLE WHERE scope_key = ? GROUP BY state",
                    arrayOf(scopeKey),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val index = when (cursor.getString(0)) {
                            STATE_AVAILABLE -> 0
                            STATE_RESERVED -> 1
                            STATE_CONSUMED -> 2
                            STATE_EXPIRED -> 3
                            else -> -1
                        }
                        if (index >= 0) result[index] = cursor.getInt(1)
                    }
                }
                Counts(result[0], result[1], result[2], result[3])
            }.onFailure { Log.e(TAG, "counts failed", it) }.getOrDefault(Counts())
        }

    fun availableCount(
        scopeKey: String = LOCAL_SCOPE,
        nowMs: Long = System.currentTimeMillis(),
        answerScope: QuizAnswerScope? = null,
        answerKind: QuizTypeFilter = QuizTypeFilter.ANY,
    ): Int = synchronized(lock) {
        if (answerScope == null && answerKind == QuizTypeFilter.ANY) {
            return counts(scopeKey, nowMs).available
        }
        val db = database() ?: return 0
        runCatching {
            expire(db, scopeKey, nowMs)
            val (scopeWhere, scopeArgs) = answerScopeWhere(answerScope)
            val (kindWhere, kindArgs) = answerKindWhere(answerKind)
            db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE scope_key = ? AND state = ? " +
                    "AND (expires_at IS NULL OR expires_at > ?)$scopeWhere$kindWhere",
                arrayOf(scopeKey, STATE_AVAILABLE, nowMs.toString()) + scopeArgs + kindArgs,
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }.onFailure { Log.e(TAG, "scoped available count failed", it) }.getOrDefault(0)
    }

    /**
     * The web course picker shows the mixed total and the single/multiple split
     * before a parent saves a type-filtered source.
     */
    fun availableTypeCounts(
        scopeKey: String = LOCAL_SCOPE,
        nowMs: Long = System.currentTimeMillis(),
        answerScope: QuizAnswerScope,
    ): AnswerKindCounts = synchronized(lock) {
        val db = database() ?: return AnswerKindCounts()
        runCatching {
            expire(db, scopeKey, nowMs)
            val (scopeWhere, scopeArgs) = answerScopeWhere(answerScope)
            var total = 0
            var single = 0
            var multiple = 0
            db.rawQuery(
                "SELECT answer_kind, COUNT(*) FROM $TABLE " +
                    "WHERE scope_key = ? AND state = ? " +
                    "AND (expires_at IS NULL OR expires_at > ?)$scopeWhere " +
                    "GROUP BY answer_kind",
                arrayOf(scopeKey, STATE_AVAILABLE, nowMs.toString()) + scopeArgs,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val count = cursor.getInt(1)
                    total += count
                    when (cursor.getString(0)) {
                        "single" -> single += count
                        "multiple" -> multiple += count
                    }
                }
            }
            AnswerKindCounts(total, single, multiple)
        }.onFailure { Log.e(TAG, "answer kind counts failed", it) }.getOrDefault(AnswerKindCounts())
    }

    /**
     * Available rows in one custom tag. Imported questions carry no curriculum
     * scope, so this must not reuse [answerScopeWhere].
     */
    fun availableCollectionCount(
        collectionId: String,
        nowMs: Long = System.currentTimeMillis(),
        scopeKey: String = LOCAL_SCOPE,
    ): Int = synchronized(lock) {
        if (collectionId.isBlank()) return 0
        val db = database() ?: return 0
        runCatching {
            expire(db, scopeKey, nowMs)
            db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE scope_key = ? AND state = ? " +
                    "AND (expires_at IS NULL OR expires_at > ?) AND collection_id = ?",
                arrayOf(scopeKey, STATE_AVAILABLE, nowMs.toString(), collectionId),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }.onFailure { Log.e(TAG, "collection available count failed", it) }.getOrDefault(0)
    }

    /** All local rows split into the AI/seed namespace and custom tag rows. */
    fun sourceCounts(
        scopeKey: String = LOCAL_SCOPE,
        nowMs: Long = System.currentTimeMillis(),
    ): SourceCounts = synchronized(lock) {
        val db = database() ?: return SourceCounts()
        runCatching {
            expire(db, scopeKey, nowMs)
            var course = 0
            var custom = 0
            db.rawQuery(
                "SELECT collection_id IS NULL, COUNT(*) FROM $TABLE " +
                    "WHERE scope_key = ? GROUP BY collection_id IS NULL",
                arrayOf(scopeKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getInt(0) != 0) course += cursor.getInt(1) else custom += cursor.getInt(1)
                }
            }
            SourceCounts(course, custom)
        }.onFailure { Log.e(TAG, "source counts failed", it) }.getOrDefault(SourceCounts())
    }

    /** Per-tag state totals; tags without rows are left for the UI to zero-fill. */
    fun collectionCounts(
        scopeKey: String = LOCAL_SCOPE,
    ): List<CollectionCounts> = synchronized(lock) {
        val db = database() ?: return emptyList()
        runCatching {
            val totals = LinkedHashMap<String, IntArray>()
            db.rawQuery(
                "SELECT collection_id, state, last_result, COUNT(*) FROM $TABLE " +
                    "WHERE scope_key = ? AND collection_id IS NOT NULL " +
                    "GROUP BY collection_id, state, last_result",
                arrayOf(scopeKey),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val counts = totals.getOrPut(id) { IntArray(7) }
                    val count = cursor.getInt(3)
                    counts[0] += count
                    when (cursor.getString(1)) {
                        STATE_AVAILABLE -> counts[1] += count
                        STATE_RESERVED -> counts[2] += count
                        STATE_CONSUMED -> when (if (cursor.isNull(2)) null else cursor.getString(2)) {
                            RESULT_CORRECT -> counts[3] += count
                            RESULT_WRONG -> counts[4] += count
                            else -> counts[5] += count
                        }
                        STATE_EXPIRED -> counts[6] += count
                    }
                }
            }
            totals.map { (id, counts) ->
                CollectionCounts(
                    collectionId = id,
                    counts = BankCounts(
                        total = counts[0],
                        unused = counts[1],
                        inRound = counts[2],
                        correct = counts[3],
                        wrong = counts[4],
                        legacy = counts[5],
                        expired = counts[6],
                    ),
                )
            }
        }.onFailure { Log.e(TAG, "collection counts failed", it) }.getOrDefault(emptyList())
    }

    /**
     * The SQL twin of the old in-memory matcher. Grade, semester, topic and
     * difficulty are always strict; subject/textbook are wildcards only when
     * the answer scope uses `ALL`. Rows without a scope never match a custom
     * range because their nullable columns yield NULL, not a wildcard.
     */
    private fun answerScopeWhere(scope: QuizAnswerScope?): Pair<String, List<String>> {
        if (scope == null) return "" to emptyList()
        val clauses = mutableListOf(
            "scope_grade = ?",
            "scope_semester = ?",
            "UPPER(scope_type) = ?",
        )
        val args = mutableListOf(
            scope.grade.toString(),
            scope.semester.toString(),
            scope.scopeType.uppercase(),
        )
        if (!scope.subject.equals(QuizAnswerScope.SUBJECT_ALL, ignoreCase = true)) {
            clauses += "UPPER(scope_subject) = ?"
            args += scope.subject.uppercase()
        }
        if (!scope.textbook.equals(QuizAnswerScope.TEXTBOOK_ALL, ignoreCase = true)) {
            clauses += "UPPER(scope_textbook) = ?"
            args += scope.textbook.uppercase()
        }
        if (scope.scopeType.equals("UNIT", ignoreCase = true)) {
            clauses += "scope_unit_no = ?"
            args += scope.unitNo.toString()
        } else {
            clauses += "scope_unit_no IS NULL"
        }
        val difficulty = scope.difficulty
        if (difficulty != null) {
            clauses += "LOWER(difficulty) = ?"
            args += difficulty.lowercase()
        }
        return " AND " + clauses.joinToString(" AND ") to args
    }

    private fun answerKindWhere(kind: QuizTypeFilter): Pair<String, List<String>> =
        if (kind == QuizTypeFilter.ANY) {
            "" to emptyList()
        } else {
            " AND answer_kind = ?" to listOf(kind.wire)
        }

    private fun courseWhere(
        scopeKey: String,
        nowMs: Long,
        scope: QuizAnswerScope,
        answerKind: QuizTypeFilter,
    ): Pair<String, List<String>> {
        val (scopeWhere, scopeArgs) = answerScopeWhere(scope)
        val (kindWhere, kindArgs) = answerKindWhere(answerKind)
        return "scope_key = ? AND state = ? " +
            "AND (expires_at IS NULL OR expires_at > ?)$scopeWhere$kindWhere" to
            listOf(scopeKey, STATE_AVAILABLE, nowMs.toString()) + scopeArgs + kindArgs
    }

    private fun collectionWhere(
        scopeKey: String,
        nowMs: Long,
        collectionId: String,
    ): Pair<String, List<String>> =
        "scope_key = ? AND state = ? " +
            "AND (expires_at IS NULL OR expires_at > ?) AND collection_id = ?" to
            listOf(scopeKey, STATE_AVAILABLE, nowMs.toString(), collectionId)

    private fun countAvailable(db: SQLiteDatabase, where: Pair<String, List<String>>): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE ${where.first}",
            where.second.toTypedArray(),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun wrongReviewCount(db: SQLiteDatabase, scopeKey: String): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE " +
                "WHERE scope_key = ? AND state = ? AND last_result = ?",
            arrayOf(scopeKey, STATE_CONSUMED, RESULT_WRONG),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * The web and runtime share one temp table per connection. It is reset at
     * the start of every package round, so a failed transaction cannot leak
     * exclusions into the next attempt.
     */
    private fun resetRoundExclusions(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TEMP TABLE IF NOT EXISTS $ROUND_EXCLUSION_TABLE (" +
                "kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(kind, value))",
        )
        db.execSQL("DELETE FROM $ROUND_EXCLUSION_TABLE")
    }

    private fun addRoundExclusion(db: SQLiteDatabase, kind: String, value: String) {
        if (value.isBlank()) return
        db.execSQL(
            "INSERT OR IGNORE INTO $ROUND_EXCLUSION_TABLE(kind, value) VALUES (?, ?)",
            arrayOf(kind, value),
        )
    }

    /**
     * Block the whole wrong pool for normal sources before drawing starts.
     * Otherwise a wrong question that appears later in the source order could
     * be duplicated by a normal source and never cleared this round.
     */
    private fun blockWrongReviewCandidates(db: SQLiteDatabase, scopeKey: String) {
        db.rawQuery(
            "SELECT question_id, fingerprint FROM $TABLE " +
                "WHERE scope_key = ? AND state = ? AND last_result = ?",
            arrayOf(scopeKey, STATE_CONSUMED, RESULT_WRONG),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                addRoundExclusion(db, "wrong-id", cursor.getString(0).orEmpty())
                addRoundExclusion(db, "wrong-fp", cursor.getString(1).orEmpty())
            }
        }
    }

    private fun drawCourseSource(
        db: SQLiteDatabase,
        scopeKey: String,
        nowMs: Long,
        source: QuizPackageSource.Course,
        random: Random,
        usage: MutableMap<String, Int>,
    ): CachedQuestion? {
        val (base, args) = courseWhere(scopeKey, nowMs, source.scope, source.answerKind)
        val where = base + NORMAL_SOURCE_EXCLUSION
        if (!source.scope.subject.equals(QuizAnswerScope.SUBJECT_ALL, ignoreCase = true)) {
            return drawRandomRow(db, where, args, random)
        }
        // A wildcard subject must not let one subject fill the round. Prefer a
        // subject that has not appeared yet from this source, then the least
        // used, and randomize ties.
        val subjects = mutableListOf<String>()
        db.rawQuery(
            "SELECT UPPER(scope_subject), COUNT(*) FROM $TABLE WHERE $where " +
                "GROUP BY UPPER(scope_subject)",
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) subjects += cursor.getString(0).orEmpty()
        }
        if (subjects.isEmpty()) return null
        val unused = subjects.filter { usage[it] == null }
        val pool = if (unused.isNotEmpty()) {
            unused
        } else {
            val minimum = subjects.minOf { usage[it] ?: 0 }
            subjects.filter { (usage[it] ?: 0) == minimum }
        }
        val subject = pool[random.nextInt(pool.size)]
        val row = drawRandomRow(
            db,
            "$where AND UPPER(scope_subject) = ?",
            args + subject,
            random,
        )
        if (row != null) usage[subject] = (usage[subject] ?: 0) + 1
        return row
    }

    private fun drawCollectionSource(
        db: SQLiteDatabase,
        scopeKey: String,
        nowMs: Long,
        collectionId: String,
        random: Random,
    ): CachedQuestion? {
        val (base, args) = collectionWhere(scopeKey, nowMs, collectionId)
        return drawRandomRow(db, base + NORMAL_SOURCE_EXCLUSION, args, random)
    }

    private fun drawWrongSource(
        db: SQLiteDatabase,
        scopeKey: String,
        random: Random,
    ): CachedQuestion? {
        val where = "scope_key = ? AND state = ? AND last_result = ?$PICKED_EXCLUSION"
        return drawRandomRow(
            db,
            where,
            listOf(scopeKey, STATE_CONSUMED, RESULT_WRONG),
            random,
        )
    }

    /**
     * Uniform random row without SQLite's `ORDER BY RANDOM()` sort. The stable
     * ordering plus an offset from the injected [Random] makes tests
     * reproducible while still covering the whole candidate pool.
     */
    private fun drawRandomRow(
        db: SQLiteDatabase,
        where: String,
        args: List<String>,
        random: Random,
    ): CachedQuestion? {
        val total = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE $where",
            args.toTypedArray(),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (total <= 0) return null
        val offset = random.nextInt(total)
        db.rawQuery(
            "SELECT $SELECT_COLUMNS FROM $TABLE WHERE $where " +
                "ORDER BY received_at ASC, rowid ASC LIMIT 1 OFFSET ?",
            (args + offset.toString()).toTypedArray(),
        ).use { cursor ->
            if (cursor.moveToNext()) return readQuestion(cursor)
        }
        return null
    }

    private fun database(): SQLiteDatabase? = helper?.writableDatabase

    private fun expire(db: SQLiteDatabase, scopeKey: String, nowMs: Long) {
        db.update(
            TABLE,
            ContentValues().apply { put("state", STATE_EXPIRED) },
            "scope_key = ? AND state = ? AND expires_at IS NOT NULL AND expires_at <= ?",
            arrayOf(scopeKey, STATE_AVAILABLE, nowMs.toString()),
        )
    }

    private fun rowCount(db: SQLiteDatabase, scopeKey: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE scope_key = ?", arrayOf(scopeKey)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun countState(db: SQLiteDatabase, scopeKey: String, state: String): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE scope_key = ? AND state = ?",
            arrayOf(scopeKey, state),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Content identity is scoped to one namespace: AI/seed rows compare only
     * with `collection_id IS NULL`, an import compares only with its own tag.
     * Cross-source duplicates are allowed and are counted separately.
     */
    private fun fingerprintExists(
        db: SQLiteDatabase,
        scopeKey: String,
        fp: String,
        collectionId: String?,
    ): Boolean {
        val selection = if (collectionId == null) {
            "scope_key = ? AND fingerprint = ? AND collection_id IS NULL"
        } else {
            "scope_key = ? AND fingerprint = ? AND collection_id = ?"
        }
        val args = if (collectionId == null) {
            arrayOf(scopeKey, fp)
        } else {
            arrayOf(scopeKey, fp, collectionId)
        }
        return db.rawQuery("SELECT 1 FROM $TABLE WHERE $selection LIMIT 1", args)
            .use { it.moveToFirst() }
    }

    private fun fingerprintExistsOutsideCollection(
        db: SQLiteDatabase,
        scopeKey: String,
        fp: String,
        collectionId: String,
    ): Boolean =
        db.rawQuery(
            "SELECT 1 FROM $TABLE WHERE scope_key = ? AND fingerprint = ? " +
                "AND (collection_id IS NULL OR collection_id != ?) LIMIT 1",
            arrayOf(scopeKey, fp, collectionId),
        ).use { it.moveToFirst() }

    private fun sourceRefExists(db: SQLiteDatabase, sourceKind: String, sourceRef: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM $TABLE WHERE source_kind = ? AND source_ref = ? LIMIT 1",
            arrayOf(sourceKind, sourceRef),
        ).use { it.moveToFirst() }

    private fun writeAnswersLocked(
        db: SQLiteDatabase,
        roundId: String,
        scopeKey: String,
        answers: List<AnswerWrite>,
        nowMs: Long,
    ) {
        answers.forEach { answer ->
            val row = findAnswerRow(db, scopeKey, answer) ?: return@forEach
            val (cacheId, state, lastRoundId, wrongCount) = row
            if (state == STATE_CONSUMED && lastRoundId == roundId) return@forEach
            if (state != STATE_RESERVED && !(state == STATE_CONSUMED && lastRoundId.isNullOrBlank())) {
                return@forEach
            }
            val values = ContentValues().apply {
                put("state", STATE_CONSUMED)
                put("last_result", if (answer.correct) RESULT_CORRECT else RESULT_WRONG)
                put("wrong_count", wrongCount + if (answer.correct) 0 else 1)
                put("answered_at", if (answer.answeredAt > 0) answer.answeredAt else nowMs)
                put("last_round_id", roundId)
                put("last_timed_out", if (answer.timedOut) 1 else 0)
                if (answer.selectedIndex == null) {
                    putNull("last_selected_index")
                } else {
                    put("last_selected_index", answer.selectedIndex)
                }
                val selectedIndices = answer.selectedIndices.distinct().sorted()
                if (selectedIndices.isEmpty()) {
                    putNull("last_selected_indices")
                } else {
                    put("last_selected_indices", JSONArray(selectedIndices).toString())
                }
                put("consumed_at", nowMs)
                putNull("reserved_by")
                putNull("reserved_at")
            }
            db.update(TABLE, values, "cache_id = ?", arrayOf(cacheId))
        }
    }

    private fun findAnswerRow(
        db: SQLiteDatabase,
        scopeKey: String,
        answer: AnswerWrite,
    ): AnswerRow? {
        val selection: String
        val args: Array<String>
        if (answer.cacheId.isNotBlank()) {
            selection = "scope_key = ? AND cache_id = ?"
            args = arrayOf(scopeKey, answer.cacheId)
        } else if (answer.questionId.isNotBlank()) {
            selection = "scope_key = ? AND question_id = ?"
            args = arrayOf(scopeKey, answer.questionId)
        } else {
            return null
        }
        return db.rawQuery(
            "SELECT cache_id, state, last_round_id, wrong_count FROM $TABLE " +
                "WHERE $selection LIMIT 1",
            args,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            AnswerRow(
                cacheId = cursor.getString(0),
                state = cursor.getString(1),
                lastRoundId = if (cursor.isNull(2)) null else cursor.getString(2),
                wrongCount = cursor.getInt(3),
            )
        }
    }

    private data class AnswerRow(
        val cacheId: String,
        val state: String,
        val lastRoundId: String?,
        val wrongCount: Int,
    )

    private fun readQuestion(cursor: Cursor): CachedQuestion {
        val raw = cursor.getString(1)
        val question = QuizSessionCodec.decodeItem(raw)
            ?: throw CacheException("corrupt cached question")
        return CachedQuestion(
            cacheId = cursor.getString(0),
            question = question,
            sourceKind = cursor.getString(2) ?: "",
            sourceRef = if (cursor.isNull(3)) null else cursor.getString(3),
            scopeKey = cursor.getString(4),
            collectionId = if (cursor.isNull(5)) null else cursor.getString(5),
            receivedAt = cursor.getLong(6),
            expiresAt = if (cursor.isNull(7)) null else cursor.getLong(7),
            state = cursor.getString(8) ?: STATE_AVAILABLE,
        )
    }

    private fun readBankRow(cursor: Cursor): BankRow {
        val raw = cursor.getString(1)
        val question = QuizSessionCodec.decodeItem(raw)
            ?: throw CacheException("corrupt cached question")
        return BankRow(
            cacheId = cursor.getString(0),
            question = question,
            sourceKind = cursor.getString(2) ?: "",
            sourceRef = if (cursor.isNull(3)) null else cursor.getString(3),
            scopeKey = cursor.getString(4),
            collectionId = if (cursor.isNull(5)) null else cursor.getString(5),
            receivedAt = cursor.getLong(6),
            expiresAt = if (cursor.isNull(7)) null else cursor.getLong(7),
            state = cursor.getString(8) ?: STATE_AVAILABLE,
            lastResult = if (cursor.isNull(9)) null else cursor.getString(9),
            wrongCount = cursor.getInt(10),
            answeredAt = if (cursor.isNull(11)) null else cursor.getLong(11),
            timedOut = cursor.getInt(12) != 0,
            selectedIndex = if (cursor.isNull(13)) null else cursor.getInt(13),
            selectedIndices = if (cursor.isNull(14)) {
                emptyList()
            } else {
                runCatching {
                    val array = JSONArray(cursor.getString(14))
                    (0 until array.length()).map { array.getInt(it) }.distinct().sorted()
                }.getOrDefault(emptyList())
            },
        )
    }

    private fun isValid(item: QuizItem): Boolean = runCatching {
        QuizSessionCodec.decodeItem(QuizSessionCodec.encodeItem(item).toString()) != null
    }.getOrDefault(false)

    /**
     * Scope columns let the hot paths (bank state after every answer, reserve
     * at round start) filter in SQLite instead of decoding the whole bank.
     * `question_json` remains the source of truth; these are a query index.
     */
    private fun ContentValues.putScopeFields(item: QuizItem) {
        if (item.scopeGrade == null) putNull("scope_grade") else put("scope_grade", item.scopeGrade)
        if (item.scopeSemester == null) putNull("scope_semester") else put("scope_semester", item.scopeSemester)
        put("scope_subject", item.scopeSubject)
        put("scope_textbook", item.scopeTextbook)
        put("scope_type", item.scopeType)
        if (item.scopeUnitNo == null) putNull("scope_unit_no") else put("scope_unit_no", item.scopeUnitNo)
        put("difficulty", item.difficulty)
        put("answer_kind", item.answerKind.wire)
    }

    private fun fingerprint(item: QuizItem): String {
        val answerKey = if (item.isMultipleChoice) {
            "multiple:" + item.answerIndices.distinct().sorted().joinToString(",")
        } else {
            // Keep the legacy single-choice canonical string byte-for-byte
            // stable so an upgrade never re-imports an existing bank.
            item.answerIndex.toString()
        }
        val canonical = item.prompt.trim().lowercase() + " " +
            item.options.joinToString(" ") { it.trim().lowercase() } + " " +
            answerKey
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private class Helper(context: Context, val dbName: String) :
        SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "cache_id TEXT PRIMARY KEY, " +
                    "question_json TEXT NOT NULL, " +
                    "question_id TEXT NOT NULL DEFAULT '', " +
                    "source_kind TEXT NOT NULL, " +
                    "source_ref TEXT, " +
                    "scope_key TEXT NOT NULL, " +
                    "collection_id TEXT, " +
                    "scope_grade INTEGER, " +
                    "scope_semester INTEGER, " +
                    "scope_subject TEXT NOT NULL DEFAULT '', " +
                    "scope_textbook TEXT NOT NULL DEFAULT '', " +
                    "scope_type TEXT NOT NULL DEFAULT '', " +
                    "scope_unit_no INTEGER, " +
                    "difficulty TEXT NOT NULL DEFAULT '', " +
                    "answer_kind TEXT NOT NULL DEFAULT '', " +
                    "fingerprint TEXT NOT NULL, " +
                    "state TEXT NOT NULL, " +
                    "reserved_by TEXT, " +
                    "received_at INTEGER NOT NULL, " +
                    "expires_at INTEGER, " +
                    "reserved_at INTEGER, " +
                    "consumed_at INTEGER, " +
                    "last_result TEXT, " +
                    "wrong_count INTEGER NOT NULL DEFAULT 0, " +
                    "answered_at INTEGER, " +
                    "last_round_id TEXT, " +
                    "last_timed_out INTEGER NOT NULL DEFAULT 0, " +
                    "last_selected_index INTEGER, " +
                    "last_selected_indices TEXT)",
            )
            createIndexes(db)
            createAnswerScopeIndex(db)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_answer_kind " +
                    "ON $TABLE(scope_key, answer_kind)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 1) {
                onCreate(db)
                return
            }
            if (oldVersion < 2) {
                addColumn(db, "question_id", "TEXT NOT NULL DEFAULT ''")
                addColumn(db, "last_result", "TEXT")
                addColumn(db, "wrong_count", "INTEGER NOT NULL DEFAULT 0")
                addColumn(db, "answered_at", "INTEGER")
                addColumn(db, "last_round_id", "TEXT")
                addColumn(db, "last_timed_out", "INTEGER NOT NULL DEFAULT 0")
                addColumn(db, "last_selected_index", "INTEGER")
                addColumn(db, "last_selected_indices", "TEXT")
                backfillQuestionIds(db)
                db.execSQL(
                    "UPDATE $TABLE SET scope_key = 'cloud:legacy' " +
                        "WHERE scope_key = ? AND source_kind = ?",
                    arrayOf(LOCAL_SCOPE, SOURCE_CLOUD),
                )
                createIndexes(db)
            }
            // The type column must exist before addScopeColumns backfills scope
            // fields; putScopeFields now writes both sets of columns.
            if (oldVersion < 4) {
                ensureAnswerKindColumn(db)
            }
            if (oldVersion < 3) {
                addScopeColumns(db)
            }
        }

        /**
         * `collection_id` deliberately does not bump DB_VERSION: an old APK can
         * still open the shared database after a downgrade. Every open checks
         * the column (new installs already have it) and creates the namespace
         * dedupe index once the column exists.
         */
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            ensureCollectionColumn(db)
            ensureSelectedIndicesColumn(db)
            ensureAnswerKindColumn(db)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_scope_collection_fp " +
                    "ON $TABLE(scope_key, collection_id, fingerprint)",
            )
        }

        private fun ensureCollectionColumn(db: SQLiteDatabase) {
            if (!hasColumn(db, "collection_id")) addColumn(db, "collection_id", "TEXT")
        }

        /**
         * Additive like `collection_id`: no DB_VERSION bump, so an older APK
         * can still open a database that has the extra v7 column.
         */
        private fun ensureSelectedIndicesColumn(db: SQLiteDatabase) {
            if (!hasColumn(db, "last_selected_indices")) {
                addColumn(db, "last_selected_indices", "TEXT")
            }
        }

        /**
         * Additive like `collection_id`, so a downgrade can still open the
         * database. The JSON remains the source of truth; this column is only
         * the type filter for course package sources.
         */
        private fun ensureAnswerKindColumn(db: SQLiteDatabase) {
            if (!hasColumn(db, "answer_kind")) {
                addColumn(db, "answer_kind", "TEXT NOT NULL DEFAULT ''")
                backfillAnswerKinds(db)
            }
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_answer_kind " +
                    "ON $TABLE(scope_key, answer_kind)",
            )
        }

        private fun hasColumn(db: SQLiteDatabase, name: String): Boolean {
            db.rawQuery("PRAGMA table_info($TABLE)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) return true
                }
            }
            return false
        }

        private fun addScopeColumns(db: SQLiteDatabase) {
            addColumn(db, "scope_grade", "INTEGER")
            addColumn(db, "scope_semester", "INTEGER")
            addColumn(db, "scope_subject", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "scope_textbook", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "scope_type", "TEXT NOT NULL DEFAULT ''")
            addColumn(db, "scope_unit_no", "INTEGER")
            addColumn(db, "difficulty", "TEXT NOT NULL DEFAULT ''")
            backfillScopeColumns(db)
            createIndexes(db)
            createAnswerScopeIndex(db)
        }

        private fun backfillScopeColumns(db: SQLiteDatabase) {
            val rows = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT cache_id, question_json FROM $TABLE WHERE scope_type = ''",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0) to cursor.getString(1)
                }
            }
            rows.forEach { (cacheId, raw) ->
                val item = QuizSessionCodec.decodeItem(raw) ?: return@forEach
                db.update(
                    TABLE,
                    ContentValues().apply { putScopeFields(item) },
                    "cache_id = ?",
                    arrayOf(cacheId),
                )
            }
        }

        private fun backfillAnswerKinds(db: SQLiteDatabase) {
            val rows = mutableListOf<Triple<String, String, String>>()
            db.rawQuery(
                "SELECT cache_id, question_json, answer_kind FROM $TABLE WHERE answer_kind = ''",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2).orEmpty())
                }
            }
            rows.forEach { (cacheId, raw, current) ->
                if (current.isNotEmpty()) return@forEach
                val item = QuizSessionCodec.decodeItem(raw) ?: return@forEach
                db.update(
                    TABLE,
                    ContentValues().apply { put("answer_kind", item.answerKind.wire) },
                    "cache_id = ?",
                    arrayOf(cacheId),
                )
            }
        }

        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_qc_state ON $TABLE(state, expires_at)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_qc_scope ON $TABLE(scope_key, state)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_bank_filter " +
                    "ON $TABLE(scope_key, state, last_result, received_at)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_scope_fingerprint " +
                    "ON $TABLE(scope_key, fingerprint)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_question ON $TABLE(scope_key, question_id)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS uk_qc_source ON $TABLE(source_kind, source_ref) " +
                    "WHERE source_ref IS NOT NULL",
            )
        }

        private fun createAnswerScopeIndex(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_qc_answer_scope " +
                    "ON $TABLE(scope_key, state, scope_grade, scope_semester, scope_type)",
            )
        }

        private fun addColumn(db: SQLiteDatabase, name: String, type: String) {
            runCatching {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $name $type")
            }.onFailure { error ->
                if (!error.message.orEmpty().contains("duplicate column")) {
                    Log.w(TAG, "cache migration add column $name failed", error)
                }
            }
        }

        private fun backfillQuestionIds(db: SQLiteDatabase) {
            val rows = mutableListOf<Pair<String, String>>()
            db.rawQuery(
                "SELECT cache_id, question_json FROM $TABLE WHERE question_id = ''",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows += cursor.getString(0) to cursor.getString(1)
                }
            }
            rows.forEach { (cacheId, raw) ->
                val item = QuizSessionCodec.decodeItem(raw) ?: return@forEach
                db.update(
                    TABLE,
                    ContentValues().apply { put("question_id", item.questionId) },
                    "cache_id = ?",
                    arrayOf(cacheId),
                )
            }
        }
    }
}
