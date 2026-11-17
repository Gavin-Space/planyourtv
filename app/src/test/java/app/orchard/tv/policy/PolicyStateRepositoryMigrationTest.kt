package app.orchard.tv.policy

import android.app.Application
import com.planyourtv.policy.AccessMode
import com.planyourtv.policy.LocalPolicyStore
import com.planyourtv.policy.LocalSessionEnd
import com.planyourtv.policy.PolicyActivation
import com.planyourtv.policy.PolicySessionOrigin
import com.planyourtv.policy.PolicyStateRepository
import com.planyourtv.policy.RuntimeStateSource
import com.planyourtv.policy.SavedLocalSession
import com.planyourtv.policy.TvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * V37 reads the scattered pre-V37 keys exactly once and then treats the v2 snapshot as the only
 * authority. A corrupt or newer v2 document must never be silently reinterpreted.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyStateRepositoryMigrationTest {
    private val videoPackage = "com.example.video"

    @Before
    fun setUp() {
        val app: Application = RuntimeEnvironment.getApplication()
        assertTrue(TvStore.init(app))
        LocalPolicyRuntime.debugResetForTest()
    }

    private fun legacySession() = SavedLocalSession(
        totalSeconds = 600L,
        remainingSeconds = 420L,
        endReason = LocalSessionEnd.SESSION_LIMIT,
        templateVersion = 7L,
        restAfterSeconds = 300L,
        startedAtEpochMs = 1_700_000_000_000L,
        usageDate = LocalDate.now().toString(),
        deadlineEpochMs = 1_700_003_600_000L,
        wallDeadlineEpochMs = 1_700_004_800_000L,
        reportUsage = true,
        startedLocally = true,
        activation = PolicyActivation.MANUAL,
        accessMode = AccessMode.APPS,
        allowedPackages = setOf(videoPackage),
    )

    @Test
    fun aLegacySessionBecomesAUsableRuntimeSnapshot() {
        LocalPolicyStore.saveSession(legacySession())
        LocalPolicyStore.saveUsed(LocalDate.now().toString(), 180L)
        LocalPolicyStore.saveBreakUntil(1_700_005_000_000L)
        LocalPolicyStore.saveLastSessionEndedAt(1_700_004_900_000L)

        val load = PolicyStateRepository.loadRuntimeOrMigrate()

        assertEquals(RuntimeStateSource.LEGACY, load.source)
        val session = load.snapshot.session
        assertNotNull(session)
        // The old format had no session id; the migration synthesizes one so settlement stays
        // idempotent, and leaves the checkpoint clocks empty so no dead gap is charged.
        assertTrue(session!!.sessionId.isNotBlank())
        assertEquals(420L, session.remainingSeconds)
        assertEquals(0L, session.checkpointElapsedMs)
        assertEquals(PolicySessionOrigin.LOCAL, session.origin)
        assertEquals(180L, load.snapshot.ledger.days[LocalDate.now().toString()])
        assertEquals(1_700_005_000_000L, load.snapshot.ledger.breakUntilEpochMs)
        assertEquals(1_700_004_900_000L, load.snapshot.ledger.lastEndedAtEpochMs)
    }

    @Test
    fun savingTheMigratedSnapshotMakesV2Authoritative() {
        LocalPolicyStore.saveSession(legacySession())
        val migrated = PolicyStateRepository.loadRuntimeOrMigrate()
        assertEquals(RuntimeStateSource.LEGACY, migrated.source)

        assertTrue(
            PolicyStateRepository.saveRuntime(migrated.snapshot, durable = true, forceSync = true),
        )

        val reloaded = PolicyStateRepository.loadRuntimeOrMigrate()
        assertEquals(RuntimeStateSource.V2, reloaded.source)
        assertEquals(migrated.snapshot, reloaded.snapshot)
    }

    @Test
    fun aCorruptV2DocumentFallsBackToTheLegacyKeys() {
        LocalPolicyStore.saveSession(legacySession())
        TvStore.putStringDurable("local_runtime_v2", "{not-json")

        val load = PolicyStateRepository.loadRuntimeOrMigrate()

        assertEquals(RuntimeStateSource.LEGACY, load.source)
        assertEquals(420L, load.snapshot.session?.remainingSeconds)
    }

    @Test
    fun aNewerSchemaIsNotInterpretedAsV2() {
        LocalPolicyStore.saveSession(legacySession())
        TvStore.putStringDurable("local_runtime_v2", "{\"v\":99,\"crc\":0,\"payload\":\"{}\"}")

        val load = PolicyStateRepository.loadRuntimeOrMigrate()

        assertEquals(RuntimeStateSource.SAFE, load.source)
        assertNull(load.snapshot.session)
        assertTrue(load.snapshot.ledger.days.isEmpty())
    }

    @Test
    fun aCorruptGuardSnapshotLocksInsteadOfRestoring() {
        TvStore.putStringDurable("local_guard_v2", "{not-json")

        val load = PolicyStateRepository.loadGuard()

        assertEquals(com.planyourtv.policy.GuardStateSource.SAFE, load.source)
        assertEquals(com.planyourtv.policy.GuardStatus.ENFORCING, load.snapshot.phase)
    }
}
