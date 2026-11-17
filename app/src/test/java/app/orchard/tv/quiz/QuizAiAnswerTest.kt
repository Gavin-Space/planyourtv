package app.orchard.tv.quiz

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuizAiAnswerTest {
    private val options = listOf("Alpha", "Bravo", "Charlie", "Delta")

    @Test
    fun letterAndTextMustAgree() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "C", "Charlie")
        assertNull(resolved.error)
        assertEquals(2, resolved.index)
    }

    @Test
    fun letterAcceptsCommonForms() {
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "C.", "Charlie").index)
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "(C)", "Charlie").index)
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "Option C", "Charlie").index)
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "答案：C", "Charlie").index)
    }

    @Test
    fun textToleratesPrefixesAndQuotes() {
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "C", "C. Charlie").index)
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "C", "(C) Charlie").index)
        assertEquals(2, QuizAiClient.resolveAnswerIndex(options, "C", "“Charlie”").index)
    }

    @Test
    fun textMatchIgnoresCase() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "C", "charlie")
        assertNull(resolved.error)
        assertEquals(2, resolved.index)
    }

    @Test
    fun missingLetterIsRejectedInsteadOfUsingTextAlone() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "", "Charlie")
        assertEquals("answerLetter", resolved.error)
        assertNull(resolved.index)
    }

    @Test
    fun outOfRangeLetterIsRejectedInsteadOfFallingBack() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "E", "Alpha")
        assertEquals("answerLetter range", resolved.error)
        assertNull(resolved.index)
    }

    @Test
    fun missingTextIsRejectedInsteadOfUsingLetterAlone() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "C", "")
        assertEquals("answerText", resolved.error)
        assertNull(resolved.index)
    }

    @Test
    fun textMustBeOneOfTheOptions() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "C", "Echo")
        assertEquals("answerText match", resolved.error)
        assertNull(resolved.index)
    }

    @Test
    fun conflictingLetterAndTextAreRejected() {
        val resolved = QuizAiClient.resolveAnswerIndex(options, "C", "Delta")
        assertEquals("answer mismatch", resolved.error)
        assertNull(resolved.index)
    }

    @Test
    fun multipleAnswersResolveAsASortedSet() {
        val resolved = QuizAiClient.resolveMultipleAnswer(
            options,
            JSONArray().put("D").put("A").put("C"),
            JSONArray().put("Delta").put("Alpha").put("Charlie"),
        )
        assertNull(resolved.error)
        assertEquals(listOf(0, 2, 3), resolved.indices)
    }

    @Test
    fun multipleAnswerArraysMustAgreeAndLeaveADistractor() {
        fun resolve(letters: List<String>, texts: List<String>) = QuizAiClient.resolveMultipleAnswer(
            options,
            JSONArray().apply { letters.forEach { put(it) } },
            JSONArray().apply { texts.forEach { put(it) } },
        )

        assertEquals(
            "answer length mismatch",
            resolve(listOf("A", "C"), listOf("Alpha")).error,
        )
        assertEquals(
            "answer mismatch",
            resolve(listOf("A", "C"), listOf("Alpha", "Bravo")).error,
        )
        assertEquals(
            "answer duplicate",
            resolve(listOf("A", "A"), listOf("Alpha", "Alpha")).error,
        )
        assertEquals("answer count", resolve(listOf("A"), listOf("Alpha")).error)
        assertEquals(
            "answer count",
            resolve(
                listOf("A", "B", "C", "D"),
                listOf("Alpha", "Bravo", "Charlie", "Delta"),
            ).error,
        )
        assertEquals(
            "answerText match",
            resolve(listOf("A", "C"), listOf("Alpha", "Echo")).error,
        )
    }

    @Test
    fun multipleAnswerRequiresAtLeastThreeOptions() {
        val resolved = QuizAiClient.resolveMultipleAnswer(
            listOf("Alpha", "Bravo"),
            JSONArray().put("A").put("B"),
            JSONArray().put("Alpha").put("Bravo"),
        )
        assertEquals("answer count", resolved.error)
    }

    @Test
    fun parsesMultipleChoiceOnlyWhenRequested() {
        val content = """{"questions":[${multipleQuestionJson()}]}"""
        val mixed = QuizAiClient.parse(envelope(content), request(count = 1, questionType = "mixed"))
        assertTrue(mixed is QuizAiClient.Result.Success)
        val item = (mixed as QuizAiClient.Result.Success).items.single()
        assertEquals(
            com.planyourtv.policy.quiz.QuizAnswerKind.MULTIPLE,
            item.answerKind,
        )
        assertEquals(listOf(0, 2), item.answerIndices)
        assertEquals(0, item.answerIndex)
        assertEquals(listOf(0, 2), item.correctIndices)

        // The generation setting is a hard constraint: a request for single
        // answers drops the multiple-choice item instead of down-grading it.
        val single = QuizAiClient.parse(envelope(content), request(count = 1))
        assertEquals(QuizAiClient.Result.Failure("AI_EMPTY"), single)

        val multiple = QuizAiClient.parse(
            envelope(content),
            request(count = 1, questionType = "multiple"),
        )
        assertTrue(multiple is QuizAiClient.Result.Success)
    }

    @Test
    fun rejectsMixedOrTruncatedMultipleAnswerShapes() {
        val ambiguous = multipleQuestionJson()
            .put("answerLetter", "A")
            .put("answerText", "a")
        assertEquals(
            QuizAiClient.Result.Failure("AI_EMPTY"),
            QuizAiClient.parse(
                envelope("""{"questions":[$ambiguous]}"""),
                request(count = 1, questionType = "multiple"),
            ),
        )

        val singleAnswer = multipleQuestionJson()
            .put("answerLetters", JSONArray().put("A"))
            .put("answerTexts", JSONArray().put("a"))
        assertEquals(
            QuizAiClient.Result.Failure("AI_EMPTY"),
            QuizAiClient.parse(
                envelope("""{"questions":[$singleAnswer]}"""),
                request(count = 1, questionType = "multiple"),
            ),
        )
    }

    @Test
    fun singleAnswerIsDroppedWhenMultipleWasRequested() {
        val content = """{"questions":[${questionJson("q1")}]}"""
        assertEquals(
            QuizAiClient.Result.Failure("AI_EMPTY"),
            QuizAiClient.parse(envelope(content), request(count = 1, questionType = "multiple")),
        )
        val mixed = QuizAiClient.parse(envelope(content), request(count = 1, questionType = "mixed"))
        assertTrue(mixed is QuizAiClient.Result.Success)
    }

    @Test
    fun legacyScalarAnswerWithoutTypeStaysReadable() {
        val parsed = QuizAiClient.parse(
            envelope("""{"questions":[${questionJson("q1")}]}"""),
            request(count = 1),
        )
        assertTrue(parsed is QuizAiClient.Result.Success)
        val item = (parsed as QuizAiClient.Result.Success).items.single()
        assertEquals(com.planyourtv.policy.quiz.QuizAnswerKind.SINGLE, item.answerKind)
        assertEquals(emptyList<Int>(), item.answerIndices)
    }

    @Test
    fun solutionKeepsNumberedStepsOnSeparateLines() {
        val raw = "1. 8 = 2 × 4\r\n2. 4 = 2 × 2\n\n\n3. 所以 8 是偶数"
        assertEquals(
            "1. 8 = 2 × 4\n2. 4 = 2 × 2\n\n3. 所以 8 是偶数",
            QuizAiClient.cleanSolution(raw),
        )
    }

    @Test
    fun solutionCollapsesOnlyHorizontalWhitespace() {
        assertEquals(
            "1. Step one\n2. Step two",
            QuizAiClient.cleanSolution("  1.   Step\tone  \n 2. Step   two  "),
        )
    }

    @Test
    fun missingSolutionIsEmpty() {
        assertEquals("", QuizAiClient.cleanSolution(null))
        assertEquals("", QuizAiClient.cleanSolution(42))
    }

    @Test
    fun truncatedResponseKeepsCompleteQuestionObjects() {
        val content = """
            {"questions":[
              {"prompt":"q1","options":["a","b"]},
              {"prompt":"q2","options":["a","b"]},
              {"prompt":"q3","options":["a","b"
        """.trimIndent()
        val questions = QuizAiClient.salvageQuestions(content, 5)!!
        assertEquals(2, questions.length())
        assertEquals("q1", questions.getJSONObject(0).getString("prompt"))
        assertEquals("q2", questions.getJSONObject(1).getString("prompt"))
    }

    @Test
    fun salvageAcceptsTopLevelArrayAndFences() {
        val content = """
            ```json
            [{"prompt":"q1","options":["a"]},{"prompt":"q2","options":["b"]}]
            ```
        """.trimIndent()
        val questions = QuizAiClient.salvageQuestions(content, 5)!!
        assertEquals(2, questions.length())
        assertEquals("q2", questions.getJSONObject(1).getString("prompt"))
    }

    @Test
    fun salvageIgnoresObjectsWithoutQuestionShape() {
        val content = """{"other":{"prompt":"not-a-question"},"questions":[{"prompt":"q","options":[]}"""
        val questions = QuizAiClient.salvageQuestions(content, 5)!!
        assertEquals(1, questions.length())
        assertEquals("q", questions.getJSONObject(0).getString("prompt"))
    }

    @Test
    fun truncatedProviderResponseKeepsCompleteQuestions() {
        val first = questionJson("q1").toString()
        val second = questionJson("q2").toString()
        val content = """{"questions":[$first,$second,{"prompt":"q3","options":["a","b","c","d"]"""
        val parsed = QuizAiClient.parse(envelope(content), request(count = 5))
        assertTrue(parsed is QuizAiClient.Result.Success)
        val success = parsed as QuizAiClient.Result.Success
        assertEquals(2, success.items.size)
        assertEquals("q1", success.items[0].prompt)
        assertTrue(success.warnings.any { it.contains("recovered") })
    }

    @Test
    fun fiftyQuestionResponseIsSupported() {
        val questions = (1..50).joinToString(",") { questionJson("q$it").toString() }
        val content = """{"questions":[$questions]}"""
        val parsed = QuizAiClient.parse(envelope(content), request(count = 50))
        assertTrue(parsed is QuizAiClient.Result.Success)
        assertEquals(50, (parsed as QuizAiClient.Result.Success).items.size)
    }

    private fun request(
        count: Int,
        questionType: String = QuizGeneration.QUESTION_TYPE_SINGLE,
    ): QuizAiClient.Request = QuizAiClient.Request(
        count = count,
        options = 4,
        language = "zh-CN",
        subject = "语文",
        grade = "小学四年级",
        prompt = "第三单元",
        difficulty = "",
        points = 1,
        timeLimitSec = 60,
        questionType = questionType,
    )

    private fun envelope(content: String): String = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("content", content),
                ),
            ),
        )
        .toString()

    private fun questionJson(prompt: String): JSONObject = JSONObject()
        .put("prompt", prompt)
        .put("options", JSONArray().put("a").put("b").put("c").put("d"))
        .put("answerLetter", "A")
        .put("answerText", "a")
        .put("explanation", "1. 正确答案是 a。\n2. 其他选项不符合题意。")

    private fun multipleQuestionJson(): JSONObject = JSONObject()
        .put("type", "multiple")
        .put("prompt", "q-multiple")
        .put("options", JSONArray().put("a").put("b").put("c").put("d"))
        .put("answerLetters", JSONArray().put("A").put("C"))
        .put("answerTexts", JSONArray().put("a").put("c"))
        .put("explanation", "1. a 和 c 正确。\n2. b、d 不符合题意。")
}
