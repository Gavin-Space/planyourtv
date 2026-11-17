package app.orchard.tv.parent

import android.content.Context

/**
 * The TV addresses this phone has paired with. The admin session and its expiry
 * stay in each origin's WebView storage, exactly as they would in a browser.
 * Only bookmarks and their names live here.
 */
internal class ParentPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): DeviceSnapshot {
        val stored = prefs.getString(KEY_DEVICES, null)
        val legacy = prefs.getString(KEY_URL, null)
        val snapshot = ParentDevices.load(
            stored = stored,
            legacyUrl = legacy,
            defaultName = appContext.getString(R.string.device_default_name, 1),
        ).normalized()
        if (stored.isNullOrBlank() && snapshot.devices.isNotEmpty()) save(snapshot)
        return snapshot
    }

    fun save(snapshot: DeviceSnapshot) {
        prefs.edit()
            .putString(KEY_DEVICES, ParentDevices.encode(snapshot))
            .remove(KEY_URL)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val NAME = "parent_shell"
        const val KEY_DEVICES = "devices_v1"

        /** Written by shell 1.1 and older; read once, then removed by [save]. */
        const val KEY_URL = "quiz_url"
    }
}
