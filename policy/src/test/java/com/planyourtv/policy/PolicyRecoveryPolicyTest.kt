package com.planyourtv.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyRecoveryPolicyTest {
    private val boot = BootEvidence("boot-a", 10_000L)
    private val now = RecoveryNow(
        wallMs = 1_700_000_100_000L,
        elapsedMs = 200_000L,
        asleepMs = 20_000L,
        boot = boot,
    )
    private val noMarker = ScreenOffEvidence()

    private fun lease(
        untilWallMs: Long = 0L,
        hardCapWallMs: Long = now.wallMs + 60_000L,
        hardCapElapsedMs: Long = now.elapsedMs + 60_000L,
        startedElapsedMs: Long = 100_000L,
        startedAsleepMs: Long = 5_000L,
        bootEvidence: BootEvidence = boot,
    ) = OverrideLease(
        leaseId = "ovr",
        startedWallMs = now.wallMs - 100_000L,
        startedElapsedMs = startedElapsedMs,
        startedAsleepMs = startedAsleepMs,
        untilWallMs = untilWallMs,
        hardCapWallMs = hardCapWallMs,
        hardCapElapsedMs = hardCapElapsedMs,
        boot = bootEvidence,
    )

    private fun studyLease() = StudyLease(
        startedWallMs = now.wallMs - 100_000L,
        startedElapsedMs = 100_000L,
        startedAsleepMs = 5_000L,
        hardCapWallMs = now.wallMs + 60_000L,
        hardCapElapsedMs = now.elapsedMs + 60_000L,
        boot = boot,
    )

    @Test
    fun overrideRestoresOnlyWithSameBootScreenOnAndNoEvidenceOfSleep() {
        assertTrue(PolicyRecoveryPolicy.overrideRestorable(lease(), now, true, noMarker))
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(lease(), now, false, noMarker))

        val otherBoot = now.copy(boot = BootEvidence("boot-b", 10_000L))
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(lease(), otherBoot, true, noMarker))

        val slept = now.copy(asleepMs = now.asleepMs + PolicyRecoveryPolicy.ASLEEP_SLACK_MS + 1L)
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(lease(), slept, true, noMarker))
    }

    @Test
    fun overrideEndsAfterDeadlineOrHardCap() {
        val timed = lease(untilWallMs = now.wallMs - 1L)
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(timed, now, true, noMarker))

        val wallCap = lease(hardCapWallMs = now.wallMs)
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(wallCap, now, true, noMarker))

        val elapsedCap = lease(hardCapElapsedMs = now.elapsedMs)
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(elapsedCap, now, true, noMarker))
    }

    @Test
    fun indefiniteOverrideStillRestoresWhenTheWallClockIsUnset() {
        val wallUnset = now.copy(wallMs = 0L)
        assertTrue(
            PolicyRecoveryPolicy.overrideRestorable(
                lease(hardCapWallMs = 0L),
                wallUnset,
                true,
                noMarker,
            ),
        )
    }

    @Test
    fun aScreenOffAfterTheUnlockEndsTheOverride() {
        val marker = ScreenOffEvidence(elapsedMs = 150_000L, wallMs = now.wallMs, boot = boot)
        assertFalse(PolicyRecoveryPolicy.overrideRestorable(lease(), now, true, marker))

        val before = marker.copy(elapsedMs = 90_000L)
        assertTrue(PolicyRecoveryPolicy.overrideRestorable(lease(), now, true, before))
    }

    @Test
    fun studyModeFollowsTheSameEvidenceRules() {
        assertTrue(PolicyRecoveryPolicy.studyRestorable(studyLease(), now, true, noMarker))
        assertFalse(PolicyRecoveryPolicy.studyRestorable(studyLease(), now, false, noMarker))
        assertFalse(
            PolicyRecoveryPolicy.studyRestorable(
                studyLease().copy(boot = BootEvidence("boot-b", 10_000L)),
                now,
                true,
                noMarker,
            ),
        )
        val marker = ScreenOffEvidence(elapsedMs = 150_000L, wallMs = now.wallMs, boot = boot)
        assertFalse(PolicyRecoveryPolicy.studyRestorable(studyLease(), now, true, marker))
    }

    @Test
    fun deadGapChargesOnlyTheProvableAwakeInterval() {
        assertEquals(
            50L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = boot,
                now = now,
                screenInteractive = true,
                screenOff = noMarker,
            ),
        )

        assertEquals(
            0L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = boot,
                now = now,
                screenInteractive = false,
                screenOff = noMarker,
            ),
        )

        assertEquals(
            0L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs - PolicyRecoveryPolicy.ASLEEP_SLACK_MS - 1L,
                boot = boot,
                now = now,
                screenInteractive = true,
                screenOff = noMarker,
            ),
        )

        assertEquals(
            0L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = BootEvidence("boot-b", 10_000L),
                now = now,
                screenInteractive = true,
                screenOff = noMarker,
            ),
        )
    }

    @Test
    fun deadGapRefusesClockDriftAndScreenOffEvidence() {
        assertEquals(
            0L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 10_000_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = boot,
                now = now,
                screenInteractive = true,
                screenOff = noMarker,
            ),
        )

        val marker = ScreenOffEvidence(elapsedMs = 175_000L, wallMs = now.wallMs, boot = boot)
        assertEquals(
            0L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 600L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = boot,
                now = now,
                screenInteractive = true,
                screenOff = marker,
            ),
        )
    }

    @Test
    fun deadGapNeverExceedsTheBudget() {
        assertEquals(
            20L,
            PolicyRecoveryPolicy.deadGapSeconds(
                remainingSeconds = 20L,
                checkpointElapsedMs = 150_000L,
                checkpointWallMs = now.wallMs - 50_000L,
                checkpointAsleepMs = now.asleepMs,
                boot = boot,
                now = now,
                screenInteractive = true,
                screenOff = noMarker,
            ),
        )
    }
}
