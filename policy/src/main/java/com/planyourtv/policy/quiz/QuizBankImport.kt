package com.planyourtv.policy.quiz

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** One structured import error; index is 0-based and the page adds one. */
data class QuizImportError(
    val index: Int,
    val field: String,
    val code: String,
)

sealed interface QuizImportResult {
    data class Success(val items: List<QuizItem>) : QuizImportResult
    data class Failure(val errors: List<QuizImportError>) : QuizImportResult
}

/**
 * Parses manually supplied questions into the same [QuizItem] shape the cache
 * and round engine already use. The parser is intentionally strict: an import
 * batch is all-or-nothing, so a partially understood file never reaches the
 * bank. Single- and multiple-answer questions are supported; unsupported shapes
 * (fill-in, images) are rejected or skipped by the instructions, never guessed
 * here.
 */
object QuizBankImport {
    const val MAX_ITEMS = 200
    const val MAX_ERRORS = 20

    private val DIFFICULTIES = setOf("easy", "medium", "hard")
    private val ANSWER_FIELDS = setOf(
        "prompt",
        "options",
        "type",
        "answer",
        "answerIndex",
        "answerIndices",
        "explanation",
        "difficulty",
    )
    private val QUESTION_NUMBER = Regex("^\\s*(?:(\\d+)[.、．)）]|【(\\d+)】)\\s*(.*)$")
    private val OPTION_LINE = Regex("^\\s*([A-Fa-f])[.、．:：)）]\\s*(.*)$")
    private val ANSWER_LINE = Regex("^\\s*(?:参考答案|正确答案|答案)\\s*[:：]\\s*(.*)$")
    private val EXPLANATION_LINE = Regex("^\\s*(?:解析|解答)\\s*[:：]\\s*(.*)$")

    /** Common ways a multi-answer key is written: AB, A、B, A,B, A 和 B. */
    private val MULTI_ANSWER = Regex(
        "^\\s*[A-F](?:\\s*(?:[、,，/&+;；和与及]|and|or)?\\s*[A-F])+\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Canonical JSON: a bare array or `{ "items": [...] }` is accepted. */
    fun parseJsonItems(
        items: JSONArray,
        defaultDifficulty: String? = null,
    ): QuizImportResult {
        val fallback = normalizeDifficulty(defaultDifficulty)
            ?: return failure(-1, "defaultDifficulty", "INVALID_DIFFICULTY")
        if (items.length() > MAX_ITEMS) return failure(0, "items", "TOO_MANY_ITEMS")
        if (items.length() == 0) return failure(0, "items", "NO_ITEMS")
        val errors = mutableListOf<QuizImportError>()
        val parsed = ArrayList<QuizItem>(items.length())
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            if (item == null) {
                errors += QuizImportError(index, "item", "TYPE")
                continue
            }
            val unknownFields = item.keys().asSequence().filterNot { it in ANSWER_FIELDS }
            unknownFields.forEach { errors += QuizImportError(index, it, "UNKNOWN_FIELD") }

            val prompt = readPrompt(item, index, errors) ?: continue
            val options = readOptions(item, index, errors) ?: continue
            val answer = readAnswer(item, options, index, errors) ?: continue
            val explanation = readExplanation(item, index, errors) ?: continue
            val difficulty = readDifficulty(item, index, fallback, errors) ?: continue

            parsed += QuizItem(
                questionId = newQuestionId(),
                prompt = prompt,
                options = options,
                answerIndex = answer.indices.first(),
                explanation = explanation,
                difficulty = difficulty,
                points = 1,
                timeLimitSec = 60,
                recommendedTimeSec = 60,
                answerIndices = if (answer.kind == QuizAnswerKind.MULTIPLE) {
                    answer.indices
                } else {
                    emptyList()
                },
                answerKind = answer.kind,
            )
        }
        if (errors.isNotEmpty()) return QuizImportResult.Failure(errors.take(MAX_ERRORS))
        return QuizImportResult.Success(parsed)
    }

    /** Numbered text format: `1. prompt`, `A. option`, `答案：B`, `解析：...`. */
    fun parseText(
        text: String,
        defaultDifficulty: String? = null,
    ): QuizImportResult {
        val fallback = normalizeDifficulty(defaultDifficulty)
            ?: return failure(-1, "defaultDifficulty", "INVALID_DIFFICULTY")
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
            .removePrefix("﻿")
            .trim()
        if (normalized.isEmpty()) return failure(0, "text", "NO_ITEMS")

        val lines = normalized.split('\n')
        val blocks = mutableListOf<MutableList<String>>()
        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val match = QUESTION_NUMBER.matchEntire(line)
            if (match != null) {
                val first = match.groupValues[3].trim()
                blocks += mutableListOf(first)
            } else if (blocks.isEmpty()) {
                if (line.isNotBlank()) return failure(0, "text", "NO_QUESTIONS")
            } else {
                blocks.last() += line
            }
        }
        if (blocks.isEmpty()) return failure(0, "text", "NO_QUESTIONS")
        if (blocks.size > MAX_ITEMS) return failure(0, "text", "TOO_MANY_ITEMS")

        val errors = mutableListOf<QuizImportError>()
        val parsed = ArrayList<QuizItem>(blocks.size)
        blocks.forEachIndexed { index, block ->
            val item = parseTextBlock(index, block, fallback, errors)
            if (item != null) parsed += item
        }
        if (errors.isNotEmpty()) return QuizImportResult.Failure(errors.take(MAX_ERRORS))
        return QuizImportResult.Success(parsed)
    }

    /**
     * Text blocks may omit a question body line when the number is alone; the
     * following lines then form the prompt until the first option.
     */
    private fun parseTextBlock(
        index: Int,
        lines: List<String>,
        fallbackDifficulty: String,
        errors: MutableList<QuizImportError>,
    ): QuizItem? {
        var answerLine = -1
        var answerRaw: String? = null
        var explanationLine = -1
        var explanationRaw = ""
        lines.forEachIndexed { lineIndex, line ->
            val answer = ANSWER_LINE.matchEntire(line)
            if (answer != null) {
                if (answerLine >= 0) {
                    errors += QuizImportError(index, "answer", "DUPLICATE")
                } else {
                    answerLine = lineIndex
                    answerRaw = answer.groupValues[1].trim()
                }
            }
            val explanation = EXPLANATION_LINE.matchEntire(line)
            if (explanation != null && explanationLine < 0) {
                explanationLine = lineIndex
                explanationRaw = explanation.groupValues[1].trim()
            }
        }
        if (answerLine < 0 || answerRaw.isNullOrBlank()) {
            errors += QuizImportError(index, "answer", "REQUIRED")
        }
        if (answerLine >= 0 && explanationLine in 0 until answerLine) {
            errors += QuizImportError(index, "explanation", "ORDER")
        }

        val bodyEnd = listOf(
            if (answerLine >= 0) answerLine else lines.size,
            if (explanationLine >= 0) explanationLine else lines.size,
        ).minOrNull() ?: lines.size
        val promptLines = mutableListOf<String>()
        val optionLetters = mutableListOf<Char>()
        val optionTexts = mutableListOf<String>()
        for (lineIndex in 0 until bodyEnd) {
            val line = lines[lineIndex].trim()
            if (line.isBlank()) continue
            val option = OPTION_LINE.matchEntire(line)
            if (option != null) {
                val letter = option.groupValues[1].first().uppercaseChar()
                val value = option.groupValues[2].trim()
                if (optionLetters.contains(letter)) {
                    errors += QuizImportError(index, "options", "DUPLICATE")
                } else {
                    optionLetters += letter
                    optionTexts += value
                }
            } else if (optionTexts.isEmpty()) {
                promptLines += line
            } else {
                errors += QuizImportError(index, "options", "FORMAT")
            }
        }

        if (answerLine >= 0 && (explanationLine < 0 || explanationLine > answerLine) &&
            lines.subList(answerLine + 1, if (explanationLine >= 0) explanationLine else lines.size)
                .any { it.isNotBlank() }
        ) {
            errors += QuizImportError(index, "answer", "FORMAT")
        }

        val prompt = promptLines.joinToString("\n").trim()
        if (prompt.isEmpty() || prompt.length > QUIZ_MAX_PROMPT_CHARS) {
            errors += QuizImportError(index, "prompt", if (prompt.isEmpty()) "REQUIRED" else "TOO_LONG")
        }
        if (optionTexts.size !in QUIZ_MIN_OPTIONS..QUIZ_MAX_OPTIONS) {
            errors += QuizImportError(index, "options", "COUNT")
        }
        if (optionTexts.any { it.isEmpty() || it.length > QUIZ_MAX_OPTION_CHARS }) {
            errors += QuizImportError(index, "options", "INVALID_TEXT")
        }
        if (optionTexts.map { it.lowercase(Locale.ROOT) }.toSet().size != optionTexts.size) {
            errors += QuizImportError(index, "options", "DUPLICATE")
        }

        val answer = if (answerRaw.isNullOrBlank() || optionTexts.isEmpty()) {
            null
        } else {
            val resolved = resolveTextAnswer(answerRaw, optionLetters, optionTexts)
            when {
                resolved == null -> {
                    errors += QuizImportError(
                        index,
                        "answer",
                        if (isMultipleAnswer(answerRaw)) "ANSWER_FORMAT" else "ANSWER_RANGE",
                    )
                    null
                }
                !validateAnswerShape(index, optionTexts, resolved, errors) -> null
                else -> resolved
            }
        }

        val explanation = buildExplanation(lines, explanationLine, explanationRaw) ?: run {
            errors += QuizImportError(index, "explanation", "TOO_LONG")
            null
        } ?: return null

        if (prompt.isEmpty() || prompt.length > QUIZ_MAX_PROMPT_CHARS ||
            optionTexts.size !in QUIZ_MIN_OPTIONS..QUIZ_MAX_OPTIONS ||
            optionTexts.any { it.isEmpty() || it.length > QUIZ_MAX_OPTION_CHARS } ||
            answer == null
        ) {
            return null
        }
        return QuizItem(
            questionId = newQuestionId(),
            prompt = prompt,
            options = optionTexts.toList(),
            answerIndex = answer.indices.first(),
            explanation = explanation,
            difficulty = fallbackDifficulty,
            points = 1,
            timeLimitSec = 60,
            recommendedTimeSec = 60,
            answerIndices = if (answer.kind == QuizAnswerKind.MULTIPLE) {
                answer.indices
            } else {
                emptyList()
            },
            answerKind = answer.kind,
        )
    }

    private fun buildExplanation(
        lines: List<String>,
        explanationLine: Int,
        first: String,
    ): String? {
        if (explanationLine < 0) return ""
        val rest = lines.subList(explanationLine + 1, lines.size)
            .map { it.trim() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        val text = (listOf(first) + rest).filter { it.isNotEmpty() }.joinToString("\n").trim()
        if (text.length > QUIZ_MAX_EXPLANATION_CHARS) return null
        return text
    }

    private fun readPrompt(item: JSONObject, index: Int, errors: MutableList<QuizImportError>): String? {
        val value = if (item.has("prompt") && !item.isNull("prompt")) item.opt("prompt") else null
        if (value !is String) {
            errors += QuizImportError(index, "prompt", "REQUIRED")
            return null
        }
        val prompt = value.trim()
        if (prompt.isEmpty()) {
            errors += QuizImportError(index, "prompt", "REQUIRED")
            return null
        }
        if (prompt.length > QUIZ_MAX_PROMPT_CHARS) {
            errors += QuizImportError(index, "prompt", "TOO_LONG")
            return null
        }
        return prompt
    }

    private fun readOptions(item: JSONObject, index: Int, errors: MutableList<QuizImportError>): List<String>? {
        val value = if (item.has("options") && !item.isNull("options")) item.opt("options") else null
        if (value !is JSONArray) {
            errors += QuizImportError(index, "options", "REQUIRED")
            return null
        }
        val options = ArrayList<String>(value.length())
        for (position in 0 until value.length()) {
            val option = value.opt(position)
            if (option !is String) {
                errors += QuizImportError(index, "options", "TYPE")
                return null
            }
            options += option.trim()
        }
        if (options.size !in QUIZ_MIN_OPTIONS..QUIZ_MAX_OPTIONS) {
            errors += QuizImportError(index, "options", "COUNT")
            return null
        }
        if (options.any { it.isEmpty() || it.length > QUIZ_MAX_OPTION_CHARS }) {
            errors += QuizImportError(index, "options", "INVALID_TEXT")
            return null
        }
        if (options.map { it.lowercase(Locale.ROOT) }.toSet().size != options.size) {
            errors += QuizImportError(index, "options", "DUPLICATE")
            return null
        }
        return options
    }

    private data class ParsedAnswer(
        val kind: QuizAnswerKind,
        val indices: List<Int>,
    )

    private fun readAnswer(
        item: JSONObject,
        options: List<String>,
        index: Int,
        errors: MutableList<QuizImportError>,
    ): ParsedAnswer? {
        val hasAnswer = item.has("answer") && !item.isNull("answer")
        val hasIndex = item.has("answerIndex") && !item.isNull("answerIndex")
        val hasIndices = item.has("answerIndices") && !item.isNull("answerIndices")
        if (listOf(hasAnswer, hasIndex, hasIndices).count { it } > 1) {
            errors += QuizImportError(index, "answer", "ANSWER_CONFLICT")
            return null
        }
        if (!hasAnswer && !hasIndex && !hasIndices) {
            errors += QuizImportError(index, "answer", "REQUIRED")
            return null
        }

        val declared: QuizAnswerKind?
        if (item.has("type") && !item.isNull("type")) {
            val rawType = item.opt("type")
            if (rawType !is String) {
                errors += QuizImportError(index, "type", "ANSWER_FORMAT")
                return null
            }
            declared = QuizAnswerKind.fromWire(rawType)
            if (declared == null) {
                errors += QuizImportError(index, "type", "ANSWER_FORMAT")
                return null
            }
        } else {
            declared = null
        }

        val parsed = when {
            hasAnswer -> {
                val raw = item.opt("answer") as? String
                if (raw == null || raw.isBlank()) {
                    errors += QuizImportError(index, "answer", "ANSWER_FORMAT")
                    return null
                }
                val resolved = resolveTextAnswer(
                    raw,
                    options.indices.map { ('A' + it) },
                    options,
                )
                if (resolved == null) {
                    errors += QuizImportError(
                        index,
                        "answer",
                        if (isMultipleAnswer(raw)) "ANSWER_FORMAT" else "ANSWER_RANGE",
                    )
                    return null
                }
                resolved
            }
            hasIndex -> {
                val number = numberValue(item.opt("answerIndex"))
                if (number == null || number !in options.indices) {
                    errors += QuizImportError(index, "answer", "ANSWER_RANGE")
                    return null
                }
                ParsedAnswer(QuizAnswerKind.SINGLE, listOf(number))
            }
            else -> {
                val array = item.opt("answerIndices") as? JSONArray
                if (array == null) {
                    errors += QuizImportError(index, "answerIndices", "ANSWER_FORMAT")
                    return null
                }
                val indices = mutableListOf<Int>()
                for (position in 0 until array.length()) {
                    val number = numberValue(array.opt(position))
                    if (number == null) {
                        errors += QuizImportError(index, "answerIndices", "ANSWER_FORMAT")
                        return null
                    }
                    indices += number
                }
                if (indices.distinct().size != indices.size) {
                    errors += QuizImportError(index, "answerIndices", "ANSWER_FORMAT")
                    return null
                }
                if (indices.any { it !in options.indices }) {
                    errors += QuizImportError(index, "answerIndices", "ANSWER_RANGE")
                    return null
                }
                ParsedAnswer(QuizAnswerKind.MULTIPLE, indices.sorted())
            }
        }

        if (declared != null && declared != parsed.kind) {
            errors += QuizImportError(index, "answer", "ANSWER_FORMAT")
            return null
        }
        return if (validateAnswerShape(index, options, parsed, errors)) parsed else null
    }

    private fun validateAnswerShape(
        index: Int,
        options: List<String>,
        answer: ParsedAnswer,
        errors: MutableList<QuizImportError>,
    ): Boolean {
        if (answer.kind == QuizAnswerKind.SINGLE) {
            if (answer.indices.size != 1) {
                errors += QuizImportError(index, "answer", "ANSWER_FORMAT")
                return false
            }
            return true
        }
        if (options.size < 3) {
            errors += QuizImportError(index, "options", "OPTIONS_TOO_FEW")
            return false
        }
        if (answer.indices.size < 2) {
            errors += QuizImportError(index, "answer", "ANSWER_TOO_FEW")
            return false
        }
        if (answer.indices.size >= options.size) {
            errors += QuizImportError(index, "answer", "ANSWER_ALL")
            return false
        }
        return true
    }

    private fun readExplanation(
        item: JSONObject,
        index: Int,
        errors: MutableList<QuizImportError>,
    ): String? {
        if (!item.has("explanation") || item.isNull("explanation")) return ""
        val value = item.opt("explanation")
        if (value !is String) {
            errors += QuizImportError(index, "explanation", "TYPE")
            return null
        }
        val explanation = value.trim()
        if (explanation.length > QUIZ_MAX_EXPLANATION_CHARS) {
            errors += QuizImportError(index, "explanation", "TOO_LONG")
            return null
        }
        return explanation
    }

    private fun readDifficulty(
        item: JSONObject,
        index: Int,
        fallback: String,
        errors: MutableList<QuizImportError>,
    ): String? {
        if (!item.has("difficulty") || item.isNull("difficulty")) return fallback
        val value = item.opt("difficulty")
        if (value !is String) {
            errors += QuizImportError(index, "difficulty", "TYPE")
            return null
        }
        val normalized = normalizeDifficulty(value)
        if (normalized == null) {
            errors += QuizImportError(index, "difficulty", "INVALID_DIFFICULTY")
        }
        return normalized
    }

    /**
     * Exact option text wins before letter parsing, so an option whose text is
     * literally "AB" is still a single-choice match. A single real label maps
     * by its position; two or more labels become a multiple-choice set.
     */
    private fun resolveTextAnswer(
        raw: String,
        letters: List<Char>,
        options: List<String>,
    ): ParsedAnswer? {
        val value = raw.trim()
        val exact = options.indexOfFirst { it == value }
        if (exact >= 0) return ParsedAnswer(QuizAnswerKind.SINGLE, listOf(exact))

        val upper = value.uppercase(Locale.ROOT)
        if (upper.length == 1 && upper[0] in 'A'..'F') {
            val position = letters.indexOf(upper[0])
            if (position >= 0) return ParsedAnswer(QuizAnswerKind.SINGLE, listOf(position))
        }

        val multiple = resolveMultipleLetters(value, letters, options) ?: return null
        return ParsedAnswer(QuizAnswerKind.MULTIPLE, multiple)
    }

    private fun resolveMultipleLetters(
        raw: String,
        letters: List<Char>,
        options: List<String>,
    ): List<Int>? {
        if (!MULTI_ANSWER.matches(raw)) return null
        val selectedLetters = Regex("[A-Fa-f]").findAll(raw)
            .map { it.value.first().uppercaseChar() }
            .toList()
        if (selectedLetters.size < 2 ||
            selectedLetters.distinct().size != selectedLetters.size
        ) {
            return null
        }
        val indices = selectedLetters.map { letters.indexOf(it) }
        if (indices.any { it !in options.indices }) return null
        return indices.sorted()
    }

    private fun isMultipleAnswer(raw: String): Boolean =
        MULTI_ANSWER.matches(raw.trim())

    private fun normalizeDifficulty(raw: String?): String? {
        if (raw == null) return "medium"
        val normalized = raw.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { it in DIFFICULTIES }
    }

    private fun newQuestionId(): String =
        "q-" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun failure(index: Int, field: String, code: String): QuizImportResult =
        QuizImportResult.Failure(listOf(QuizImportError(index, field, code)))

    private fun numberValue(value: Any?): Int? {
        if (value !is Number) return null
        val double = value.toDouble()
        if (double != value.toLong().toDouble()) return null
        return value.toInt()
    }
}
