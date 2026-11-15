package com.planyourtv.policy.quiz

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizLevelTest {
    private fun scope(
        subject: String = "CHINESE",
        textbook: String = "BUBIAN",
        scopeType: String = "MIDTERM",
    ): QuizAnswerScope = QuizAnswerScope.create(
        grade = 4,
        semester = 1,
        subject = subject,
        textbook = textbook,
        scopeType = scopeType,
        unitNo = null,
        difficulty = null,
    )!!

    private fun level(
        id: String,
        name: String = "",
        scope: QuizAnswerScope = scope(),
    ): JSONObject = QuizLevel(id, name, scope).encode()

    @Test
    fun parseListKeepsOrderAndRoundTrips() {
        val levels = listOf(
            QuizLevel("lvl-a", "语文期中", scope()),
            QuizLevel("lvl-b", "", scope(subject = "MATH", textbook = "RENJIAO")),
        )
        val parsed = QuizLevel.parseList(QuizLevel.encodeList(levels))
        assertNotNull(parsed)
        assertEquals(listOf("lvl-a", "lvl-b"), parsed!!.map { it.id })
        assertEquals("语文期中", parsed[0].name)
        assertEquals("", parsed[1].name)
        assertEquals(scope(subject = "MATH", textbook = "RENJIAO"), parsed[1].scope)
    }

    @Test
    fun parseListAllowsAnyNumberOfLevels() {
        assertEquals(emptyList<QuizLevel>(), QuizLevel.parseList(JSONArray()))
        assertNull(QuizLevel.parseList(null))

        // There is no fixed cap: build far more than the old six levels, each with a
        // distinct scope (duplicate scopes and ids are still rejected).
        val many = JSONArray()
        var index = 0
        for (grade in 1..2) {
            for (semester in 1..2) {
                for (scopeType in listOf("MIDTERM", "FINAL", "REVIEW")) {
                    for (difficulty in listOf(null, "EASY", "MEDIUM", "HARD")) {
                        val s = QuizAnswerScope.create(
                            grade = grade,
                            semester = semester,
                            subject = "CHINESE",
                            textbook = "BUBIAN",
                            scopeType = scopeType,
                            unitNo = null,
                            difficulty = difficulty,
                        )!!
                        many.put(level("lvl-$index", scope = s))
                        index++
                    }
                }
            }
        }
        assertEquals(48, index)
        val parsed = QuizLevel.parseList(many)
        assertNotNull(parsed)
        assertEquals(48, parsed!!.size)
        assertEquals("lvl-0", parsed[0].id)
        assertEquals("lvl-47", parsed[47].id)
    }

    @Test
    fun parseListRejectsDuplicateScopesAndIds() {
        val sameScope = JSONArray()
            .put(level("lvl-a"))
            .put(level("lvl-b"))
        assertNull(QuizLevel.parseList(sameScope))

        val sameId = JSONArray()
            .put(level("lvl-a"))
            .put(level("lvl-a", scope = scope(subject = "MATH")))
        assertNull(QuizLevel.parseList(sameId))
    }

    @Test
    fun parseAssignsMissingIdsButRejectsMalformedOnes() {
        val missingId = JSONObject()
            .put("name", "关卡")
            .put("scope", scope().encode())
        val parsed = QuizLevel.parse(missingId)
        assertNotNull(parsed)
        assertTrue(parsed!!.id.isNotBlank())

        val malformed = JSONObject()
            .put("id", "bad id!")
            .put("scope", scope().encode())
        assertNull(QuizLevel.parse(malformed))
    }

    @Test
    fun parseRejectsAnInvalidScopeInsideTheList() {
        val invalid = JSONObject()
            .put("id", "lvl-a")
            .put("scope", JSONObject().put("grade", 4))
        val array = JSONArray().put(invalid)
        assertNull(QuizLevel.parseList(array))
    }

    @Test
    fun namesAreTrimmedAndCappedAtTenCodePoints() {
        assertEquals("语文", QuizLevel.normalizeName("  语文  "))
        assertEquals("一二三四五六七八九十", QuizLevel.normalizeName("一二三四五六七八九十"))
        assertNull(QuizLevel.normalizeName("一二三四五六七八九十一"))
        assertEquals("😀😀😀😀😀😀😀😀😀😀", QuizLevel.normalizeName("😀😀😀😀😀😀😀😀😀😀"))
        assertNull(QuizLevel.normalizeName("😀😀😀😀😀😀😀😀😀😀😀"))
    }

    @Test
    fun createRejectsBadNamesAndIds() {
        assertNull(QuizLevel.create(scope(), name = "一二三四五六七八九十一"))
        assertNull(QuizLevel.create(scope(), id = "bad id"))
        assertNotNull(QuizLevel.create(scope()))
    }

    @Test
    fun emptyNamesAreAllowed() {
        val parsed = QuizLevel.parse(
            JSONObject()
                .put("id", "lvl-a")
                .put("name", "   ")
                .put("scope", scope().encode()),
        )
        assertEquals("", parsed!!.name)
    }
}
