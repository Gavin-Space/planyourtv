package com.planyourtv.policy

import android.util.Log
import java.time.LocalDate
import java.util.UUID

enum class RuntimeStateSource { V2, LEGACY, SAFE }

data class RuntimeStateLoad(
    val snapshot: RuntimeSnapshot,
    val source: RuntimeStateSource,
)

enum class GuardStateSource { V2, SAFE }

data class GuardStateLoad(
    val snapshot: GuardSnapshot,
    val source: GuardStateSource,
)

/**
 * The only read/write entry for durable execution state. Configuration stays in
 * [LocalPolicyStore]; the usage-upload outbox stays in [RuleUsageStore]. They share the same
 * [TvStore] (one MMKV id), but each has its own owner and lifecycle.
 *
 * A runtime write is a single-key atomic JSON snapshot. High-frequency checkpoints use an
 * ordinary encode in MMKV mode; transitions, settlement and the SharedPreferences fallback use
 * [TvStore.putStringDurable], which commits/syncs before returning.
 */
object PolicyStateRepository {
    private const val TAG = "PolicyStateRepository"

    fun loadRuntimeOrMigrate(): RuntimeStateLoad {
        val raw = if (TvStore.isReady) TvStore.getString(PolicyKeys.LOCAL_RUNTIME_V2, null) else null
        when (val decoded = PolicyStateCodec.decodeRuntime(raw)) {
            is PolicySnapshotDecode.Success ->
                return RuntimeStateLoad(decoded.value, RuntimeStateSource.V2)

            is PolicySnapshotDecode.Unsupported -> {
                // A downgraded APK must not reinterpret a newer schema. The safe state is an
                // empty runtime; the old keys remain untouched for a future upgrade.
                Log.w(TAG, "unsupported runtime schema ${decoded.version}; using safe state")
                return RuntimeStateLoad(RuntimeSnapshot(), RuntimeStateSource.SAFE)
            }

            is PolicySnapshotDecode.Corrupt -> {
                if (raw != null) {
                    Log.w(TAG, "corrupt runtime snapshot (${decoded.reason}); trying legacy keys")
                }
            }
        }
        return RuntimeStateLoad(legacyRuntimeSnapshot(), RuntimeStateSource.LEGACY)
    }

    fun loadGuard(): GuardStateLoad {
        val raw = if (TvStore.isReady) TvStore.getString(PolicyKeys.LOCAL_GUARD_V2, null) else null
        return when (val decoded = PolicyStateCodec.decodeGuard(raw)) {
            is PolicySnapshotDecode.Success -> GuardStateLoad(decoded.value, GuardStateSource.V2)

            is PolicySnapshotDecode.Unsupported -> {
                Log.w(TAG, "unsupported guard schema ${decoded.version}; locking safely")
                GuardStateLoad(GuardSnapshot(), GuardStateSource.SAFE)
            }

            is PolicySnapshotDecode.Corrupt -> {
                if (raw != null) Log.w(TAG, "corrupt guard snapshot (${decoded.reason})")
                GuardStateLoad(GuardSnapshot(), GuardStateSource.SAFE)
            }
        }
    }

    fun saveRuntime(
        snapshot: RuntimeSnapshot,
        durable: Boolean,
        forceSync: Boolean = false,
    ): Boolean {
        if (!TvStore.isReady) return false
        val raw = PolicyStateCodec.encodeRuntime(snapshot)
        val written = if (durable || TvStore.backend != "mmkv") {
            TvStore.putStringDurable(PolicyKeys.LOCAL_RUNTIME_V2, raw)
        } else {
            TvStore.putString(PolicyKeys.LOCAL_RUNTIME_V2, raw)
            true
        }
        if (written && forceSync) TvStore.sync()
        return written
    }

    fun saveGuard(snapshot: GuardSnapshot): Boolean {
        if (!TvStore.isReady) return false
        return TvStore.putStringDurable(
            PolicyKeys.LOCAL_GUARD_V2,
            PolicyStateCodec.encodeGuard(snapshot),
        )
    }

    fun clearAll() {
        if (!TvStore.isReady) return
        TvStore.removeDurable(PolicyKeys.LOCAL_RUNTIME_V2)
        TvStore.removeDurable(PolicyKeys.LOCAL_GUARD_V2)
    }

    /**
     * Maps the pre-V37 scattered keys into the new model. This is read-only; the caller commits
     * the normalized snapshot once it has applied expiry/recovery rules. The old keys are kept
     * for rollback.
     *
     * The old parent-override boolean is intentionally not mapped: it carries no boot or screen
     * evidence, so restoring it as an unlock would be unsafe. The runtime locks instead.
     */
    fun legacyRuntimeSnapshot(): RuntimeSnapshot {
        if (!TvStore.isReady) return RuntimeSnapshot()
        val today = LocalDate.now().toString()
        val saved = LocalPolicyStore.loadSession()
        val session = saved?.let { legacySession(it) }
        val used = LocalPolicyStore.usedSeconds(today)
        return RuntimeSnapshot(
            session = session,
            ledger = RuntimeLedger(
                days = if (used > 0L) mapOf(today to used) else emptyMap(),
                breakUntilEpochMs = LocalPolicyStore.breakUntil(),
                lastEndedAtEpochMs = LocalPolicyStore.lastSessionEndedAt(),
            ),
        )
    }

    private fun legacySession(saved: SavedLocalSession): SessionSnapshot? {
        if (saved.totalSeconds <= 0L || saved.remainingSeconds < 0L) return null
        val sessionId = saved.sessionId.ifBlank {
            "rule-" + UUID.randomUUID().toString().replace("-", "").take(24)
        }
        val paused = saved.pausedAtEpochMs > 0L
        return SessionSnapshot(
            sessionId = sessionId,
            phase = if (paused) SessionPhase.PAUSED else SessionPhase.ACTIVE,
            totalSeconds = saved.totalSeconds,
            remainingSeconds = saved.remainingSeconds,
            endReason = saved.endReason,
            usageDate = saved.usageDate,
            startedAtEpochMs = saved.startedAtEpochMs,
            planDeadlineEpochMs = saved.deadlineEpochMs,
            wallDeadlineEpochMs = saved.wallDeadlineEpochMs,
            pausedAtEpochMs = saved.pausedAtEpochMs,
            // The old format stored no checkpoint clock. A blank boot and zero checkpoint make
            // the recovery policy refuse to charge dead time, which is the safe default.
            activation = saved.activation,
            accessMode = saved.accessMode,
            allowedPackages = saved.allowedPackages,
            origin = if (saved.startedLocally) PolicySessionOrigin.LOCAL else null,
            reportUsage = saved.reportUsage,
            templateVersion = saved.templateVersion,
            restAfterSeconds = saved.restAfterSeconds,
        )
    }

}
