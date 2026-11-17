package app.orchard.tv.policy.media

import android.content.Context
import app.orchard.tv.quiz.QuizIntroVideo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Role slots share one preference store. Clearing one role must never clear the
 * other, and legacy `builtin:*` ids must disappear exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyVideoLibraryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        File(context.filesDir, "quiz-intro").deleteRecursively()
    }

    @Test
    fun migrationDropsBundledIdsAndKeepsOneOtherSlot() {
        prefs().edit()
            .putString(PolicyVideoRole.QUIZ_INTRO.prefKey, "builtin:hero")
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, "builtin:forward")
            .commit()

        PolicyVideoLibrary.ensureMigrated(context)

        assertNull(prefs().getString(PolicyVideoRole.QUIZ_INTRO.prefKey, null))
        assertNull(prefs().getString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, null))
        assertEquals(1, prefs().getInt("media_schema", 0))
    }

    @Test
    fun clearingQuizKeepsTheLockChoice() {
        prefs().edit()
            .putString(PolicyVideoRole.QUIZ_INTRO.prefKey, "official:hero")
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, "official:forward")
            .putInt("media_schema", 1)
            .commit()

        val result = PolicyVideoLibrary.select(
            context,
            setOf(PolicyVideoRole.QUIZ_INTRO),
            "",
        )

        assertEquals(PolicyVideoLibrary.EditResult.Ok, result)
        assertNull(prefs().getString(PolicyVideoRole.QUIZ_INTRO.prefKey, null))
        assertEquals(
            "official:forward",
            prefs().getString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, null),
        )
        assertNull(PolicyVideoLibrary.quizSource(context))
        // The file does not exist in this test; the stored reference is what matters here.
        assertEquals(
            "official:forward",
            prefs().getString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, null),
        )
    }

    @Test
    fun clearingBothRolesLeavesNoSelection() {
        prefs().edit()
            .putString(PolicyVideoRole.QUIZ_INTRO.prefKey, "official:hero")
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, "official:hero")
            .putInt("media_schema", 1)
            .commit()

        val result = PolicyVideoLibrary.select(
            context,
            PolicyVideoRole.entries.toSet(),
            "",
        )

        assertEquals(PolicyVideoLibrary.EditResult.Ok, result)
        assertNull(prefs().getString(PolicyVideoRole.QUIZ_INTRO.prefKey, null))
        assertNull(prefs().getString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, null))
    }

    @Test
    fun emptyRoleSetAndUnknownIdsAreRejected() {
        assertEquals(
            PolicyVideoLibrary.EditResult.RoleInvalid,
            PolicyVideoLibrary.select(context, emptySet(), "official:hero"),
        )
        assertEquals(
            PolicyVideoLibrary.EditResult.NotFound,
            PolicyVideoLibrary.select(context, setOf(PolicyVideoRole.QUIZ_INTRO), "official:hero"),
        )
        assertEquals(
            PolicyVideoLibrary.EditResult.NotFound,
            PolicyVideoLibrary.select(context, setOf(PolicyVideoRole.QUIZ_INTRO), "builtin:hero"),
        )
        assertEquals(
            PolicyVideoLibrary.EditResult.NotFound,
            PolicyVideoLibrary.delete(context, "../hero"),
        )
        assertNull(PolicyVideoLibrary.source(context, PolicyVideoRole.QUIZ_INTRO))
    }

    @Test
    fun lockRoleIdReportsAConfiguredWallpaperWithoutOpeningItsFile() {
        prefs().edit()
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, "official:forward")
            .putInt("media_schema", 1)
            .commit()

        assertEquals("official:forward", PolicyVideoLibrary.lockRoleId(context))
    }

    @Test
    fun lockRoleIdIgnoresAnUnconfiguredOrMalformedSlot() {
        prefs().edit().putInt("media_schema", 1).commit()
        assertNull(PolicyVideoLibrary.lockRoleId(context))

        prefs().edit()
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, "../not-a-role")
            .commit()
        assertNull(PolicyVideoLibrary.lockRoleId(context))
    }

    @Test
    fun migrationDoesNotReopenAfterTheFirstRun() {
        prefs().edit()
            .putString(PolicyVideoRole.QUIZ_INTRO.prefKey, "keep-me")
            .putInt("media_schema", 1)
            .commit()

        PolicyVideoLibrary.ensureMigrated(context)

        assertEquals(
            "keep-me",
            prefs().getString(PolicyVideoRole.QUIZ_INTRO.prefKey, null),
        )
    }

    @Test
    fun longLocalVideoIsWallpaperOnlyAndShortVideoCanAlsoBeAQuizIntro() {
        val long = localVideo(durationMs = 600_000, hasAudio = true)
        assertEquals(
            setOf(PolicyVideoRole.LOCK_WALLPAPER),
            PolicyVideoLibrary.capabilityRoles(context, long),
        )

        val short = localVideo(durationMs = 12_000, hasAudio = false)
        assertEquals(
            PolicyVideoRole.entries.toSet(),
            PolicyVideoLibrary.capabilityRoles(context, short),
        )
    }

    @Test
    fun soundDefaultsOnButOnlyResolvesForAnAudioWallpaper() {
        prefs().edit().putInt("media_schema", 1).commit()
        assertTrue(QuizIntroVideo.lockSound(context))
        assertFalse(PolicyVideoLibrary.lockSound(context))
        assertFalse(PolicyVideoLibrary.setLockSound(context, true))
        assertTrue(QuizIntroVideo.lockSound(context))

        val silent = localVideo(durationMs = 600_000, hasAudio = false)
        setLockRole(silent)
        assertFalse(PolicyVideoLibrary.lockSound(context))
        assertFalse(PolicyVideoLibrary.setLockSound(context, true))

        val audio = localVideo(durationMs = 600_000, hasAudio = true)
        setLockRole(audio)
        assertTrue(PolicyVideoLibrary.lockSound(context))
        assertTrue(PolicyVideoLibrary.setLockSound(context, false))
        assertFalse(PolicyVideoLibrary.lockSound(context))
        assertTrue(PolicyVideoLibrary.setLockSound(context, true))
        assertTrue(PolicyVideoLibrary.lockSound(context))
    }

    /** A tiny complete local entry; the library only reads metadata here, never decodes it. */
    private fun localVideo(durationMs: Long, hasAudio: Boolean): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val dir = File(File(context.filesDir, "quiz-intro"), id)
        check(dir.mkdirs())
        File(dir, "video.mp4").writeBytes(byteArrayOf(0, 0, 0, 1))
        File(dir, "info.json").writeText(
            """
            {"kind":"video","name":"$id.mp4","durationMs":$durationMs,
             "width":1920,"height":1080,"hasAudio":$hasAudio,"createdAt":1}
            """.trimIndent(),
        )
        return id
    }

    private fun setLockRole(id: String) {
        prefs().edit()
            .putString(PolicyVideoRole.LOCK_WALLPAPER.prefKey, id)
            .putInt("media_schema", 1)
            .commit()
    }

    private fun prefs() = context.getSharedPreferences("quiz-intro", Context.MODE_PRIVATE)
}
