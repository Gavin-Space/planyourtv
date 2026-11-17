package app.orchard.tv.policy.web

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardHealth
import org.json.JSONObject

/**
 * Read-only device diagnostics for the web Control page.
 *
 * The permission block is [GuardHealth], the exact reader behind the TV's "Status check", so the
 * phone cannot disagree with the TV about a missing item. Memory and uptime are sampled per
 * request, which lets an expanded card show the current state while a parent watches it.
 *
 * No device identifier is exposed: no serial, Android ID, MAC address or network address.
 */
object ParentDeviceStatus {
    fun snapshot(context: Context): JSONObject {
        val health = GuardHealth.read(context)
        val setup = SetupState.of(health)
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        runCatching { activity?.getMemoryInfo(memory) }
        val pssKb = runCatching {
            activity?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()?.totalPss
        }.getOrNull()
        val runtime = Runtime.getRuntime()
        val dataDir = Environment.getDataDirectory()
        val storage = runCatching { StatFs(dataDir.absolutePath) }.getOrNull()
        val storageTotal = storage?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
        val storageAvailable = storage?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L
        val lowRam = runCatching { activity?.isLowRamDevice == true }.getOrDefault(false)

        return JSONObject()
            .put(
                "status",
                JSONObject()
                    .put("healthy", health.healthy)
                    .put("issues", health.issues)
                    .put("setupComplete", setup.complete)
                    .put("setupMissing", setup.missing)
                    .put("setupUnknown", setup.unknown)
                    .put("homeReady", health.homeReady)
                    .put("guardRunning", health.guardRunning)
                    .put("accessibilityOn", health.accessibilityOn)
                    .put("guardListed", health.guardListed)
                    .put("selfHeal", health.selfHeal)
                    .putNullable("homeDefault", health.homeDefault)
                    .putNullable("revival", health.revival)
                    .putNullable("usageAccess", health.usageAccess)
                    .putNullable("overlay", health.overlay),
            )
            .put(
                "system",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER.orEmpty())
                    .put("model", Build.MODEL.orEmpty())
                    .put("android", Build.VERSION.RELEASE.orEmpty())
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("securityPatch", securityPatch())
                    .putNullable("adbEnabled", adbEnabled(context))
                    .put("kernel", System.getProperty("os.version").orEmpty())
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    .put("cores", runtime.availableProcessors())
                    .put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000L)
                    .put("lowRamDevice", lowRam),
            )
            .put(
                "memory",
                JSONObject()
                    .put("totalBytes", memory.totalMem.coerceAtLeast(0L))
                    .put(
                        "availBytes",
                        memory.availMem.coerceIn(0L, memory.totalMem.coerceAtLeast(0L)),
                    )
                    .put(
                        "usedBytes",
                        (memory.totalMem - memory.availMem).coerceIn(
                            0L,
                            memory.totalMem.coerceAtLeast(0L),
                        ),
                    )
                    .put("thresholdBytes", memory.threshold.coerceAtLeast(0L))
                    .put("lowMemory", memory.lowMemory)
                    .put("appPssBytes", (pssKb?.toLong() ?: 0L) * 1024L)
                    .put(
                        "heapUsedBytes",
                        (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L),
                    )
                    .put("heapMaxBytes", runtime.maxMemory().coerceAtLeast(0L)),
            )
            .put(
                "storage",
                JSONObject()
                    .put("totalBytes", storageTotal)
                    .put("availBytes", storageAvailable),
            )
            .put(
                "app",
                JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE.toLong())
                    .put("processUptimeSeconds", processUptimeSeconds()),
            )
    }

    /**
     * The master "ADB debugging" switch (the one a TV must enable before a LAN build can be
     * installed). It is a plain global setting, readable without a privileged permission; a
     * failed read stays null so the card can say "unknown" instead of guessing "off".
     */
    private fun adbEnabled(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrNull()

    /** API 23 introduced the patch-level string; older builds genuinely have no such field. */
    private fun securityPatch(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.orEmpty()
        } else {
            ""
        }

    /** How long this process has been alive; null where the platform cannot report it. */
    private fun processUptimeSeconds(): Long? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
                .coerceAtLeast(0L) / 1000L
        } else {
            null
        }

    private fun JSONObject.putNullable(name: String, value: Boolean?): JSONObject =
        put(name, value ?: JSONObject.NULL)
}

/**
 * The single "all necessary services are green" state behind the Control page banner.
 *
 * It mirrors the six rows the card and the TV status panel show, but is deliberately stricter
 * than [GuardHealth.healthy] in one place: an unreadable value is not green, so a grey row can
 * never sit under a "setup complete" banner. [missing] counts only items explicitly off; [unknown]
 * counts items that could not be read at all.
 */
internal data class SetupState(
    val complete: Boolean,
    val missing: Int,
    val unknown: Int,
) {
    companion object {
        fun of(health: GuardHealth): SetupState {
            val off = listOf(
                !health.homeReady,
                !health.guardRunning,
                !health.selfHeal,
                health.revival == false,
                health.usageAccess == false,
                health.overlay == false,
            ).count { it }
            val unknown = listOf(health.revival, health.usageAccess, health.overlay)
                .count { it == null }
            return SetupState(complete = off == 0 && unknown == 0, missing = off, unknown = unknown)
        }
    }
}
