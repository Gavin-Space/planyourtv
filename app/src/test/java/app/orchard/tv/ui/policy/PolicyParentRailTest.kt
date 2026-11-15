package app.orchard.tv.ui.policy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * V29 collapses the TV rail to the child-mode switch and remote management. Local configuration
 * lives on the phone, so password, auto-update, quiz-setup and web-pair rows no longer exist.
 */
class PolicyParentRailTest {
    @Test
    fun childModeOffShowsOnlyTheSwitch() {
        assertEquals(
            listOf(ParentFocusTarget.Switch),
            parentRailTargets(childOn = false, remoteVisible = false),
        )
        assertEquals(
            listOf(ParentFocusTarget.Switch),
            parentRailTargets(childOn = false, remoteVisible = true),
        )
    }

    @Test
    fun childModeOnWithoutCloudShowsOnlyTheSwitch() {
        assertEquals(
            listOf(ParentFocusTarget.Switch),
            parentRailTargets(childOn = true, remoteVisible = false),
        )
    }

    @Test
    fun remoteManagementFollowsTheSwitch() {
        assertEquals(
            listOf(ParentFocusTarget.Switch, ParentFocusTarget.Remote),
            parentRailTargets(childOn = true, remoteVisible = true),
        )
    }
}
