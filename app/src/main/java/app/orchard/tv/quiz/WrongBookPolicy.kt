package app.orchard.tv.quiz

import com.planyourtv.policy.TvStore

/**
 * The wrong book's weekly cycle.
 *
 * The child's mistakes are replayed in practice until they are answered
 * correctly (that answer is what takes a question out of the book). A cycle is
 * anchored at the moment the book first held a mistake after the previous clear
 * — not at a calendar week boundary — so a child who starts practising on a
 * Wednesday gets a full seven days. While the cycle runs the mistakes are mixed
 * into normal rounds and new questions keep flowing; once the seven days are up
 * and mistakes are still unanswered, the book is overdue and practice serves
 * nothing but the book until it is empty.
 *
 * This lives outside [QuizQuestionCache] because it is policy, not storage: the
 * cache only knows which rows are wrong.
 */
object WrongBookPolicy {
    /** A cycle is a rolling seven days from the first mistake, not a calendar week. */
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000L

    // Zero means "no open cycle". A real timestamp can never be zero, so the
    // absent case needs no separate flag.
    private const val KEY_CYCLE_START = "quiz.wrongBook.cycleStartMs"
    private const val KEY_LAST_CLEAR = "quiz.wrongBook.lastClearMs"

    private const val DAY_MS = 24L * 60 * 60 * 1000L

    data class State(
        /** Questions currently in the book. */
        val count: Int,
        /** When the open cycle started, or 0 when no cycle is open. */
        val cycleStartMs: Long,
        /** True when the week ended with mistakes still unanswered. */
        val overdue: Boolean,
        /** Whole days until the cycle ends; 0 once overdue. */
        val daysLeft: Int,
    ) {
        /** The book is open but not yet owed: mistakes mix into normal rounds. */
        val mixing: Boolean get() = count > 0 && !overdue
    }

    fun state(nowMs: Long = System.currentTimeMillis()): State {
        val count = QuizQuestionCache.wrongCount()
        if (count <= 0) {
            // The book emptied itself through mastery. Close the cycle so the
            // next mistake starts a fresh seven days instead of inheriting the
            // remaining days of a book that no longer exists.
            if (cycleStart() != 0L) saveCycleStart(0L)
            return State(count = 0, cycleStartMs = 0L, overdue = false, daysLeft = 0)
        }
        var start = cycleStart()
        if (start <= 0L) {
            start = nowMs
            saveCycleStart(start)
        }
        val elapsed = nowMs - start
        val remaining = WEEK_MS - elapsed
        val overdue = remaining <= 0L
        val daysLeft = if (overdue) 0 else ((remaining + DAY_MS - 1) / DAY_MS).toInt()
        return State(count = count, cycleStartMs = start, overdue = overdue, daysLeft = daysLeft)
    }

    /**
     * Called when the parent clears the wrong book. The next mistake opens a new
     * cycle, which is exactly the "clear it to get new questions" rule.
     */
    fun markCleared(nowMs: Long = System.currentTimeMillis()) {
        TvStore.putLong(KEY_LAST_CLEAR, nowMs)
        saveCycleStart(0L)
        TvStore.sync()
    }

    /** When the book was last cleared, for the parent page; 0 when never. */
    fun lastClearMs(): Long = TvStore.getLong(KEY_LAST_CLEAR, 0L)

    private fun cycleStart(): Long = TvStore.getLong(KEY_CYCLE_START, 0L)

    private fun saveCycleStart(value: Long) {
        TvStore.putLong(KEY_CYCLE_START, value)
        TvStore.sync()
    }
}
