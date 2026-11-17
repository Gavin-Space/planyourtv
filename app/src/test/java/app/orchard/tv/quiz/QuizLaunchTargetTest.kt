package app.orchard.tv.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The post-quiz launch is one-shot and only armed by a settlement that really started a session.
 * The input page is armed before the round, so a cancelled round must not leave it ready to fire.
 */
class QuizLaunchTargetTest {
    @Before
    fun setUp() {
        QuizLaunchTarget.clear()
    }

    @Test
    fun armedInputsIsNotAConfirmation() {
        QuizLaunchTarget.armInputs()
        assertNull(QuizLaunchTarget.confirmed.value)
    }

    @Test
    fun successfulSettlementConfirmsTheArmedInputs() {
        QuizLaunchTarget.armInputs()
        QuizLaunchTarget.confirm(null)
        assertEquals(QuizLaunchTarget.Target.Inputs, QuizLaunchTarget.confirmed.value)
    }

    @Test
    fun clearedInputsIsNotRevivedByTheNextSettlement() {
        QuizLaunchTarget.armInputs()
        QuizLaunchTarget.clear()
        QuizLaunchTarget.confirm(null)
        assertNull(QuizLaunchTarget.confirmed.value)
    }

    @Test
    fun appTargetDoesNotReuseTheArmedInputs() {
        QuizLaunchTarget.armInputs()
        QuizLaunchTarget.confirm("com.example.tv")
        assertEquals(QuizLaunchTarget.Target.App("com.example.tv"), QuizLaunchTarget.confirmed.value)

        QuizLaunchTarget.clear()
        QuizLaunchTarget.confirm(null)
        assertNull(QuizLaunchTarget.confirmed.value)
    }

    @Test
    fun consumeIsOneShot() {
        QuizLaunchTarget.armInputs()
        QuizLaunchTarget.confirm(null)
        val target = requireNotNull(QuizLaunchTarget.confirmed.value)
        assertTrue(QuizLaunchTarget.consume(target))
        assertNull(QuizLaunchTarget.confirmed.value)
        assertFalse(QuizLaunchTarget.consume(target))
    }
}
