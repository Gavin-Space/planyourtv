package app.orchard.tv.quiz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The TV-use entry a successful quiz round should open: an entertainment app or the TV input page.
 *
 * Settlement starts the policy session, which disposes the lock screen — and with it the review
 * composition — before the report can call back into the host. The settlement therefore confirms
 * the target here, outside any composition, and [app.orchard.tv.ui.policy.PolicyHomeGate] collects
 * it, waits for the session to become ACTIVE and opens it once.
 *
 * The target is confirmed only after the policy session really started; abandoning the quiz or a
 * refused start leaves it empty. The input page is armed before its round because settlement only
 * receives an app package; [confirm] with a null package promotes the armed entry on success.
 */
object QuizLaunchTarget {
    sealed interface Target {
        data class App(val packageName: String) : Target

        /** The TV input list, opened on Home once the earned session starts. */
        data object Inputs : Target
    }

    private val _confirmed = MutableStateFlow<Target?>(null)
    val confirmed: StateFlow<Target?> = _confirmed.asStateFlow()

    // Set by the lock screen's input entry before the quiz. The settlement's null package means
    // this round was opened for the input page, so only a successful settlement may promote it.
    @Volatile
    private var pending: Target? = null

    /** Arms the input page a successful settlement may open. */
    @Synchronized
    fun armInputs() {
        pending = Target.Inputs
    }

    /** Arms the launch after the earned session started. */
    @Synchronized
    fun confirm(packageName: String?) {
        val target = if (packageName != null && packageName.isNotBlank()) {
            Target.App(packageName)
        } else {
            pending
        }
        pending = null
        if (target == null) return
        _confirmed.value = target
    }

    /** Takes the one-shot launch, clearing it only if it is still [target]. */
    @Synchronized
    fun consume(target: Target): Boolean {
        if (_confirmed.value != target) return false
        _confirmed.value = null
        return true
    }

    @Synchronized
    fun clear() {
        pending = null
        _confirmed.value = null
    }
}
