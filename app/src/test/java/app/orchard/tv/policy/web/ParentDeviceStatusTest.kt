package app.orchard.tv.policy.web

import android.app.Application
import app.orchard.tv.guard.GuardHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Control page's device card is a projection, not a second health check: its permission
 * block must keep using every [app.orchard.tv.guard.GuardHealth] field the TV status panel
 * shows, and it must never grow a device identifier.
 */
@RunWith(RobolectricTestRunner::class)
class ParentDeviceStatusTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
    }

    @Test
    fun thePermissionBlockCarriesEveryStatusCheckField() {
        val status = ParentDeviceStatus.snapshot(app).getJSONObject("status")
        listOf(
            "healthy",
            "issues",
            "setupComplete",
            "setupMissing",
            "setupUnknown",
            "homeDefault",
            "homeReady",
            "guardRunning",
            "accessibilityOn",
            "guardListed",
            "selfHeal",
            "revival",
            "usageAccess",
            "overlay",
        ).forEach { assertTrue("missing $it", status.has(it)) }
    }

    @Test
    fun systemAndMemorySamplesAreWellFormed() {
        val device = ParentDeviceStatus.snapshot(app)
        val system = device.getJSONObject("system")
        assertTrue(system.has("manufacturer"))
        assertTrue(system.has("model"))
        assertTrue(system.getString("android").isNotEmpty())
        assertTrue(system.getInt("sdk") > 0)
        assertTrue(system.getInt("cores") >= 1)
        assertTrue(system.getLong("uptimeSeconds") >= 0L)
        // ADB may be null when the platform refuses the read, but the key must always exist.
        assertTrue(system.has("adbEnabled"))

        val memory = device.getJSONObject("memory")
        val total = memory.getLong("totalBytes")
        val available = memory.getLong("availBytes")
        val used = memory.getLong("usedBytes")
        assertTrue(total >= 0L)
        assertTrue(available in 0L..total)
        assertEquals((total - available).coerceAtLeast(0L), used)
        assertTrue(memory.getLong("appPssBytes") >= 0L)
        assertTrue(memory.getLong("heapMaxBytes") >= 0L)

        val storage = device.getJSONObject("storage")
        assertTrue(storage.getLong("totalBytes") >= 0L)
        assertTrue(storage.getLong("availBytes") >= 0L)

        val appInfo = device.getJSONObject("app")
        assertTrue(appInfo.getString("versionName").isNotEmpty())
    }

    @Test
    fun setupIsCompleteOnlyWhenEveryRequiredServiceIsExplicitlyOn() {
        assertEquals(
            SetupState(complete = true, missing = 0, unknown = 0),
            SetupState.of(health()),
        )

        val guardOff = SetupState.of(health(guardRunning = false))
        assertFalse(guardOff.complete)
        assertEquals(1, guardOff.missing)
        assertEquals(0, guardOff.unknown)

        // The running guard still gets Home back, so the default-home row is green as well.
        assertTrue(SetupState.of(health(homeDefault = false)).complete)

        // A grey row must never sit under a "setup complete" banner.
        val overlayUnknown = SetupState.of(health(overlay = null))
        assertFalse(overlayUnknown.complete)
        assertEquals(0, overlayUnknown.missing)
        assertEquals(1, overlayUnknown.unknown)
    }

    @Test
    fun noDeviceIdentifierIsExposed() {
        val device = ParentDeviceStatus.snapshot(app)
        val forbidden = setOf(
            "serial",
            "androidId",
            "macAddress",
            "ipAddress",
            "fingerprint",
            "deviceId",
        )
        listOf("status", "system", "memory", "storage", "app").forEach { section ->
            val keys = device.getJSONObject(section).keys().asSequence().toSet()
            forbidden.forEach { assertFalse("$section exposes $it", keys.contains(it)) }
        }
    }

    private fun health(
        homeDefault: Boolean? = true,
        guardRunning: Boolean = true,
        selfHeal: Boolean = true,
        revival: Boolean? = true,
        usageAccess: Boolean? = true,
        overlay: Boolean? = true,
    ) = GuardHealth(
        homeDefault = homeDefault,
        accessibilityOn = true,
        guardListed = true,
        guardRunning = guardRunning,
        selfHeal = selfHeal,
        revival = revival,
        usageAccess = usageAccess,
        overlay = overlay,
    )
}
