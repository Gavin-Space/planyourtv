package app.orchard.tv.quiz

import com.planyourtv.policy.quiz.QuizItem

/** Builds the exact text sent to DeepSeek; the answer is never part of a question utterance. */
internal object QuizSpeechText {
    // Fill-in questions use runs of underscores (ASCII or full-width) as blanks.
    // Read verbatim, TTS spells out every underscore; speak the placeholder
    // instead. A single underscore inside an English word (snake_case) is left
    // alone, while a standalone one is treated as a blank as well.
    private const val BLANK_WORD_ZH = "空格"
    private const val BLANK_WORD_EN = "blank"
    private val BLANK_CANDIDATE = Regex("[_＿](?:[ \\t\\u00A0\\u3000]*[_＿])+")

    // Only ASCII letters and digits keep a single underscore as part of a word.
    // Chinese (and other non-Latin) text has no word identifiers, so "她_去"
    // is still a blank.
    private val STANDALONE_BLANK = Regex("(?<![A-Za-z0-9_＿])[_＿](?![A-Za-z0-9_＿])")

    private val CJK = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]")
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")

    private val ISO_DATE = Regex("(?<!\\d)((?:19|20)\\d{2})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})(?!\\d)")
    private val PLUS = Regex("(?<=[0-9A-Za-z)）])\\s*\\+\\s*(?=[0-9A-Za-z(（])")
    private val MINUS = Regex("(?<=[0-9)）])\\s*[-－]\\s*(?=[0-9(（])")
    private val PERCENT = Regex("(\\d+(?:\\.\\d+)?)\\s*[%％]")
    private val DEGREE = Regex("(\\d+(?:\\.\\d+)?)\\s*°")

    fun question(item: QuizItem, settings: QuizSpeechSettings): String? {
        val english = isEnglish(item)
        val parts = ArrayList<String>(2)
        if (settings.readPrompt) {
            val prompt = speakable(item.prompt, english)
            if (prompt.isNotEmpty()) {
                parts += if (english) "Question: $prompt" else "题目：$prompt"
            }
        }
        if (settings.readOptions) {
            val options = item.options.mapIndexedNotNull { index, option ->
                speakable(option, english).takeIf { it.isNotEmpty() }?.let {
                    if (english) {
                        "${optionLabel(index)}, $it"
                    } else {
                        // Repeating the Chinese word keeps each short option
                        // from being auto-detected as an English snippet.
                        "选项${optionLabel(index)}，$it"
                    }
                }
            }
            if (options.isNotEmpty()) {
                parts += if (english) {
                    "Options: ${options.joinToString("; ")}."
                } else {
                    "${options.joinToString("；")}。"
                }
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    fun answer(item: QuizItem, settings: QuizSpeechSettings): String? {
        if (!settings.readAnswer) return null
        val english = isEnglish(item)
        val answers = item.correctIndices.mapNotNull { index ->
            item.options.getOrNull(index)?.let { speakable(it, english) }?.takeIf {
                it.isNotEmpty()
            }?.let {
                if (english) {
                    "${optionLabel(index)}, $it"
                } else {
                    "选项${optionLabel(index)}，$it"
                }
            }
        }
        if (answers.isEmpty()) return null
        val parts = ArrayList<String>(2)
        parts += if (english) {
            "The correct answer is ${answers.joinToString("; ")}."
        } else {
            "正确答案是${answers.joinToString("；")}。"
        }
        // The result page shows the explanation right below the correct answer,
        // so read it in the same utterance. The explanation can be written in a
        // different language from the prompt, so pick its voice frame and math
        // normalisation from its own text.
        val explanation = item.explanation.trim()
        if (explanation.isNotEmpty()) {
            val explanationEnglish = isEnglishText(explanation)
            val spoken = speakable(explanation, explanationEnglish)
            if (spoken.isNotEmpty()) {
                parts += if (explanationEnglish) {
                    "Explanation: $spoken"
                } else {
                    "解题过程：$spoken"
                }
            }
        }
        return parts.joinToString("\n")
    }

    /**
     * An English question is one written without Chinese characters that
     * contains an actual English word. Math notation and single-letter
     * variables ("2+2=?", "M÷N") stay Chinese because they are not English
     * prose, even when the TV UI is in another language.
     */
    private fun isEnglish(item: QuizItem): Boolean =
        isEnglishText(item.prompt) || item.options.any(::isEnglishText)

    private fun isEnglishText(raw: String): Boolean =
        !CJK.containsMatchIn(raw) && LATIN_WORD.containsMatchIn(raw)

    private fun speakable(raw: String, english: Boolean): String {
        val blank = if (english) BLANK_WORD_EN else BLANK_WORD_ZH
        val text = replaceBlanks(raw.trim(), blank)
            .replace(STANDALONE_BLANK, blank)
        return if (english) englishMath(text) else chineseMath(text)
    }

    /**
     * A two-underscore run attached to an ASCII word is an identifier
     * (__init__, my__var), not a fill-in blank. Three or more underscores, or
     * a run next to spaces/CJK, is always a blank.
     */
    private fun replaceBlanks(raw: String, blank: String): String =
        BLANK_CANDIDATE.replace(raw) { match ->
            val count = match.value.count { it == '_' || it == '＿' }
            val before = raw.getOrNull(match.range.first - 1)
            val after = raw.getOrNull(match.range.last + 1)
            if (count == 2 && (before.isAsciiWordChar() || after.isAsciiWordChar())) {
                match.value
            } else {
                blank
            }
        }

    private fun Char?.isAsciiWordChar(): Boolean =
        this != null && (this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9')

    /**
     * DeepSeek has no language parameter and detects it from the text. Chinese
     * math prompts are mostly digits and Latin variables, so the operators are
     * spelled out in Chinese; otherwise the speech endpoint may switch to an
     * English reading. Dates are expanded first so their hyphens are not read
     * as subtraction.
     */
    private fun chineseMath(raw: String): String {
        var text = raw.replace(ISO_DATE) {
            "${it.groupValues[1]}年${it.groupValues[2]}月${it.groupValues[3]}日"
        }
        text = text
            .replace("≠", "不等于")
            .replace("≥", "大于等于")
            .replace("≤", "小于等于")
            .replace("≈", "约等于")
            .replace("÷", "除以")
            .replace("×", "乘")
            .replace("✕", "乘")
            .replace("✖", "乘")
            .replace("＝", "等于")
            .replace("=", "等于")
            .replace("＋", "加")
            .replace(PLUS, "加")
            .replace("－", "减")
            .replace(MINUS, "减")
            .replace(">", "大于")
            .replace("<", "小于")
            .replace("√", "根号")
            .replace("π", "派")
            .replace("°C", "摄氏度")
            .replace("°c", "摄氏度")
            .replace("℃", "摄氏度")
            .replace(DEGREE) { "${it.groupValues[1]}度" }
            .replace(PERCENT) { "百分之${it.groupValues[1]}" }
        return text
    }

    /** English math symbols are spelled out so the English voice reads them consistently. */
    private fun englishMath(raw: String): String =
        raw
            .replace("≠", " does not equal ")
            .replace("≥", " is greater than or equal to ")
            .replace("≤", " is less than or equal to ")
            .replace("≈", " is approximately ")
            .replace("÷", " divided by ")
            .replace("×", " times ")
            .replace("✕", " times ")
            .replace("✖", " times ")
            .replace("=", " equals ")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\s+([?.!,;:])")) { it.groupValues[1] }
            .trim()

    private fun optionLabel(index: Int): String =
        if (index in 0..5) ('A' + index).toString() else (index + 1).toString()
}
