package app.orchard.tv.ui.policy

/** Logical focus targets. Compose focus search is never used to move between them. */
internal sealed interface ParentFocusTarget {
    data object Switch : ParentFocusTarget
    data object Remote : ParentFocusTarget
    data object MigrationRetry : ParentFocusTarget
    data object WaitConfigRetry : ParentFocusTarget
    data object SummaryRead : ParentFocusTarget
    data class LocalRow(val key: String) : ParentFocusTarget
}

/**
 * The V29 rail has only two entries: the child-mode switch and remote management. Every local
 * rule is configured on the phone; the TV never edits them.
 *
 * Remote management is hidden while child mode is off and while the cloud service is not
 * enabled; the caller decides that through [remoteVisible].
 */
internal fun parentRailTargets(
    childOn: Boolean,
    remoteVisible: Boolean,
): List<ParentFocusTarget> = buildList {
    add(ParentFocusTarget.Switch)
    if (childOn && remoteVisible) add(ParentFocusTarget.Remote)
}

/** The right-hand pane. The rail no longer selects a guard mode. */
internal enum class ParentPaneKind {
    OFF,
    LOCAL,
    BIND,
    MIGRATION,
    WAIT_CONFIG,
    SUMMARY,
    PARENT_WEB_QR,
}

internal enum class ParentZone { RAIL, PANE }

/** Left always returns to the rail row that opened the pane. */
internal enum class ParentRailOrigin { SWITCH, REMOTE }
