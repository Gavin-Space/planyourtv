package app.orchard.tv.quiz

import android.content.Context
import app.orchard.tv.policy.ManualSessionLimits
import app.orchard.tv.policy.PolicyModeCoordinator
import app.orchard.tv.policy.web.ParentWebCenter
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject
import com.planyourtv.policy.PolicyMode
import com.planyourtv.policy.quiz.QuizAnswerKind
import com.planyourtv.policy.quiz.QuizAnswerScope
import com.planyourtv.policy.quiz.QuizBankImport
import com.planyourtv.policy.quiz.QuizCollection
import com.planyourtv.policy.quiz.QuizConfigDocument
import com.planyourtv.policy.quiz.QuizConfigDocumentCodec
import com.planyourtv.policy.quiz.QuizCurriculum
import com.planyourtv.policy.quiz.QuizDecodeResult
import com.planyourtv.policy.quiz.QuizDocDecodeResult
import com.planyourtv.policy.quiz.QuizDocIssue
import com.planyourtv.policy.quiz.QuizEngineConfig
import com.planyourtv.policy.quiz.QuizImportError
import com.planyourtv.policy.quiz.QuizImportResult
import com.planyourtv.policy.quiz.QuizItem
import com.planyourtv.policy.quiz.QuizLevelPackage
import com.planyourtv.policy.quiz.QuizPackageSource
import com.planyourtv.policy.quiz.QuizSession
import com.planyourtv.policy.quiz.QuizSessionCodec
import com.planyourtv.policy.quiz.QuizSessionStatus
import com.planyourtv.policy.quiz.QuizSessionValidation
import com.planyourtv.policy.quiz.QuizUnitCatalog
import com.planyourtv.policy.quiz.SourceIssue
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

/**
 * The quiz page and its small JSON API. PhoneServer hands over only paths below
 * its random page token; this object still checks the pairing secret + parent
 * PIN before it reads or changes any session.
 */
object QuizWebAdmin {
    private const val MAX_JSON_BYTES = 256 * 1024
    private const val MAX_IMPORT_BYTES = 512 * 1024
    private const val MAX_PROVIDER_KEY_LENGTH = 4096
    private const val IMPORT_PATH = "/api/quiz/admin/bank/import"
    /** The web level editor's shape; a cached older page saves through the legacy fields. */
    private const val LEVEL_MODEL = 2
    private const val MAX_LEVEL_COUNT_SCOPES = 64

    /** Device-capability failures share one 400 UNSUPPORTED contract. */
    private val CAPABILITY_ISSUE_CODES = setOf(
        "UNSUPPORTED_FILL",
        "UNSUPPORTED_AUTO_NEXT",
        "UNSUPPORTED_PINYIN",
        "TIME_LIMIT_OVER_MAX",
        "LEVEL_LIMIT_20",
    )

    /**
     * NanoHTTPD keeps one [IHTTPSession] for the whole keep-alive connection,
     * so caching bodies by session would return the first request's body for
     * every later request on that connection. Scope the parsed body to the
     * current request instead: [api] runs synchronously on the connection
     * thread, so a ThreadLocal is request-local here.
     */
    private val requestBody = ThreadLocal<JSONObject?>()

    fun handle(context: Context, session: IHTTPSession, path: String): Response? {
        // PhoneServer still serves wallpaper/setup pages in cloud mode. The quiz API is a
        // local-mode feature. With V27 enabled the page shell is also the parent-center host,
        // so it stays available in NONE/CLOUD; its parent API has its own gate.
        if (isQuizApiPath(path) && PolicyModeCoordinator.mode != PolicyMode.LOCAL) {
            return error(Response.Status.GONE, "LOCAL_ONLY")
        }
        if (isQuizShellPath(path) && PolicyModeCoordinator.mode != PolicyMode.LOCAL &&
            !ParentWebCenter.ENABLED
        ) {
            return error(Response.Status.GONE, "LOCAL_ONLY")
        }
        return when {
            session.method == Method.GET && (path == "/quiz" || path == "/quiz/") ->
                asset(context, "web/quiz.html", "text/html; charset=utf-8")
            session.method == Method.GET && path == "/quiz.js" ->
                asset(context, "web/quiz.js", "application/javascript; charset=utf-8")
            session.method == Method.GET && path == "/quiz.css" ->
                asset(context, "web/quiz.css", "text/css; charset=utf-8")
            session.method == Method.GET && path == "/api/quiz/status" -> {
                json(Response.Status.OK, QuizWeb.statusJson())
            }
            path.startsWith("/api/quiz/") -> api(session, path)
            else -> null
        }
    }

    private fun isQuizShellPath(path: String): Boolean =
        path == "/quiz" || path == "/quiz/" || path == "/quiz.js" || path == "/quiz.css"

    private fun isQuizApiPath(path: String): Boolean = path.startsWith("/api/quiz/")

    private fun api(session: IHTTPSession, path: String): Response {
        // NanoHTTPD 2.3.1 leaves a POST body on the keep-alive stream when the
        // handler never reads it, so the next request would start with "{}POST"
        // and fail with "HTTP verb {}POST unhandled". Read the body once per
        // request; jsonBody() serves it to the handler and clears it after.
        val bodyLimit = if (path == IMPORT_PATH) MAX_IMPORT_BYTES else MAX_JSON_BYTES
        requestBody.set(readJsonBody(session, bodyLimit))
        try {
            if (!sameOrigin(session)) return error(Response.Status.FORBIDDEN, "ORIGIN")
            return when {
                session.method == Method.POST && path == "/api/quiz/pair" -> pair(session)
                session.method == Method.POST && path == "/api/quiz/auth/pin" -> pin(session)
                session.method == Method.POST && path == "/api/quiz/auth/logout" -> logout(session)
                session.method == Method.POST && path == "/api/quiz/auth/release" -> release(session)
                session.method == Method.GET && path == "/api/quiz/admin/heartbeat" -> heartbeat(session)
                session.method == Method.POST && path == "/api/quiz/admin/unlock" -> unlock(session)
                session.method == Method.POST && path == "/api/quiz/admin/manual/start" -> manualStart(session)
                session.method == Method.GET && path == "/api/quiz/admin/session" -> readSession(session)
                session.method == Method.GET && path == "/api/quiz/admin/units" -> readUnitCatalog(session)
                session.method == Method.PUT && path == "/api/quiz/admin/session" -> putSession(session)
                session.method == Method.DELETE && path == "/api/quiz/admin/session" -> deleteSession(session)
                session.method == Method.POST && path == "/api/quiz/admin/challenge-config" -> saveChallengeConfig(session)
                session.method == Method.POST && path == "/api/quiz/admin/level-counts" -> levelSourceCounts(session)
                session.method == Method.GET && path == "/api/quiz/admin/config-document" -> readConfigDocument(session)
                session.method == Method.PUT && path == "/api/quiz/admin/config-document" -> saveConfigDocument(session)
                session.method == Method.GET && path == "/api/quiz/admin/bank" -> readBank(session)
                session.method == Method.GET && path == "/api/quiz/admin/bank/facets" -> readBankFacets(session)
                session.method == Method.POST && path == IMPORT_PATH -> importBank(session)
                session.method == Method.POST && path == "/api/quiz/admin/bank/clear" -> clearBank(session)
                session.method == Method.GET && path == "/api/quiz/admin/cache" -> readCache(session)
                session.method == Method.POST && path == "/api/quiz/admin/cache/clear" -> clearCache(session)
                session.method == Method.POST && path == "/api/quiz/admin/cache/rebuild" -> rebuildCache(session)
                session.method == Method.GET && path == "/api/quiz/admin/provider" -> readProvider(session)
                session.method == Method.POST && path == "/api/quiz/admin/consent" -> setConsent(session)
                session.method == Method.POST && path == "/api/quiz/admin/provider" -> saveProvider(session)
                session.method == Method.POST && path == "/api/quiz/admin/provider/test" -> testProvider(session)
                session.method == Method.GET && path == "/api/quiz/admin/generate/prefs" -> readGenerationPrefs(session)
                session.method == Method.POST && path == "/api/quiz/admin/generate/prefs" -> saveGenerationPrefs(session)
                session.method == Method.POST && path == "/api/quiz/admin/generate" -> startGeneration(session)
                session.method == Method.GET && path == "/api/quiz/admin/generate/status" -> generationStatus(session)
                session.method == Method.POST && path == "/api/quiz/admin/generate/cancel" -> cancelGeneration(session)
                session.method == Method.POST && path == "/api/quiz/admin/generate/add" -> addGeneration(session)
                session.method == Method.POST && path == "/api/quiz/admin/generate/discard" -> discardGeneration(session)
                // The quiz feature switch was removed: local mode always has the quiz, and a
                // cached page must not be able to delete an unstarted challenge here.
                session.method == Method.POST &&
                    (path == "/api/quiz/admin/enable" || path == "/api/quiz/admin/disable") ->
                    error(Response.Status.GONE, "QUIZ_SWITCH_REMOVED")
                session.method == Method.GET && path == "/api/quiz/admin/sync" -> syncStatus(session)
                session.method == Method.POST && path == "/api/quiz/admin/sync/retry" -> syncRetry(session)
                session.method == Method.POST && path == "/api/quiz/admin/sync/use-remote" -> syncUseRemote(session)
                else -> error(Response.Status.NOT_FOUND, "NOT_FOUND")
            }
        } finally {
            requestBody.remove()
        }
    }

    /**
     * Kept only for pages cached before the fixed-URL change. There is no
     * per-scan secret anymore; the PIN is the only first-time gate.
     */
    private fun pair(session: IHTTPSession): Response {
        if (!jsonRequest(session)) return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE")
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("needsPin", true)
                .put("consentAccepted", QuizSessionStore.consentAccepted())
                .put("providerConfigured", QuizSessionStore.providerConfigured()),
        )
    }

    private fun pin(session: IHTTPSession): Response {
        if (!jsonRequest(session)) return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE")
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val pin = body.optString("pin", "")
        if (pin.length != 6 || pin.any { !it.isDigit() }) return error(Response.Status.BAD_REQUEST, "PIN_INVALID")
        // A parent who explicitly confirms takeover replaces the phone that
        // currently owns the page; the old token stops working immediately.
        val takeover = body.optBoolean("takeover", false)
        return when (val result = QuizAccess.authenticate(pin, takeover = takeover)) {
            is QuizAccess.AuthResult.Ok -> json(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("adminToken", result.adminToken)
                    .put("expiresAt", result.expiresAt),
            )
            QuizAccess.AuthResult.PinInvalid -> error(Response.Status.UNAUTHORIZED, "PIN_INVALID")
            QuizAccess.AuthResult.PinNotSet -> error(Response.Status.CONFLICT, "PIN_NOT_SET")
            QuizAccess.AuthResult.ParentInUse -> error(Response.Status.CONFLICT, "PARENT_IN_USE")
            is QuizAccess.AuthResult.PinLocked -> json(
                Response.Status.TOO_MANY_REQUESTS,
                JSONObject()
                    .put("ok", false)
                    .put("error", "PIN_LOCKED")
                    .put("retryAfterSeconds", result.retryAfterSeconds),
            )
        }
    }

    private fun logout(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        QuizAccess.logout(adminToken(session))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    /**
     * Presence probe. The page sends this only while it is visible; [adminError]
     * refreshes the owner's last-seen time. The lock and manualStart objects let the
     * page enable or disable both lock-screen actions without polling extra endpoints.
     */
    private fun heartbeat(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("lock", lockJson(PolicyModeCoordinator.webUnlockStatus()))
                .put("manualStart", manualStartJson(PolicyModeCoordinator.webManualStartStatus())),
        )
    }

    /**
     * The lock-screen-only temporary unlock. The coordinator performs the final atomic
     * check; this handler adds the page's own PIN-attempt limiter and never logs the PIN.
     */
    private fun unlock(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val currentLock = { PolicyModeCoordinator.webUnlockStatus() }
        val wait = QuizAccess.sensitiveActionLockedSeconds()
        if (wait > 0) {
            return json(
                Response.Status.TOO_MANY_REQUESTS,
                JSONObject()
                    .put("ok", false)
                    .put("error", "PIN_LOCKED")
                    .put("retryAfterSeconds", wait)
                    .put("lock", lockJson(currentLock())),
            )
        }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val pin = body.optString("pin", "")
        if (pin.length != 6 || pin.any { !it.isDigit() }) {
            return error(Response.Status.BAD_REQUEST, "PIN_INVALID")
        }
        return when (val result = PolicyModeCoordinator.unlockFromWeb(pin)) {
            PolicyModeCoordinator.WebUnlockResult.Ok -> {
                QuizAccess.onSensitiveActionResult(success = true)
                json(
                    Response.Status.OK,
                    JSONObject().put("ok", true).put("lock", lockJson(currentLock())),
                )
            }
            is PolicyModeCoordinator.WebUnlockResult.PinInvalid -> {
                if (result.locked) {
                    QuizAccess.lockSensitiveActionOut()
                } else {
                    QuizAccess.onSensitiveActionResult(success = false)
                }
                val retrySeconds = QuizAccess.sensitiveActionLockedSeconds()
                if (retrySeconds > 0) {
                    json(
                        Response.Status.TOO_MANY_REQUESTS,
                        JSONObject()
                            .put("ok", false)
                            .put("error", "PIN_LOCKED")
                            .put("retryAfterSeconds", retrySeconds)
                            .put("lock", lockJson(currentLock())),
                    )
                } else {
                    json(
                        Response.Status.UNAUTHORIZED,
                        JSONObject()
                            .put("ok", false)
                            .put("error", "PIN_INVALID")
                            .put("lock", lockJson(currentLock())),
                    )
                }
            }
            is PolicyModeCoordinator.WebUnlockResult.Rejected -> json(
                Response.Status.CONFLICT,
                JSONObject()
                    .put("ok", false)
                    .put("error", result.reason.name)
                    .put("lock", lockJson(currentLock())),
            )
        }
    }

    /**
     * One lock-screen manual session from the parent page. This is not the temporary
     * unlock: it starts a timed session and therefore repeats the full lock-screen
     * eligibility check, requires the parent PIN for every start, and shares the
     * sensitive-action limiter with the unlock endpoint. The coordinator and runtime
     * still perform the final atomic checks; this handler only maps typed results.
     */
    private fun manualStart(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val currentStatus = { PolicyModeCoordinator.webManualStartStatus() }
        val wait = QuizAccess.sensitiveActionLockedSeconds()
        if (wait > 0) {
            return json(
                Response.Status.TOO_MANY_REQUESTS,
                JSONObject()
                    .put("ok", false)
                    .put("error", "PIN_LOCKED")
                    .put("retryAfterSeconds", wait)
                    .put("manualStart", manualStartJson(currentStatus())),
            )
        }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val pin = body.optString("pin", "")
        if (pin.length != 6 || pin.any { !it.isDigit() }) {
            return error(Response.Status.BAD_REQUEST, "PIN_INVALID")
        }
        val minutes = body.optInt("minutes", -1)
        if (!ManualSessionLimits.isValid(minutes)) {
            return error(Response.Status.BAD_REQUEST, "INVALID_DURATION")
        }
        return when (val result = PolicyModeCoordinator.startManualFromWeb(pin, minutes)) {
            PolicyModeCoordinator.WebManualStartResult.Ok -> {
                QuizAccess.onSensitiveActionResult(success = true)
                json(
                    Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put("manualStart", manualStartJson(currentStatus())),
                )
            }
            PolicyModeCoordinator.WebManualStartResult.InvalidDuration ->
                error(Response.Status.BAD_REQUEST, "INVALID_DURATION")
            is PolicyModeCoordinator.WebManualStartResult.PinInvalid -> {
                if (result.locked) {
                    QuizAccess.lockSensitiveActionOut()
                } else {
                    QuizAccess.onSensitiveActionResult(success = false)
                }
                val retrySeconds = QuizAccess.sensitiveActionLockedSeconds()
                if (retrySeconds > 0) {
                    json(
                        Response.Status.TOO_MANY_REQUESTS,
                        JSONObject()
                            .put("ok", false)
                            .put("error", "PIN_LOCKED")
                            .put("retryAfterSeconds", retrySeconds)
                            .put("manualStart", manualStartJson(currentStatus())),
                    )
                } else {
                    json(
                        Response.Status.UNAUTHORIZED,
                        JSONObject()
                            .put("ok", false)
                            .put("error", "PIN_INVALID")
                            .put("manualStart", manualStartJson(currentStatus())),
                    )
                }
            }
            is PolicyModeCoordinator.WebManualStartResult.Rejected -> json(
                Response.Status.CONFLICT,
                JSONObject()
                    .put("ok", false)
                    .put("error", result.reason.name)
                    .put("manualStart", manualStartJson(currentStatus())),
            )
        }
    }

    /**
     * Best-effort unload release. Unlike [logout] this keeps the remembered
     * token, so the same phone can resume without the PIN when nobody else
     * took the slot. If the browser never sends this request, the idle timeout
     * in [QuizAccess] has the same effect about ten seconds later.
     */
    private fun release(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        QuizAccess.release(adminToken(session))
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun readSession(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        // Entering the local settings page is a documented cloud->TV apply point.
        QuizConfigSync.refreshFromCloudAsync()
        val state = QuizRuntime.refresh()
        val current = state.session
        // This page is LOCAL-only, so the active QCD is the only configuration
        // source. An unreadable document yields the neutral default here and is
        // reported explicitly below; the old keys are never read back.
        val challengeConfig = state.defaultChallengeConfig
        val body = JSONObject()
            .put("ok", true)
            .put("enabled", state.enabled)
            .put("documentUnreadable", state.documentUnreadable)
        if (current == null) {
            body.put("session", JSONObject.NULL)
        } else if (current.status in ACTIVE_STATUSES) {
            body.put("session", summary(current))
        } else {
            body.put("session", JSONObject(QuizSessionCodec.encode(current)))
        }
        body.put("sessionActive", current?.status in ACTIVE_STATUSES)
        body.put("curriculum", QuizCurriculum.current()?.descriptor() ?: JSONObject.NULL)
        body.put("cloudJuniorReady", QuizCurriculum.CLOUD_JUNIOR_READY)
        body.put("consentAccepted", QuizSessionStore.consentAccepted())
        body.put("providerConfigured", QuizSessionStore.providerConfigured())
        body.put("challengeConfig", QuizSessionCodec.encodeConfig(challengeConfig))
        // Level packages are the only editable model. The legacy fields below
        // are a read-only projection for a page cached before V37; a save from
        // that page is rejected with LEVEL_MODEL_UPGRADED.
        val packages = state.levelPackages
        val projection = legacyLevelProjection(packages)
        body.put("levelModel", LEVEL_MODEL)
        body.put("levelPackagesUnreadable", state.levelPackagesUnreadable)
        body.put("levelPackages", levelPackagesJson(packages))
        body.put(
            "levelLimits",
            JSONObject()
                .put("maxLevels", QuizLevelPackage.MAX_LEVELS)
                .put("maxSources", QuizLevelPackage.MAX_SOURCES)
                .put("maxNameCodePoints", QuizLevelPackage.MAX_NAME_CODE_POINTS),
        )
        body.put("answerScope", projection.answerScope?.encode() ?: JSONObject.NULL)
        // Kept for a page cached before the level list existed; it only knows
        // one scope and reads the first level.
        body.put(
            "answerScopeMode",
            if (projection.answerScope == null) "bound" else "custom",
        )
        body.put("answerScopes", projection.answerScopes)
        val collections = QuizCollectionStore.collections()
        val countsByCollection = QuizQuestionCache.collectionCounts(QuizQuestionCache.LOCAL_SCOPE)
            .associateBy { it.collectionId }
        body.put("customLevels", projection.customLevels)
        body.put("wrongLevel", projection.wrongLevel ?: JSONObject.NULL)
        body.put(
            "collections",
            JSONArray().apply {
                collections.forEach { collection ->
                    val counts = countsByCollection[collection.id]?.counts
                    put(
                        JSONObject()
                            .put("id", collection.id)
                            .put("name", collection.name)
                            .put("total", counts?.total ?: 0)
                            .put(
                                "unused",
                                QuizQuestionCache.availableCollectionCount(collection.id),
                            ),
                    )
                }
            },
        )
        val sourceCounts = QuizQuestionCache.sourceCounts(QuizQuestionCache.LOCAL_SCOPE)
        body.put(
            "bankSourceCounts",
            JSONObject()
                .put("course", sourceCounts.course)
                .put("custom", sourceCounts.custom),
        )
        body.put(
            "answerScopeAvailable",
            QuizQuestionCache.availableCount(
                QuizQuestionCache.LOCAL_SCOPE,
                answerScope = projection.answerScope,
            ),
        )
        body.put("engineConfig", QuizEngineConfig.from(challengeConfig).let {
            JSONObject()
                .put("pullCount", it.pullCount)
                .put("defaultTimeLimitSec", it.defaultTimeLimitSec)
                .put("answerDisplaySec", it.answerDisplaySec)
                .put("minutesPerQuestion", it.minutesPerQuestion)
                .put("shuffleOptions", it.shuffleOptions)
        })
        body.put("sourceConfig", QuizSessionStore.loadSourceConfig().encode())
        body.put("source", state.source.name)
        body.put("localBankActive", state.localBankActive)
        body.put("bankState", state.bankState)
        body.put("bank", bankCountsJson(state.bankCounts))
        body.put("activeScopeAvailable", state.activeScopeAvailable)
        body.put("syncState", QuizSessionStore.loadSyncState().encode())
        body.put("cloudManaged", QuizConfigSync.isCloudManaged())
        body.put("cache", legacyCacheJson(state.bankCounts))
        body.put("bankCapacity", QuizQuestionCache.BANK_CAPACITY)
        return json(Response.Status.OK, body)
    }

    /**
     * Stock for candidate course scopes while the parent builds a package.
     * Counts stay separate from `packageAvailability` so an unsaved source can
     * show "mixed / single / multiple" before it joins the package.
     */
    private fun levelSourceCounts(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val array = body.optJSONArray("scopes")
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_SCOPE")
        if (array.length() > MAX_LEVEL_COUNT_SCOPES) {
            return error(Response.Status.BAD_REQUEST, "INVALID_SCOPE")
        }
        val now = System.currentTimeMillis()
        val counts = JSONArray()
        for (index in 0 until array.length()) {
            val scope = QuizAnswerScope.parse(array.optJSONObject(index))
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_SCOPE")
            val typeCounts = QuizQuestionCache.availableTypeCounts(
                nowMs = now,
                answerScope = scope,
            )
            counts.put(
                JSONObject()
                    .put("total", typeCounts.total)
                    .put("single", typeCounts.single)
                    .put("multiple", typeCounts.multiple),
            )
        }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("counts", counts)
                .put("wrong", QuizQuestionCache.wrongReviewCount()),
        )
    }

    /**
     * Display-only unit titles for the phone page. The endpoint stays separate
     * from the session so a missing catalogue cannot break editing; callers
     * fall back to the stage unit limit and the numeric label.
     */
    private fun readUnitCatalog(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("catalog", QuizUnitCatalog.current()?.descriptor() ?: JSONObject.NULL),
        )
    }

    /**
     * Compatibility path for a page cached before V17. A publish no longer
     * creates a READY session; its items are appended to the local bank, and
     * the TV consumes them only when the child starts a round.
     */
    private fun putSession(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val publish = body.optBoolean("publish", true)
        val sessionObject = body.optJSONObject("session") ?: return error(Response.Status.BAD_REQUEST, "INVALID_SESSION")
        val parsed = when (val decoded = QuizSessionCodec.decode(sessionObject.toString())) {
            is QuizDecodeResult.Success -> decoded.session
            is QuizDecodeResult.Failure -> return invalidSession(decoded.errors)
        }
        val now = System.currentTimeMillis()
        val errors = QuizSessionValidation.validateForPublish(parsed, now).toMutableList()
        if (publish && parsed.status != QuizSessionStatus.READY) errors += "session.status: must be READY when publish=true"
        if (!publish && parsed.status != QuizSessionStatus.DRAFT) errors += "session.status: must be DRAFT when publish=false"
        if (parsed.config.minCorrectCount > parsed.config.pullCount) {
            errors += "config.minCorrectCount: must not exceed pullCount"
        }
        if (errors.isNotEmpty()) return invalidSession(errors)

        if (publish && !QuizSessionStore.saveChallengeConfig(parsed.config)) {
            return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
        if (publish) QuizConfigSync.afterLocalEdit()
        val append = if (publish) {
            QuizQuestionCache.appendLocalQuestions(parsed.id, parsed.items, now)
        } else {
            QuizQuestionCache.AppendResult()
        }
        if (publish && append.appended == 0 && append.capacityReached) {
            return error(Response.Status.CONFLICT, "BANK_FULL")
        }
        if (publish && append.appended == 0 && append.duplicates == 0 && append.rejected > 0) {
            return error(Response.Status.INTERNAL_ERROR, "CACHE_UNAVAILABLE")
        }
        val state = QuizRuntime.refresh()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("sessionState", if (publish) "bank" else "draft")
                .put("questionCount", parsed.items.size)
                .put("appended", append.appended)
                .put("duplicates", append.duplicates)
                .put("rejected", append.rejected)
                .put("capacityReached", append.capacityReached)
                .put("counts", bankCountsJson(state.bankCounts))
                .put("expiresAt", parsed.expiresAt)
                .put("syncState", QuizSessionStore.loadSyncState().encode())
                .put("cloudManaged", QuizConfigSync.isCloudManaged()),
        )
    }

    private fun readBank(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val filter = session.parameters["filter"]?.firstOrNull().orEmpty()
            .takeIf { it in BANK_FILTERS } ?: "all"
        val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull()
            ?.coerceIn(0, 100_000) ?: 0
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull()
            ?.coerceIn(1, 100) ?: 50
        val source = when (session.parameters["source"]?.firstOrNull()?.trim()?.lowercase()) {
            null, "", "all" -> null
            "course", "course_ai", "ai" -> QuizQuestionCache.BankSource.COURSE_AI
            "custom" -> QuizQuestionCache.BankSource.CUSTOM
            else -> return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        val collectionId = session.parameters["collectionId"]?.firstOrNull()
            ?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val rows = QuizQuestionCache.listBank(
            QuizQuestionCache.LOCAL_SCOPE,
            filter,
            offset,
            limit,
            now,
            source = source,
            collectionId = collectionId,
        )
        val tagNames = QuizCollectionStore.collections().associate { it.id to it.name }
        val items = JSONArray()
        rows.forEach { row ->
            val question = row.question
            items.put(
                JSONObject()
                    .put("cacheId", row.cacheId)
                    .put("questionId", question.questionId)
                    .put("collectionId", row.collectionId ?: JSONObject.NULL)
                    .put(
                        "collectionName",
                        row.collectionId?.let { tagNames[it] } ?: JSONObject.NULL,
                    )
                    .put("prompt", question.prompt)
                    .put("options", JSONArray(question.options))
                    .put("type", question.answerKind.wire)
                    .put("presentation", question.presentation.wire)
                    .put("answerIndex", question.answerIndex)
                    .put("answerText", question.options.getOrNull(question.answerIndex).orEmpty())
                    .put("answerIndices", JSONArray(question.correctIndices))
                    .put(
                        "answerTexts",
                        JSONArray(
                            question.correctIndices.mapNotNull { question.options.getOrNull(it) },
                        ),
                    )
                    .put("explanation", question.explanation)
                    .put("subject", question.subject)
                    .put("grade", question.grade)
                    .put("difficulty", question.difficulty)
                    .put("scopeGrade", question.scopeGrade ?: JSONObject.NULL)
                    .put("scopeSemester", question.scopeSemester ?: JSONObject.NULL)
                    .put("scopeSubject", question.scopeSubject)
                    .put("scopeTextbook", question.scopeTextbook)
                    .put("scopeType", question.scopeType)
                    .put("scopeUnitNo", question.scopeUnitNo ?: JSONObject.NULL)
                    .put("points", question.points)
                    .put("timeLimitSec", question.timeLimitSec)
                    .put("status", row.status(now))
                    .put("wrongCount", row.wrongCount)
                    .put("selectedIndex", row.selectedIndex ?: JSONObject.NULL)
                    .put("selectedText", row.selectedIndex?.let { question.options.getOrNull(it) } ?: JSONObject.NULL)
                    .put("selectedIndices", JSONArray(row.selectedIndices))
                    .put(
                        "selectedTexts",
                        JSONArray(
                            row.selectedIndices.mapNotNull { question.options.getOrNull(it) },
                        ),
                    )
                    .put("timedOut", row.timedOut)
                    .put("answeredAt", row.answeredAt ?: 0L)
                    .put("receivedAt", row.receivedAt),
            )
        }
        val counts = QuizQuestionCache.bankCounts(QuizQuestionCache.LOCAL_SCOPE, now)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("filter", filter)
                .put("offset", offset)
                .put("limit", limit)
                .put("items", items)
                .put("counts", bankCountsJson(counts))
                .put(
                    "sourceCounts",
                    QuizQuestionCache.sourceCounts(QuizQuestionCache.LOCAL_SCOPE).let {
                        JSONObject().put("course", it.course).put("custom", it.custom)
                    },
                )
                .put("capacity", QuizQuestionCache.BANK_CAPACITY),
        )
    }

    /**
     * Faceted picker for the custom answer range. Only rows with a complete
     * six-field scope are counted; legacy rows are reported separately as
     * `noScope` instead of silently widening (or emptying) a custom range.
     *
     * Counts for each dimension apply every other active filter, so choosing
     * one field narrows the remaining options like a normal faceted search.
     */
    private fun readBankFacets(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        fun param(name: String): String? =
            session.parameters[name]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

        val grade = param("grade")?.let {
            it.toIntOrNull()?.takeIf { value -> QuizAnswerScope.isSupportedGrade(value) }
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        val semester = param("semester")?.let {
            it.toIntOrNull()?.takeIf { value -> value in 1..2 }
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        val subject = param("subject")?.let {
            val normalized = it.uppercase()
            if (!QuizAnswerScope.isSupportedSubject(normalized)) {
                return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
            }
            normalized
        }
        val textbook = param("textbook")?.let {
            val normalized = it.uppercase()
            if (!QuizAnswerScope.isSupportedTextbook(normalized)) {
                return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
            }
            normalized
        }
        val unitMax = unitMaxForFacets(grade)
        val topic = param("topic")?.let { raw ->
            val normalized = raw.uppercase()
            when {
                normalized.startsWith("UNIT:") -> {
                    val unit = normalized.removePrefix("UNIT:").toIntOrNull()
                        ?.takeIf { unitMax > 0 && it in 1..unitMax }
                        ?: return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
                    "UNIT:$unit"
                }
                QuizAnswerScope.isSupportedScopeType(normalized) &&
                    normalized != "UNIT" -> normalized
                else -> return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
            }
        }
        val difficulty = param("difficulty")?.let {
            val normalized = it.uppercase()
            if (!QuizAnswerScope.isSupportedDifficulty(normalized)) {
                return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
            }
            normalized.ifEmpty { null }
        }
        // "any" means no type filter. The type chips themselves are driven by
        // the answerKind facet, which ignores this filter so every backed type
        // stays selectable while the hierarchy narrows to the chosen type.
        val answerKind = param("answerKind")?.let { raw ->
            if (raw.equals("any", ignoreCase = true)) return@let null
            QuizAnswerKind.fromWire(raw)?.wire
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        val filter = FacetFilters(
            grade, semester, subject, textbook, topic, difficulty, answerKind,
        )
        val now = System.currentTimeMillis()
        val snapshot = QuizQuestionCache.facetSnapshot(QuizQuestionCache.LOCAL_SCOPE, now)
        val scopedRows = snapshot.groups.mapNotNull(::facetRow)
        val scoped = scopedRows.sumOf { it.count }
        val matched = scopedRows.filter { it.matches(filter) }.sumOf { it.count }
        val withoutDifficulty = scopedRows
            .filter { it.matches(filter, skip = "difficulty") }
            .sumOf { it.count }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("total", snapshot.total)
                .put("scoped", scoped)
                .put("noScope", snapshot.total - scoped)
                .put("matched", matched)
                .put("unrestricted", withoutDifficulty)
                .put("filters", filter.toJson())
                .put(
                    "facets",
                    JSONObject()
                        .put("grade", facetCounts(scopedRows, filter, "grade") {
                            it.item.scopeGrade?.toString()
                        })
                        .put("semester", facetCounts(scopedRows, filter, "semester") {
                            it.item.scopeSemester?.toString()
                        })
                        .put("subject", facetCounts(scopedRows, filter, "subject") {
                            it.item.scopeSubject.uppercase()
                        })
                        .put("textbook", facetCounts(scopedRows, filter, "textbook") {
                            it.item.scopeTextbook.uppercase()
                        })
                        .put("topic", facetCounts(scopedRows, filter, "topic") { it.topic })
                        .put("difficulty", facetCounts(scopedRows, filter, "difficulty") {
                            it.item.difficulty.trim().uppercase()
                                .takeIf { value -> value in FACET_DIFFICULTIES }
                        })
                        // No-op when the viewer is already filtering by type:
                        // matches() skips this dimension for its own facet.
                        .put("answerKind", facetCounts(scopedRows, filter, "answerKind") {
                            it.item.answerKind.wire
                        }),
                ),
        )
    }

    private data class FacetFilters(
        val grade: Int?,
        val semester: Int?,
        val subject: String?,
        val textbook: String?,
        val topic: String?,
        val difficulty: String?,
        val answerKind: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("grade", grade ?: JSONObject.NULL)
            .put("semester", semester ?: JSONObject.NULL)
            .put("subject", subject ?: JSONObject.NULL)
            .put("textbook", textbook ?: JSONObject.NULL)
            .put("topic", topic ?: JSONObject.NULL)
            .put("difficulty", difficulty ?: JSONObject.NULL)
            .put("answerKind", answerKind ?: JSONObject.NULL)
    }

    private data class FacetRow(val item: QuizItem, val topic: String, val count: Int) {
        /** [skip] excludes one dimension from the match, for its own facet counts. */
        fun matches(filter: FacetFilters, skip: String? = null): Boolean {
            if (skip != "grade" && filter.grade != null && item.scopeGrade != filter.grade) return false
            if (skip != "semester" && filter.semester != null &&
                item.scopeSemester != filter.semester
            ) {
                return false
            }
            if (skip != "subject" && filter.subject != null &&
                !filter.subject.equals(QuizAnswerScope.SUBJECT_ALL, ignoreCase = true) &&
                !item.scopeSubject.equals(filter.subject, ignoreCase = true)
            ) {
                return false
            }
            if (skip != "textbook" && filter.textbook != null &&
                !filter.textbook.equals(QuizAnswerScope.TEXTBOOK_ALL, ignoreCase = true) &&
                !item.scopeTextbook.equals(filter.textbook, ignoreCase = true)
            ) {
                return false
            }
            if (skip != "topic" && filter.topic != null && topic != filter.topic) return false
            if (skip != "difficulty" && filter.difficulty != null &&
                !item.difficulty.equals(filter.difficulty, ignoreCase = true)
            ) {
                return false
            }
            if (skip != "answerKind" && filter.answerKind != null &&
                !item.answerKind.wire.equals(filter.answerKind, ignoreCase = true)
            ) {
                return false
            }
            return true
        }
    }

    private fun facetRow(group: QuizQuestionCache.FacetGroup): FacetRow? =
        facetRow(
            QuizItem(
                questionId = "",
                prompt = "",
                options = emptyList(),
                answerIndex = 0,
                difficulty = group.difficulty,
                scopeGrade = group.grade,
                scopeSemester = group.semester,
                scopeSubject = group.subject,
                scopeTextbook = group.textbook,
                scopeType = group.scopeType,
                scopeUnitNo = group.unitNo,
                // Pre-v7 rows have no answer_kind column value until the
                // backfill runs; they decode as single-choice everywhere else.
                answerKind = QuizAnswerKind.fromWire(group.answerKind)
                    ?: QuizAnswerKind.SINGLE,
            ),
            group.count,
        )

    private fun facetRow(item: QuizItem, count: Int = 1): FacetRow? {
        val grade = item.scopeGrade ?: return null
        val semester = item.scopeSemester ?: return null
        val curriculum = QuizCurriculum.current() ?: return null
        if (!curriculum.isGradeSupported(grade) || semester !in 1..2) return null
        if (isCloudJuniorBlocked(grade)) return null
        if (!curriculum.isSubjectSupported(grade, item.scopeSubject)) return null
        if (!item.scopeTextbook.equals(QuizAnswerScope.TEXTBOOK_ALL, ignoreCase = true) &&
            !curriculum.isTextbookSupported(grade, item.scopeSubject, item.scopeTextbook)
        ) {
            return null
        }
        val type = item.scopeType.trim().uppercase()
        if (!QuizAnswerScope.isSupportedScopeType(type)) return null
        val topic = if (type == "UNIT") {
            val unit = item.scopeUnitNo ?: return null
            val unitMax = QuizUnitCatalog.current()
                ?.unitMax(grade, semester, item.scopeSubject, item.scopeTextbook)
                ?: curriculum.unitMax(grade)
                ?: return null
            if (unit !in 1..unitMax) return null
            "UNIT:$unit"
        } else {
            if (item.scopeUnitNo != null) return null
            type
        }
        return FacetRow(item, topic, count)
    }

    /** Facet queries may omit subject/textbook, so use the widest available limit. */
    private fun unitMaxForFacets(grade: Int?): Int {
        val curriculumMax = if (grade == null) {
            QuizCurriculum.current()?.maxUnit()
        } else {
            QuizCurriculum.current()?.unitMax(grade)
        } ?: 0
        val catalogMax = if (grade == null) {
            QuizUnitCatalog.current()?.maxUnit()
        } else {
            QuizUnitCatalog.current()?.unitMaxForGrade(grade)
        } ?: 0
        return maxOf(curriculumMax, catalogMax)
    }

    private fun facetCounts(
        rows: List<FacetRow>,
        filter: FacetFilters,
        skip: String,
        key: (FacetRow) -> String?,
    ): JSONObject {
        val counts = sortedMapOf<String, Int>()
        rows.asSequence()
            .filter { it.matches(filter, skip) }
            .forEach { row ->
                key(row)?.let { counts[it] = (counts[it] ?: 0) + row.count }
            }
        return JSONObject().apply { counts.forEach { (value, count) -> put(value, count) } }
    }

    /**
     * Strict manual import into one tag. Existing tags append; a new tag is
     * created only for a real import and removed again when the write fails,
     * so a failed batch never leaves an empty tag behind. A dry run never
     * writes either store.
     */
    private fun importBank(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        // Read the stored session instead of running a full QuizRuntime.refresh():
        // a 50-batch import would otherwise recompute bank and level counts
        // before every batch just to answer "is a round running?".
        if (QuizSessionStore.loadSession()?.status in ACTIVE_STATUSES) {
            return error(Response.Status.CONFLICT, "SESSION_RUNNING")
        }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val batchMode = body.optBoolean("batchMode", false)
        val collectionId = body.optString("collectionId", "").trim()
        val newCollectionName = body.optString("newCollectionName", "").trim()
        if (collectionId.isEmpty() && newCollectionName.isEmpty()) {
            return error(Response.Status.BAD_REQUEST, "COLLECTION_REQUIRED")
        }
        if (collectionId.isNotEmpty() && newCollectionName.isNotEmpty()) {
            return error(Response.Status.BAD_REQUEST, "COLLECTION_CONFLICT")
        }
        val dryRun = body.optBoolean("dryRun", false)
        val defaultDifficulty = if (body.has("defaultDifficulty") && !body.isNull("defaultDifficulty")) {
            body.optString("defaultDifficulty", "")
        } else {
            null
        }
        val format = when (val raw = body.optString("format", "").trim().lowercase(Locale.ROOT)) {
            "" -> when {
                body.has("items") -> "json"
                body.has("text") -> "text"
                else -> return error(Response.Status.BAD_REQUEST, "INVALID_IMPORT")
            }
            "json", "text" -> raw
            else -> return error(Response.Status.BAD_REQUEST, "INVALID_IMPORT")
        }
        val parsed = if (format == "json") {
            val items = body.optJSONArray("items")
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_IMPORT")
            if (items.length() > QuizBankImport.MAX_ITEMS) {
                return error(Response.Status.BAD_REQUEST, "TOO_MANY_ITEMS")
            }
            QuizBankImport.parseJsonItems(items, defaultDifficulty)
        } else {
            val text = body.optString("text", "")
            if (text.isBlank()) return error(Response.Status.BAD_REQUEST, "INVALID_IMPORT")
            QuizBankImport.parseText(text, defaultDifficulty)
        }
        val items = when (parsed) {
            is QuizImportResult.Success -> parsed.items
            is QuizImportResult.Failure -> return importFailure(parsed.errors)
        }
        if (items.size > QuizBankImport.MAX_ITEMS) {
            return error(Response.Status.BAD_REQUEST, "TOO_MANY_ITEMS")
        }
        if (!QuizQuestionCache.isReady()) {
            return error(Response.Status.INTERNAL_ERROR, "CACHE_UNAVAILABLE")
        }

        val existing = if (collectionId.isNotEmpty()) QuizCollectionStore.find(collectionId) else null
        if (collectionId.isNotEmpty() && existing == null) {
            return error(Response.Status.NOT_FOUND, "COLLECTION_NOT_FOUND")
        }
        var created: QuizCollection? = null
        val collection = existing ?: run {
            val cleanName = QuizCollection.normalizeName(newCollectionName)
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_COLLECTION_NAME")
            if (QuizCollectionStore.nameExists(cleanName)) {
                return error(Response.Status.CONFLICT, "COLLECTION_EXISTS")
            }
            if (QuizCollectionStore.collections().size >= QuizCollection.MAX_COLLECTIONS) {
                return error(Response.Status.CONFLICT, "COLLECTION_LIMIT")
            }
            if (dryRun) {
                QuizCollection.create(cleanName)
                    ?: return error(Response.Status.BAD_REQUEST, "INVALID_COLLECTION_NAME")
            } else {
                QuizCollectionStore.addCollection(cleanName)?.also { created = it }
                    ?: return error(
                        Response.Status.CONFLICT,
                        if (QuizCollectionStore.nameExists(cleanName)) {
                            "COLLECTION_EXISTS"
                        } else {
                            "COLLECTION_LIMIT"
                        },
                    )
            }
        }

        if (dryRun) {
            val plan = QuizQuestionCache.previewLocalImport(collection.id, items)
            val canImport = plan.rejected == 0 &&
                plan.wouldAppend > 0 &&
                plan.wouldAppend <= plan.remaining
            return json(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("dryRun", true)
                    .put("collection", collectionJson(collection))
                    .put("total", plan.total)
                    .put("fileDuplicates", plan.fileDuplicates)
                    .put("collectionDuplicates", plan.collectionDuplicates)
                    .put("crossSourceDuplicates", plan.crossSourceDuplicates)
                    .put("rejected", plan.rejected)
                    .put("wouldAppend", plan.wouldAppend)
                    .put("capacity", QuizQuestionCache.BANK_CAPACITY)
                    .put("remaining", plan.remaining)
                    .put("canImport", canImport)
                    .put("preview", importPreviewJson(plan.preview)),
            )
        }

        val result = QuizQuestionCache.importLocalQuestions(
            collection.id,
            "import-" + UUID.randomUUID().toString().replace("-", "").take(16),
            items,
        )
        if (result.storageError || result.rejected > 0 || result.capacityReached) {
            created?.let { QuizCollectionStore.removeCollection(it.id) }
            return when {
                result.storageError ->
                    error(Response.Status.INTERNAL_ERROR, "CACHE_UNAVAILABLE")
                result.capacityReached -> json(
                    Response.Status.CONFLICT,
                    JSONObject()
                        .put("ok", false)
                        .put("error", "BANK_FULL")
                        .put("remaining", result.remaining)
                        .put("capacity", QuizQuestionCache.BANK_CAPACITY),
                )
                else -> error(Response.Status.BAD_REQUEST, "INVALID_IMPORT")
            }
        }
        val response = JSONObject()
            .put("ok", true)
            .put("dryRun", false)
            .put("collection", collectionJson(collection))
            .put("appended", result.appended)
            .put("fileDuplicates", result.fileDuplicates)
            .put("collectionDuplicates", result.collectionDuplicates)
            .put("crossSourceDuplicates", result.crossSourceDuplicates)
            .put("rejected", result.rejected)
            .put("capacity", QuizQuestionCache.BANK_CAPACITY)
            .put("remaining", result.remaining)
        // A batched import refreshes the whole status once at the end. Counting
        // every tag and source after each 200-row batch would multiply the
        // same work by the number of batches.
        if (!batchMode) {
            val state = QuizRuntime.refresh()
            val collectionCounts = QuizQuestionCache.collectionCounts(QuizQuestionCache.LOCAL_SCOPE)
                .firstOrNull { it.collectionId == collection.id }?.counts
                ?: QuizQuestionCache.BankCounts()
            val sourceCounts = QuizQuestionCache.sourceCounts(QuizQuestionCache.LOCAL_SCOPE)
            response
                .put("counts", bankCountsJson(state.bankCounts))
                .put(
                    "sourceCounts",
                    JSONObject()
                        .put("course", sourceCounts.course)
                        .put("custom", sourceCounts.custom),
                )
                .put(
                    "collectionCounts",
                    JSONObject()
                        .put("total", collectionCounts.total)
                        .put("unused", collectionCounts.unused),
                )
        }
        return json(Response.Status.OK, response)
    }

    private fun importFailure(errors: List<QuizImportError>): Response {
        val only = errors.singleOrNull()
        if (only != null && only.code == "TOO_MANY_ITEMS") {
            return error(Response.Status.BAD_REQUEST, "TOO_MANY_ITEMS")
        }
        return json(
            Response.Status.BAD_REQUEST,
            JSONObject()
                .put("ok", false)
                .put("error", "INVALID_IMPORT")
                .put(
                    "errors",
                    JSONArray().apply {
                        errors.take(QuizBankImport.MAX_ERRORS).forEach { error ->
                            put(
                                JSONObject()
                                    .put("index", error.index)
                                    .put("field", error.field)
                                    .put("code", error.code),
                            )
                        }
                    },
                ),
        )
    }

    private fun collectionJson(collection: QuizCollection): JSONObject =
        JSONObject().put("id", collection.id).put("name", collection.name)

    private fun importPreviewJson(items: List<QuizItem>): JSONArray = JSONArray().apply {
        items.take(20).forEachIndexed { index, item ->
            put(
                JSONObject()
                    .put("index", index)
                    .put("prompt", item.prompt)
                    .put("options", JSONArray(item.options))
                    .put("type", item.answerKind.wire)
                    .put("answerIndex", item.answerIndex)
                    .put("answerIndices", JSONArray(item.correctIndices))
                    .put(
                        "answerTexts",
                        JSONArray(item.correctIndices.mapNotNull { item.options.getOrNull(it) }),
                    )
                    .put("explanation", item.explanation)
                    .put("difficulty", item.difficulty),
            )
        }
    }

    private fun clearBank(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        if (QuizRuntime.refresh().session?.status in ACTIVE_STATUSES) {
            return error(Response.Status.CONFLICT, "SESSION_RUNNING")
        }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val mode = when (body.optString("mode", "unused")) {
            "unused" -> QuizQuestionCache.ClearMode.UNUSED
            "answered" -> QuizQuestionCache.ClearMode.ANSWERED
            "correct" -> QuizQuestionCache.ClearMode.CORRECT
            "wrong" -> QuizQuestionCache.ClearMode.WRONG
            "all" -> QuizQuestionCache.ClearMode.ALL
            else -> return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        // The default must stay the AI/seed namespace so a cached page or a
        // habitual cleanup can never delete imported tag rows.
        val source = when (body.optString("source", "course").trim().lowercase(Locale.ROOT)) {
            "course", "course_ai", "ai" -> QuizQuestionCache.BankSource.COURSE_AI
            "custom" -> QuizQuestionCache.BankSource.CUSTOM
            "all" -> QuizQuestionCache.BankSource.ALL
            else -> return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        }
        val collectionId = body.optString("collectionId", "").trim().takeIf { it.isNotEmpty() }
        if (!QuizQuestionCache.clearBank(
                QuizQuestionCache.LOCAL_SCOPE,
                mode,
                source = source,
                collectionId = collectionId,
            )
        ) {
            return error(Response.Status.INTERNAL_ERROR, "CACHE_UNAVAILABLE")
        }
        val state = QuizRuntime.refresh()
        val sourceCounts = QuizQuestionCache.sourceCounts(QuizQuestionCache.LOCAL_SCOPE)
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("counts", bankCountsJson(state.bankCounts))
                .put(
                    "sourceCounts",
                    JSONObject()
                        .put("course", sourceCounts.course)
                        .put("custom", sourceCounts.custom),
                )
                .put("bankState", state.bankState),
        )
    }

    private fun readCache(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        return json(Response.Status.OK, cacheJson())
    }

    private fun clearCache(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        if (QuizRuntime.refresh().session?.status in ACTIVE_STATUSES) {
            return error(Response.Status.CONFLICT, "SESSION_RUNNING")
        }
        if (!QuizQuestionCache.clearBank(QuizQuestionCache.LOCAL_SCOPE, QuizQuestionCache.ClearMode.UNUSED)) {
            return error(Response.Status.INTERNAL_ERROR, "CACHE_UNAVAILABLE")
        }
        return json(Response.Status.OK, cacheJson())
    }

    private fun rebuildCache(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        // V17 has no published session to rebuild from; the bank is the source
        // of truth. A cached old page gets an explicit answer instead of a
        // silent replacement of the local bank.
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("deprecated", true)
                .put("reason", "BANK_MODEL"),
        )
    }

    private fun cacheJson(): JSONObject {
        val state = QuizRuntime.refresh()
        val source = QuizSessionStore.loadSourceConfig()
        // LOCAL-only surface: project the active document, never the old key.
        val engine = QuizEngineConfig.from(state.defaultChallengeConfig)
        val counts = state.bankCounts
        return JSONObject()
            .put("ok", true)
            .put("ready", QuizQuestionCache.isReady())
            .put("enabled", state.enabled)
            .put("bankState", state.bankState)
            .put("localBankActive", state.localBankActive)
            .put("source", source.source.name)
            .put("sourceConfig", source.encode())
            .put("engineConfig", JSONObject()
                .put("pullCount", engine.pullCount)
                .put("defaultTimeLimitSec", engine.defaultTimeLimitSec)
                .put("answerDisplaySec", engine.answerDisplaySec)
                .put("minutesPerQuestion", engine.minutesPerQuestion)
                .put("shuffleOptions", engine.shuffleOptions))
            .put("counts", bankCountsJson(counts))
            .put("cache", legacyCacheJson(counts))
    }

    private fun saveChallengeConfig(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        // Challenge rules are editable even on a bound TV: they are uploaded by
        // afterLocalEdit and the backend normalizes the source to CLOUD.
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val configObject = body.optJSONObject("config")
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CONFIG")
        val config = QuizSessionCodec.decodeConfig(configObject)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_CONFIG")
        // Field ranges are enforced by decodeConfig. Cross-field combinations
        // (cap below one reward, threshold above cap) are intentional parent
        // choices and only produce soft warnings on the web page.
        // Level packages (V37) are the only writable level model: one package
        // can mix course, tag and wrong-review sources. A page cached before
        // V37 still posts the three old fields; reject it so it reloads instead
        // of silently writing a list that cannot represent mixed sources.
        // Rules-only saves omit every level field and leave packages untouched.
        val legacyLevelField = LEGACY_LEVEL_FIELDS.firstOrNull { body.has(it) }
        if (legacyLevelField != null) {
            return error(Response.Status.CONFLICT, "LEVEL_MODEL_UPGRADED", legacyLevelField)
        }
        val packages: List<QuizLevelPackage>? = when {
            !body.has("levelPackages") -> null
            // An explicit null clears every level, matching the old sections.
            body.isNull("levelPackages") -> emptyList()
            else -> when (val parsed = parseLevelPackages(body.optJSONArray("levelPackages"))) {
                is LevelPackagesResult.Ok -> parsed.packages
                is LevelPackagesResult.Invalid ->
                    return error(Response.Status.BAD_REQUEST, parsed.code, parsed.detail)
            }
        }
        if (packages != null && packages.any { levelPackage ->
                levelPackage.sources.any {
                    it is QuizPackageSource.Course && isCloudJuniorBlocked(it.scope.grade)
                }
            }
        ) {
            return error(Response.Status.CONFLICT, "CLOUD_JUNIOR_UNSUPPORTED")
        }
        // Phase 1 adapter: the old form edits the active QCD's default profile
        // and rules. Everything it cannot express survives the save.
        val current = when (val loaded = QuizConfigDocumentStore.ensureLocalDocument()) {
            is QuizDocLoadResult.Ok -> loaded.document
            is QuizDocLoadResult.Unreadable -> return error(
                Response.Status.CONFLICT,
                "DOCUMENT_UNREADABLE",
            )
            QuizDocLoadResult.Storage -> return error(
                Response.Status.INTERNAL_ERROR,
                "STORAGE_ERROR",
            )
        }
        // A cached page has no feedback object and no feedbackExtraHoldSec key,
        // so simply opening and saving it must not wipe a JSON-set value.
        val feedbackRequest = body.optJSONObject("feedback")
        val feedback = QuizLegacyConfigAdapter.FeedbackOverride(
            extraHoldSec = when {
                feedbackRequest?.has("extraHoldSec") == true ->
                    feedbackRequest.optInt("extraHoldSec")
                configObject.has("feedbackExtraHoldSec") -> config.feedbackExtraHoldSec
                else -> null
            },
            answerRevealSec = if (feedbackRequest?.has("answerRevealSec") == true) {
                feedbackRequest.optInt("answerRevealSec")
            } else {
                null
            },
            autoNext = if (feedbackRequest?.has("autoNext") == true) {
                feedbackRequest.optBoolean("autoNext")
            } else {
                null
            },
        )
        val updated = QuizLegacyConfigAdapter.apply(
            current = current,
            config = config,
            feedback = feedback,
            hasAnswerDisplaySec = configObject.has("answerDisplaySec"),
            // Packages now own the level list; the legacy QCD levels stay as
            // the last pre-V37 snapshot for a downgrade.
            levels = null,
        )
        val next = if (packages != null) {
            QuizLevelPackageStore.withPackages(updated, packages)
        } else {
            updated
        }
        // A package save does not touch the legacy scope store, so compare the
        // package-derived first course scope before and after this edit.
        val previousScopeSignature = if (packages != null) {
            firstCourseScope(storedPackages(current))?.signature()
        } else {
            null
        }
        // One write: rules, default profile and packages land in the same revision.
        val saved = when (val result = QuizConfigDocumentStore.saveLocal(next, current.revision)) {
            is QuizDocSaveResult.Ok -> result.document
            is QuizDocSaveResult.Conflict -> return error(
                Response.Status.CONFLICT,
                "REVISION_CONFLICT",
            )
            is QuizDocSaveResult.Invalid -> return invalidDocument("INVALID_DOCUMENT", result.issues)
            QuizDocSaveResult.Storage -> return error(
                Response.Status.INTERNAL_ERROR,
                "STORAGE_ERROR",
            )
        }
        QuizConfigSync.afterLocalEdit()
        val effectiveConfig = saved.toLegacyConfig()
            ?: return error(Response.Status.INTERNAL_ERROR, "DOCUMENT_PROJECTION_FAILED")
        val effectivePackages = if (packages != null) packages else storedPackages(saved)
        val projection = legacyLevelProjection(effectivePackages)
        if (previousScopeSignature != null &&
            previousScopeSignature != firstCourseScope(effectivePackages)?.signature()
        ) {
            QuizConfigSync.afterLocalScopeChange()
        }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("config", QuizSessionCodec.encodeConfig(effectiveConfig))
                .put("levelModel", LEVEL_MODEL)
                .put("levelPackages", levelPackagesJson(effectivePackages))
                .put("levelPackagesUnreadable", false)
                .put("answerScope", projection.answerScope?.encode() ?: JSONObject.NULL)
                .put(
                    "answerScopeMode",
                    if (projection.answerScope == null) "bound" else "custom",
                )
                .put("answerScopes", projection.answerScopes)
                .put("customLevels", projection.customLevels)
                .put("wrongLevel", projection.wrongLevel ?: JSONObject.NULL)
                .put(
                    "answerScopeAvailable",
                    QuizQuestionCache.availableCount(
                        QuizQuestionCache.LOCAL_SCOPE,
                        answerScope = projection.answerScope,
                    ),
                )
                .put("syncState", QuizSessionStore.loadSyncState().encode()),
        )
    }

    /**
     * The full QCD of this TV. [docState] explains why [document] is null: an
     * unreadable document is an explicit state, never an empty config.
     */
    private fun readConfigDocument(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        return when (val loaded = QuizConfigDocumentStore.ensureLocalDocument()) {
            is QuizDocLoadResult.Ok ->
                json(Response.Status.OK, documentJson(loaded.document).put("docState", "OK"))
            is QuizDocLoadResult.Unreadable -> {
                val migrationInvalid = loaded.errors.any { it.startsWith("legacyConfig:") }
                json(
                    Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put(
                            "docState",
                            if (migrationInvalid) "MIGRATION_INVALID" else "UNREADABLE",
                        )
                        .put("document", JSONObject.NULL)
                        .put("issues", JSONArray(loaded.errors)),
                )
            }
            QuizDocLoadResult.Storage -> error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
    }

    /**
     * Whole-document replace. The TV owns the revision counter: [baseRevision]
     * is only a compare-and-swap guard, and the stored revision always advances
     * by one. A running round keeps the snapshot it froze at start.
     */
    private fun saveConfigDocument(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val documentObject = body.optJSONObject("document")
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_DOCUMENT")
        val baseRevision = if (body.has("baseRevision") && !body.isNull("baseRevision")) {
            val raw = body.optLong("baseRevision", Long.MIN_VALUE)
            if (raw == Long.MIN_VALUE) {
                return error(Response.Status.BAD_REQUEST, "INVALID_DOCUMENT")
            }
            raw
        } else {
            null
        }
        val document = when (val decoded = QuizConfigDocumentCodec.decode(documentObject.toString())) {
            is QuizDocDecodeResult.Success -> decoded.document
            is QuizDocDecodeResult.Failure ->
                return invalidDocument("INVALID_DOCUMENT", decoded.issues)
        }
        val issues = QuizLocalConfigDocument.localWriteIssues(document)
        if (issues.isNotEmpty()) {
            return invalidDocument(
                if (issues.all { it.code in CAPABILITY_ISSUE_CODES }) "UNSUPPORTED" else "INVALID_DOCUMENT",
                issues,
            )
        }
        return when (val saved = QuizConfigDocumentStore.saveLocal(document, baseRevision)) {
            is QuizDocSaveResult.Ok -> {
                QuizConfigSync.afterLocalEdit()
                QuizRuntime.refresh()
                json(Response.Status.OK, documentJson(saved.document).put("docState", "OK"))
            }
            is QuizDocSaveResult.Conflict -> json(
                Response.Status.CONFLICT,
                JSONObject()
                    .put("ok", false)
                    .put("error", "REVISION_CONFLICT")
                    .put("revision", saved.current?.revision ?: 0L)
                    .put("etag", saved.current?.etag ?: "")
                    .put(
                        "document",
                        saved.current?.let { QuizConfigDocumentCodec.encodeDocument(it) }
                            ?: JSONObject.NULL,
                    ),
            )
            is QuizDocSaveResult.Invalid -> invalidDocument(
                if (saved.issues.all { it.code in CAPABILITY_ISSUE_CODES }) {
                    "UNSUPPORTED"
                } else {
                    "INVALID_DOCUMENT"
                },
                saved.issues,
            )
            QuizDocSaveResult.Storage -> error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
    }

    private fun documentJson(document: QuizConfigDocument): JSONObject = JSONObject()
        .put("ok", true)
        .put("document", QuizConfigDocumentCodec.encodeDocument(document))
        .put("revision", document.revision)
        .put("etag", document.etag)
        .put("levelsEtag", document.levelsEtag)
        .put("origin", document.origin.name)

    private fun invalidDocument(errorCode: String, issues: List<QuizDocIssue>): Response = json(
        Response.Status.BAD_REQUEST,
        JSONObject()
            .put("ok", false)
            .put("error", errorCode)
            .put("issues", JSONArray().apply {
                issues.forEach {
                    put(JSONObject().put("field", it.field).put("code", it.code))
                }
            }),
    )

    private fun deleteSession(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val state = QuizRuntime.refresh()
        val current = state.session
        if (current?.status in ACTIVE_STATUSES) return error(Response.Status.CONFLICT, "SESSION_RUNNING")
        if (current != null) {
            if (QuizChallenge.isCloudSession(current)) {
                CloudQuizPreparer.discardCloudState(PolicyModeCoordinator.state.value.deviceNo)
            } else {
                val scope = QuizRecovery.scopeFor(current, PolicyModeCoordinator.state.value.deviceNo)
                if (scope != null) {
                    QuizQuestionCache.settleRound(
                        current.id,
                        scope,
                        QuizRecovery.answerWrites(current),
                    )
                }
                QuizQuestionCache.release(current.id)
                if (!QuizSessionStore.clearSession()) {
                    return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
                }
            }
        } else if (QuizSessionStore.hasStoredSession()) {
            // The parent asked to delete a record the current codec cannot
            // read; without this branch the setup screen could never be reset.
            QuizQuestionCache.releaseOrphans(null)
            if (!QuizSessionStore.quarantineUnreadableSession()) {
                return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
            }
        }
        QuizRuntime.refresh()
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun syncStatus(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        QuizConfigSync.refreshFromCloudAsync()
        return json(
            Response.Status.OK,
            JSONObject()
                .put("ok", true)
                .put("syncState", QuizSessionStore.loadSyncState().encode())
                .put("cloudManaged", QuizConfigSync.isCloudManaged()),
        )
    }

    /** Explicit user retry for a pending upload or a failed remote refresh. */
    private fun syncRetry(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        QuizConfigSync.retryNow()
        QuizConfigSync.refreshFromCloudAsync(force = true)
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("syncState", QuizSessionStore.loadSyncState().encode()),
        )
    }

    /**
     * Conflict resolution chosen by the parent. This is the only path that discards an unsent
     * local draft; it never runs automatically.
     */
    private fun syncUseRemote(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        QuizConfigSync.useRemoteAfterConflictAsync()
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("syncState", QuizSessionStore.loadSyncState().encode()),
        )
    }

    private fun readProvider(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        return json(Response.Status.OK, providerJson())
    }

    private fun setConsent(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        if (body.optBoolean("accepted", false)) {
            QuizSessionStore.acceptConsent()
        } else {
            QuizSessionStore.revokeConsent()
        }
        return json(Response.Status.OK, providerJson())
    }

    private fun saveProvider(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        if (!QuizCrypto.isEncrypted) return error(Response.Status.CONFLICT, "AI_UNSUPPORTED")
        if (!QuizSessionStore.consentAccepted()) return error(Response.Status.CONFLICT, "CONSENT_REQUIRED")
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val current = QuizSessionStore.loadProviderMeta()
        val base = QuizProviderMeta.normalizeBaseUrl(
            body.optString("baseUrl", current.baseUrl),
        ) ?: return error(Response.Status.BAD_REQUEST, "PROVIDER_CONFIG")
        val model = QuizProviderMeta.normalizeModel(
            body.optString("model", current.model),
        ) ?: return error(Response.Status.BAD_REQUEST, "PROVIDER_CONFIG")
        val supplied = body.optString("apiKey", "").trim()
        val key = if (supplied.isEmpty()) QuizSessionStore.providerKey() else normalizeKey(supplied)
        if (key == null) {
            return error(
                Response.Status.BAD_REQUEST,
                if (supplied.isEmpty()) "PROVIDER_KEY_REQUIRED" else "PROVIDER_KEY_INVALID",
            )
        }
        val speech = body.optJSONObject("speech")?.let { raw ->
            parseSpeechSettings(raw, QuizSessionStore.loadSpeechSettings())
                ?: return error(Response.Status.BAD_REQUEST, "INVALID_SPEECH_SETTINGS")
        }
        if (speech != null && speech.enabled && !speech.hasContent) {
            return error(Response.Status.BAD_REQUEST, "SPEECH_CONTENT_REQUIRED")
        }
        if (!QuizSessionStore.saveProviderMeta(QuizProviderMeta(base, model))) {
            return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
        if (supplied.isNotEmpty() && !QuizSessionStore.saveProviderKey(key)) {
            return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
        if (speech != null && !QuizSessionStore.saveSpeechSettings(speech)) {
            return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
        return json(Response.Status.OK, providerJson())
    }

    private fun testProvider(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        if (!QuizCrypto.isEncrypted) return error(Response.Status.CONFLICT, "AI_UNSUPPORTED")
        if (!QuizSessionStore.consentAccepted()) return error(Response.Status.CONFLICT, "CONSENT_REQUIRED")
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val current = QuizSessionStore.loadProviderMeta()
        val base = QuizProviderMeta.normalizeBaseUrl(
            body.optString("baseUrl", current.baseUrl),
        ) ?: return error(Response.Status.BAD_REQUEST, "PROVIDER_CONFIG")
        val model = QuizProviderMeta.normalizeModel(
            body.optString("model", current.model),
        ) ?: return error(Response.Status.BAD_REQUEST, "PROVIDER_CONFIG")
        val supplied = body.optString("apiKey", "").trim()
        val key = if (supplied.isEmpty()) QuizSessionStore.providerKey() else normalizeKey(supplied)
        if (key == null) {
            return error(
                Response.Status.BAD_REQUEST,
                if (supplied.isEmpty()) "PROVIDER_KEY_REQUIRED" else "PROVIDER_KEY_INVALID",
            )
        }
        val failure = QuizAiClient.testConnection(QuizProviderMeta(base, model), key)
        if (failure != null) return error(Response.Status.BAD_REQUEST, failure)
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("baseUrl", base).put("model", model),
        )
    }

    private fun readGenerationPrefs(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val prefs = QuizSessionStore.loadGenerationPrefs()
        return json(
            Response.Status.OK,
            JSONObject().put("ok", true).put("prefs", prefs ?: JSONObject.NULL),
        )
    }

    private fun saveGenerationPrefs(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val prefs = QuizGeneration.parsePreferences(body)
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_REQUEST")
        val gradeNo = QuizCurriculum.current()?.gradeNoForCode(prefs.optString("grade", ""))
        if (isCloudJuniorBlocked(gradeNo)) {
            return error(Response.Status.CONFLICT, "CLOUD_JUNIOR_UNSUPPORTED")
        }
        if (!QuizSessionStore.saveGenerationPrefs(prefs)) {
            return error(Response.Status.INTERNAL_ERROR, "STORAGE_ERROR")
        }
        // Levels are a frozen parent-designed list now; changing the generation
        // settings no longer moves the answer scope.
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun startGeneration(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        if (!QuizCrypto.isEncrypted) return error(Response.Status.CONFLICT, "AI_UNSUPPORTED")
        if (!QuizSessionStore.consentAccepted()) return error(Response.Status.CONFLICT, "CONSENT_REQUIRED")
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val request = when (val parsed = QuizGeneration.parseRequest(body)) {
            is QuizGeneration.ParseResult.Ok -> parsed.request
            is QuizGeneration.ParseResult.Invalid -> return invalidRequest(parsed.fields)
        }
        if (isCloudJuniorBlocked(request.gradeNo)) {
            return generationFailure("CLOUD_JUNIOR_UNSUPPORTED")
        }
        return when (val started = QuizGeneration.start(adminToken(session), request)) {
            is QuizGeneration.StartResult.Started -> json(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("jobId", started.jobId)
                    .put("batchId", started.jobId)
                    .put("target", request.targetCount)
                    .put("expiresAt", started.expiresAt),
            )
            is QuizGeneration.StartResult.Failure -> generationFailure(started.code)
        }
    }

    private fun generationStatus(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val jobId = session.parameters["jobId"]?.firstOrNull().orEmpty()
        if (!JOB_ID_RE.matches(jobId)) return error(Response.Status.BAD_REQUEST, "INVALID_JOB")
        return when (val result = QuizGeneration.poll(adminToken(session), jobId)) {
            is QuizGeneration.PollResult.Running -> json(
                Response.Status.OK,
                generationJson("running", result.progress, jobId),
            )
            is QuizGeneration.PollResult.Ready -> json(
                Response.Status.OK,
                generationJson("ready", result.progress, jobId)
                    .put("batch", stagedBatchJson(result.batch)),
            )
            is QuizGeneration.PollResult.Failed -> json(
                Response.Status.OK,
                generationJson("failed", result.progress, jobId)
                    .put("error", result.errorCode)
                    .put("batch", stagedBatchJson(result.batch)),
            )
            QuizGeneration.PollResult.Lost -> error(Response.Status.NOT_FOUND, "JOB_LOST")
            QuizGeneration.PollResult.Expired -> error(Response.Status.CONFLICT, "JOB_EXPIRED")
        }
    }

    private fun cancelGeneration(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val jobId = body.optString("jobId", "")
        if (!JOB_ID_RE.matches(jobId)) return error(Response.Status.BAD_REQUEST, "INVALID_JOB")
        if (!QuizGeneration.cancel(adminToken(session), jobId)) {
            return error(Response.Status.NOT_FOUND, "JOB_LOST")
        }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    /** Commits only the rows the parent selected on the review page. */
    private fun addGeneration(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val jobId = body.optString("jobId", body.optString("batchId", ""))
        if (!JOB_ID_RE.matches(jobId)) return error(Response.Status.BAD_REQUEST, "INVALID_JOB")
        val array = body.optJSONArray("indexes")
            ?: return error(Response.Status.BAD_REQUEST, "INVALID_INDEX")
        if (array.length() !in 1..QuizQuestionCache.BANK_CAPACITY) {
            return error(Response.Status.BAD_REQUEST, "INVALID_INDEX")
        }
        val indexes = mutableListOf<Int>()
        for (i in 0 until array.length()) {
            val raw = array.opt(i)
            if (raw !is Number) return error(Response.Status.BAD_REQUEST, "INVALID_INDEX")
            val index = raw.toInt()
            if (raw.toDouble() != index.toDouble() || index < 0) {
                return error(Response.Status.BAD_REQUEST, "INVALID_INDEX")
            }
            indexes += index
        }
        return when (val result = QuizGeneration.add(adminToken(session), jobId, indexes)) {
            is QuizGeneration.CommitResult.Ok -> {
                val state = QuizRuntime.refresh()
                json(
                    Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put("appended", result.appended)
                        .put("duplicates", result.duplicates)
                        .put("rejected", result.rejected)
                        .put("capacityReached", result.capacityReached)
                        .put("counts", bankCountsJson(state.bankCounts))
                        .put("bankState", state.bankState),
                )
            }
            QuizGeneration.CommitResult.Lost -> error(Response.Status.NOT_FOUND, "JOB_LOST")
            QuizGeneration.CommitResult.NotReady -> error(Response.Status.CONFLICT, "JOB_RUNNING")
            is QuizGeneration.CommitResult.BadIndexes ->
                error(Response.Status.BAD_REQUEST, "INVALID_INDEX")
        }
    }

    private fun discardGeneration(session: IHTTPSession): Response {
        adminError(session)?.let { return it }
        val body = jsonBody(session) ?: return error(Response.Status.BAD_REQUEST, "INVALID_JSON")
        val jobId = body.optString("jobId", body.optString("batchId", ""))
        if (!JOB_ID_RE.matches(jobId)) return error(Response.Status.BAD_REQUEST, "INVALID_JOB")
        if (!QuizGeneration.discard(adminToken(session), jobId)) {
            return error(Response.Status.NOT_FOUND, "JOB_LOST")
        }
        return json(Response.Status.OK, JSONObject().put("ok", true))
    }

    private fun normalizeKey(value: String): String? {
        val key = value.trim()
        // Providers use different key shapes (sk-..., id.secret, APIKey:APISecret, JWT),
        // so do not impose a format. Only reject values that cannot be sent safely in an
        // HTTP Authorization header: empty, oversized, or containing control characters.
        if (key.isEmpty() || key.length > MAX_PROVIDER_KEY_LENGTH) return null
        if (key.any { it.isISOControl() }) return null
        return key
    }

    /**
     * Strict boolean parsing for the speech settings. A malformed field must not
     * silently become a default-on value that starts uploading quiz text.
     */
    private fun parseSpeechSettings(raw: JSONObject, current: QuizSpeechSettings): QuizSpeechSettings? {
        fun value(key: String, fallback: Boolean): Boolean? = when {
            !raw.has(key) || raw.isNull(key) -> fallback
            raw.opt(key) is Boolean -> raw.getBoolean(key)
            else -> null
        }
        val enabled = value("enabled", current.enabled) ?: return null
        val prompt = value("readPrompt", current.readPrompt) ?: return null
        val options = value("readOptions", current.readOptions) ?: return null
        val answer = value("readAnswer", current.readAnswer) ?: return null
        return QuizSpeechSettings(
            initialized = true,
            enabled = enabled,
            readPrompt = prompt,
            readOptions = options,
            readAnswer = answer,
        )
    }

    private fun providerJson(): JSONObject {
        val meta = QuizSessionStore.loadProviderMeta()
        return JSONObject()
            .put("ok", true)
            .put("aiSupported", QuizCrypto.isEncrypted)
            .put("consentAccepted", QuizSessionStore.consentAccepted())
            .put("configured", QuizSessionStore.providerConfigured())
            .put("baseUrl", meta.baseUrl)
            .put("model", meta.model)
            .put("speech", QuizSessionStore.loadSpeechSettings().toJson())
    }

    private fun generationJson(
        state: String,
        progress: QuizGeneration.Progress,
        jobId: String,
    ): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("state", state)
            .put("jobId", jobId)
            .put("batchId", jobId)
            .put("target", progress.target)
            .put("generated", progress.generated)
            .put("unique", progress.unique)
            .put("duplicates", progress.duplicates)
            .put("rejected", progress.rejected)
            .put("remaining", progress.remaining)
            .put("batchIndex", progress.batchIndex)
            .put("warnings", JSONArray(progress.warnings))
            .put("counts", bankCountsJson(progress.counts))
            .put("expiresAt", progress.expiresAt)

    private fun stagedBatchJson(batch: List<QuizGeneration.StagedItem>): JSONArray =
        JSONArray().apply {
            batch.forEachIndexed { index, staged ->
                val question = staged.item
                put(
                    JSONObject()
                        .put("index", index)
                        .put("duplicate", staged.duplicate)
                        .put("questionId", question.questionId)
                        .put("prompt", question.prompt)
                        .put("options", JSONArray(question.options))
                        .put("type", question.answerKind.wire)
                        .put("answerIndex", question.answerIndex)
                        .put("answerText", question.options.getOrNull(question.answerIndex).orEmpty())
                        .put("answerIndices", JSONArray(question.correctIndices))
                        .put(
                            "answerTexts",
                            JSONArray(
                                question.correctIndices.mapNotNull { question.options.getOrNull(it) },
                            ),
                        )
                        .put("explanation", question.explanation)
                        .put("subject", question.subject)
                        .put("grade", question.grade)
                        .put("difficulty", question.difficulty)
                        .put("scopeGrade", question.scopeGrade ?: JSONObject.NULL)
                        .put("scopeSemester", question.scopeSemester ?: JSONObject.NULL)
                        .put("scopeSubject", question.scopeSubject)
                        .put("scopeTextbook", question.scopeTextbook)
                        .put("scopeType", question.scopeType)
                        .put("scopeUnitNo", question.scopeUnitNo ?: JSONObject.NULL)
                        .put("points", question.points)
                        .put("timeLimitSec", question.timeLimitSec),
                )
            }
        }

    private fun bankCountsJson(counts: QuizQuestionCache.BankCounts): JSONObject =
        JSONObject()
            .put("total", counts.total)
            .put("unused", counts.unused)
            .put("inRound", counts.inRound)
            .put("answered", counts.answered)
            .put("correct", counts.correct)
            .put("wrong", counts.wrong)
            .put("legacy", counts.legacy)
            .put("expired", counts.expired)

    /**
     * The editable level packages plus per-source stock. The phone list warns
     * before a child picks a package the bank cannot fill, and the source
     * picker shows the single/multiple split before a parent adds a course.
     */
    private fun levelPackagesJson(packages: List<QuizLevelPackage>): JSONArray {
        val now = System.currentTimeMillis()
        return JSONArray().apply {
            packages.forEach { levelPackage ->
                val availability = QuizQuestionCache.packageAvailability(levelPackage, now)
                put(
                    JSONObject()
                        .put("id", levelPackage.id)
                        .put("name", levelPackage.name)
                        .put("enabled", levelPackage.enabled)
                        .put("available", availability.available)
                        .put(
                            "sources",
                            JSONArray().apply {
                                levelPackage.sources.forEachIndexed { index, source ->
                                    put(
                                        packageSourceJson(
                                            source,
                                            availability.sourceCounts.getOrElse(index) { 0 },
                                        ),
                                    )
                                }
                            },
                        ),
                )
            }
        }
    }

    private fun packageSourceJson(source: QuizPackageSource, available: Int): JSONObject =
        when (source) {
            is QuizPackageSource.Course -> {
                val typeCounts = QuizQuestionCache.availableTypeCounts(
                    answerScope = source.scope,
                )
                JSONObject()
                    .put("type", "course")
                    .put("scope", source.scope.encode())
                    .put("answerKind", source.answerKind.wire)
                    .put("available", available)
                    .put(
                        "typeCounts",
                        JSONObject()
                            .put("total", typeCounts.total)
                            .put("single", typeCounts.single)
                            .put("multiple", typeCounts.multiple),
                    )
            }
            is QuizPackageSource.Collection -> JSONObject()
                .put("type", "collection")
                .put("collectionId", source.collectionId)
                .put("collectionName", QuizCollectionStore.tagName(source.collectionId))
                .put("available", available)
            QuizPackageSource.WrongReview -> JSONObject()
                .put("type", "wrong")
                .put("available", available)
        }

    private sealed interface LevelPackagesResult {
        data class Ok(val packages: List<QuizLevelPackage>) : LevelPackagesResult
        data class Invalid(val code: String, val detail: String? = null) : LevelPackagesResult
    }

    /**
     * Strict package parse with the plan's differentiated error codes. A
     * missing id is assigned by [QuizLevelPackage.parse]; any other malformed
     * row rejects the whole save instead of dropping part of a parent's list.
     */
    private fun parseLevelPackages(raw: JSONArray?): LevelPackagesResult {
        if (raw == null) return LevelPackagesResult.Invalid("INVALID_LEVEL_PACKAGES")
        if (raw.length() > QuizLevelPackage.MAX_LEVELS) {
            return LevelPackagesResult.Invalid("TOO_MANY_LEVELS")
        }
        val knownTags = QuizCollectionStore.collections().mapTo(HashSet()) { it.id }
        val packages = ArrayList<QuizLevelPackage>(raw.length())
        val ids = HashSet<String>(raw.length())
        for (index in 0 until raw.length()) {
            val obj = raw.optJSONObject(index)
                ?: return LevelPackagesResult.Invalid("INVALID_LEVEL_PACKAGES", "index:$index")
            val sourceArray = obj.optJSONArray("sources")
                ?: return LevelPackagesResult.Invalid("INVALID_LEVEL_PACKAGES", "sources:$index")
            if (sourceArray.length() == 0) {
                return LevelPackagesResult.Invalid("INVALID_LEVEL_SOURCE", "EMPTY")
            }
            if (sourceArray.length() > QuizLevelPackage.MAX_SOURCES) {
                return LevelPackagesResult.Invalid("LEVEL_SOURCE_LIMIT")
            }
            val sources = ArrayList<QuizPackageSource>(sourceArray.length())
            for (sourceIndex in 0 until sourceArray.length()) {
                val source = QuizLevelPackage.parseSource(sourceArray.optJSONObject(sourceIndex))
                    ?: return LevelPackagesResult.Invalid("INVALID_LEVEL_SOURCE", "MALFORMED")
                if (source is QuizPackageSource.Collection && source.collectionId !in knownTags) {
                    return LevelPackagesResult.Invalid(
                        "INVALID_LEVEL_SOURCE",
                        "UNKNOWN_COLLECTION",
                    )
                }
                sources += source
            }
            when (val issue = QuizLevelPackage.sourceIssue(sources)) {
                null -> Unit
                SourceIssue.TOO_MANY ->
                    return LevelPackagesResult.Invalid("LEVEL_SOURCE_LIMIT")
                SourceIssue.EMPTY ->
                    return LevelPackagesResult.Invalid("INVALID_LEVEL_SOURCE", "EMPTY")
                else ->
                    return LevelPackagesResult.Invalid("INVALID_LEVEL_SOURCE", issue.name)
            }
            val levelPackage = QuizLevelPackage.parse(obj)
                ?: return LevelPackagesResult.Invalid("INVALID_LEVEL_PACKAGES", "level:$index")
            if (!ids.add(levelPackage.id)) {
                return LevelPackagesResult.Invalid("INVALID_LEVEL_PACKAGES", "DUPLICATE_ID")
            }
            packages += levelPackage
        }
        return LevelPackagesResult.Ok(packages)
    }

    /**
     * Packages currently owned by [document]. An absent extension is migrated
     * in memory for comparisons; this never writes, so a rules-only save stays
     * a single revision.
     */
    private fun storedPackages(document: QuizConfigDocument): List<QuizLevelPackage> =
        when (val extension = QuizLevelPackageStore.read(document)) {
            is QuizLevelPackageStore.Extension.Present -> extension.packages
            QuizLevelPackageStore.Extension.Absent -> QuizLevelPackageStore.migrate(
                courseLevels = document.localLevels().orEmpty(),
                customLevels = QuizCollectionStore.customLevels(),
                wrongLevel = QuizWrongReviewLevelStore.level(),
            )
            is QuizLevelPackageStore.Extension.Broken -> emptyList()
        }

    private data class LegacyLevelProjection(
        val answerScope: QuizAnswerScope?,
        val answerScopes: JSONArray,
        val customLevels: JSONArray,
        val wrongLevel: JSONObject?,
    )

    /**
     * Projects packages into the three old read-only shapes. A mixed package
     * has no exact legacy equivalent, so it only contributes its first course
     * scope to the single-scope fields. Saving through those old shapes is
     * rejected; this keeps an old page readable without letting it edit.
     */
    private fun legacyLevelProjection(
        packages: List<QuizLevelPackage>,
    ): LegacyLevelProjection {
        val answerScopes = JSONArray()
        val customLevels = JSONArray()
        var wrongLevel: JSONObject? = null
        var answerScope: QuizAnswerScope? = null
        packages.forEach { levelPackage ->
            if (answerScope == null) {
                answerScope = firstCourseIn(levelPackage.sources)
            }
            when (val only = levelPackage.sources.singleOrNull()) {
                is QuizPackageSource.Course -> answerScopes.put(
                    JSONObject()
                        .put("id", levelPackage.id)
                        .put("name", levelPackage.name)
                        .put("scope", only.scope.encode())
                        .put(
                            "available",
                            QuizQuestionCache.availableCount(
                                QuizQuestionCache.LOCAL_SCOPE,
                                answerScope = only.scope,
                                answerKind = only.answerKind,
                            ),
                        ),
                )
                is QuizPackageSource.Collection -> customLevels.put(
                    JSONObject()
                        .put("id", levelPackage.id)
                        .put("name", levelPackage.name)
                        .put("collectionId", only.collectionId)
                        .put("collectionName", QuizCollectionStore.tagName(only.collectionId))
                        .put(
                            "available",
                            QuizQuestionCache.availableCollectionCount(only.collectionId),
                        ),
                )
                QuizPackageSource.WrongReview -> if (wrongLevel == null) {
                    wrongLevel = JSONObject()
                        .put("id", levelPackage.id)
                        .put("name", levelPackage.name)
                        .put("available", QuizQuestionCache.wrongReviewCount())
                }
                null -> Unit
            }
        }
        return LegacyLevelProjection(answerScope, answerScopes, customLevels, wrongLevel)
    }

    private fun firstCourseIn(sources: List<QuizPackageSource>): QuizAnswerScope? =
        sources.firstNotNullOfOrNull { (it as? QuizPackageSource.Course)?.scope }

    private fun firstCourseScope(packages: List<QuizLevelPackage>): QuizAnswerScope? =
        packages.firstNotNullOfOrNull { firstCourseIn(it.sources) }

    /** The pre-V17 `cache` shape, kept so a cached page still renders counts. */
    private fun legacyCacheJson(counts: QuizQuestionCache.BankCounts): JSONObject =
        JSONObject()
            .put("available", counts.unused)
            .put("reserved", counts.inRound)
            .put("consumed", counts.answered)
            .put("expired", counts.expired)

    private fun isCloudJuniorBlocked(gradeNo: Int?): Boolean =
        gradeNo != null && !QuizCurriculum.CLOUD_JUNIOR_READY &&
            QuizCurriculum.current()?.stageFor(gradeNo)?.id == "junior" &&
            QuizConfigSync.isCloudManaged()

    private fun generationFailure(code: String): Response = when (code) {
        "CONSENT_REQUIRED", "PROVIDER_NOT_CONFIGURED", "BUSY", "BANK_FULL",
        "CACHE_UNAVAILABLE", "CLOUD_JUNIOR_UNSUPPORTED" ->
            error(Response.Status.CONFLICT, code)
        else -> error(Response.Status.BAD_REQUEST, code)
    }

    private fun invalidRequest(fields: List<String>): Response = json(
        Response.Status.BAD_REQUEST,
        JSONObject()
            .put("ok", false)
            .put("error", "INVALID_REQUEST")
            .put("fields", JSONArray(fields.take(8).map { it.take(80) })),
    )

    private fun adminError(session: IHTTPSession): Response? {
        if (session.headers["x-quiz-admin"] != "1") return error(Response.Status.FORBIDDEN, "ADMIN_REQUIRED")
        if (session.headers["content-type"]?.startsWith("application/json") != true &&
            session.method != Method.GET
        ) {
            return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "CONTENT_TYPE")
        }
        val token = adminToken(session)
        if (!QuizAccess.adminValid(token)) return error(Response.Status.FORBIDDEN, "ADMIN_EXPIRED")
        return null
    }

    private fun sameOrigin(session: IHTTPSession): Boolean {
        val host = session.headers["host"] ?: return false
        val base = "http://$host"
        val origin = session.headers["origin"]
        val referer = session.headers["referer"]
        if (origin != null && origin != base) return false
        if (referer != null && !referer.startsWith("$base/")) return false
        return true
    }

    private fun jsonRequest(session: IHTTPSession): Boolean =
        session.headers["content-type"]?.startsWith("application/json") == true

    @Suppress("UNUSED_PARAMETER")
    private fun jsonBody(session: IHTTPSession): JSONObject? = requestBody.get()

    private fun readJsonBody(session: IHTTPSession, limit: Int = MAX_JSON_BYTES): JSONObject? {
        // NanoHTTPD 2.3.1 only exposes parseBody() results for POST, so a PUT
        // body would disappear. Read exactly Content-Length bytes for every
        // method instead; a missing length is rejected rather than blocking on
        // a keep-alive stream.
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (declared <= 0 || declared > limit) return null
        return runCatching {
            val bytes = ByteArray(declared.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = session.inputStream.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                offset += read
            }
            if (offset == 0) null else JSONObject(String(bytes, 0, offset, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun adminToken(session: IHTTPSession): String =
        session.headers["x-quiz-admin-token"].orEmpty()

    private fun summary(session: QuizSession): JSONObject = JSONObject()
        .put("id", session.id)
        .put("status", session.status.name)
        .put("questionCount", session.items.size)
        .put("expiresAt", session.expiresAt)

    private fun invalidSession(errors: List<String>): Response {
        val clean = errors.take(8).map { it.take(120) }
        return json(
            Response.Status.BAD_REQUEST,
            JSONObject()
                .put("ok", false)
                .put("error", "INVALID_SESSION")
                .put("fields", org.json.JSONArray(clean)),
        )
    }

    private fun asset(context: Context, name: String, type: String): Response {
        val body = context.assets.open(name).bufferedReader().use { it.readText() }
        return newResponse(Response.Status.OK, type, body)
    }

    private fun lockJson(status: PolicyModeCoordinator.WebUnlockStatus): JSONObject =
        JSONObject()
            .put("eligible", status.eligible)
            .put("reason", status.reason.name)
            .put("unlocked", status.unlocked)

    private fun manualStartJson(status: PolicyModeCoordinator.WebManualStartStatus): JSONObject =
        JSONObject()
            .put("eligible", status.eligible)
            .put("reason", status.reason.name)
            .put("minMinutes", ManualSessionLimits.MIN_MINUTES)
            .put("maxMinutes", ManualSessionLimits.MAX_MINUTES)
            .put("stepMinutes", ManualSessionLimits.STEP_MINUTES)
            .put("defaultMinutes", ManualSessionLimits.DEFAULT_MINUTES)
            .put("restRemainingSeconds", status.restRemainingSeconds)
            .put("remainingSeconds", status.remainingSeconds)

    private fun json(status: Response.Status, body: JSONObject): Response =
        newResponse(status, "application/json; charset=utf-8", body.toString())

    private fun error(status: Response.Status, code: String, detail: String? = null): Response =
        json(
            status,
            JSONObject().put("ok", false).put("error", code).apply {
                if (detail != null) put("detail", detail)
            },
        )

    private fun newResponse(status: Response.Status, type: String, body: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, type, body).apply {
            addHeader("Cache-Control", "no-store")
        }

    /** States where the session is owned by the TV and must not be edited remotely. */
    private val ACTIVE_STATUSES = setOf(
        QuizSessionStatus.RUNNING,
        QuizSessionStatus.SETTLING,
        QuizSessionStatus.STARTING,
        QuizSessionStatus.START_FAILED,
    )
    private val JOB_ID_RE = Regex("job_[0-9a-f]{20}")
    /** The pre-V37 level editors; any of these means the page must reload. */
    private val LEGACY_LEVEL_FIELDS = listOf(
        "answerScopes",
        "scopeMode",
        "scopeProvided",
        "scope",
        "customLevels",
        "wrongLevel",
    )
    private val BANK_FILTERS = setOf("all", "unused", "answered", "wrong", "correct")
    private val FACET_DIFFICULTIES = setOf("EASY", "MEDIUM", "HARD")
}
