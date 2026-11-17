package app.orchard.tv.parent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One TV or projector bookmarked by the phone shell.
 *
 * [key] is the token in the TV's page path, not its IP or port. DHCP can move a
 * TV to a new address; the token survives, so a re-scan updates this row instead
 * of creating a duplicate.
 */
internal data class PairedDevice(
    val key: String,
    val name: String,
    val url: String,
    val lastUsedAt: Long,
)

internal data class DeviceSnapshot(
    val devices: List<PairedDevice>,
    val currentKey: String?,
) {
    /** The selected device, falling back to the most recently used one. */
    fun current(): PairedDevice? = devices.firstOrNull { it.key == currentKey }
        ?: devices.maxByOrNull { it.lastUsedAt }

    /** Persists the fallback above, so the stored current key is always real. */
    fun normalized(): DeviceSnapshot = copy(currentKey = current()?.key)
}

/**
 * Phone-local device bookkeeping. There is no account and no cloud: the list is
 * a JSON string in SharedPreferences, and each TV's login state stays in that
 * origin's WebView storage.
 */
internal object ParentDevices {
    const val MAX_NAME_LENGTH = 24
    private const val STORAGE_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_CURRENT = "current"
    private const val KEY_DEVICES = "devices"
    private const val KEY_KEY = "key"
    private const val KEY_NAME = "name"
    private const val KEY_URL = "url"
    private const val KEY_LAST_USED = "lastUsed"

    /**
     * Reads the device list and migrates the single address saved by shell
     * 1.1 and older. A malformed list falls back to the legacy address rather
     * than discarding the user's only link to the TV.
     */
    fun load(stored: String?, legacyUrl: String?, defaultName: String): DeviceSnapshot {
        val decoded = decode(stored)
        if (decoded.devices.isNotEmpty()) return decoded.normalized()

        val migrated = legacyUrl?.let { raw ->
            val url = QuizUrl.normalize(raw) ?: return@let null
            val key = QuizUrl.deviceKey(url) ?: return@let null
            PairedDevice(
                key = key,
                name = normalizeName(defaultName) ?: key,
                url = url,
                lastUsedAt = System.currentTimeMillis(),
            )
        } ?: return DeviceSnapshot(emptyList(), null)
        return DeviceSnapshot(listOf(migrated), migrated.key)
    }

    fun decode(stored: String?): DeviceSnapshot {
        if (stored.isNullOrBlank()) return DeviceSnapshot(emptyList(), null)
        val root = runCatching { JSONObject(stored) }.getOrNull()
            ?: return DeviceSnapshot(emptyList(), null)
        val array = root.optJSONArray(KEY_DEVICES) ?: JSONArray()
        val byKey = LinkedHashMap<String, PairedDevice>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val rawUrl = item.optString(KEY_URL).takeIf { it.isNotBlank() } ?: continue
            val url = QuizUrl.normalize(rawUrl) ?: continue
            val key = QuizUrl.deviceKey(url) ?: continue
            val name = normalizeName(item.optString(KEY_NAME)) ?: key
            val lastUsed = item.optLong(KEY_LAST_USED, 0L).coerceAtLeast(0L)
            val device = PairedDevice(key, name, url, lastUsed)
            val existing = byKey[key]
            if (existing == null || lastUsed >= existing.lastUsedAt) byKey[key] = device
        }
        val devices = byKey.values.toList()
        if (devices.isEmpty()) return DeviceSnapshot(emptyList(), null)
        val storedCurrent = root.optString(KEY_CURRENT).takeIf { it.isNotBlank() }
        val current = storedCurrent?.takeIf { key -> devices.any { it.key == key } }
            ?: devices.maxByOrNull { it.lastUsedAt }?.key
        return DeviceSnapshot(devices, current)
    }

    fun encode(snapshot: DeviceSnapshot): String {
        val devices = JSONArray()
        snapshot.devices.forEach { device ->
            devices.put(
                JSONObject()
                    .put(KEY_KEY, device.key)
                    .put(KEY_NAME, device.name)
                    .put(KEY_URL, device.url)
                    .put(KEY_LAST_USED, device.lastUsedAt),
            )
        }
        return JSONObject()
            .put(KEY_VERSION, STORAGE_VERSION)
            .put(KEY_CURRENT, snapshot.currentKey ?: JSONObject.NULL)
            .put(KEY_DEVICES, devices)
            .toString()
    }

    /** Trims and collapses whitespace; returns null for an empty or blank name. */
    fun normalizeName(raw: String?): String? {
        val text = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (text.isEmpty()) return null
        val clipped = if (text.codePointCount(0, text.length) <= MAX_NAME_LENGTH) {
            text
        } else {
            text.substring(0, text.offsetByCodePoints(0, MAX_NAME_LENGTH))
        }
        return clipped.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Adds or moves [device] to the end of the list. The same token is the same
     * TV at a new address; a different token on the same origin is the same TV
     * after its app data was reset, so the stale row is replaced rather than
     * left as an unreachable duplicate.
     */
    fun upsert(devices: List<PairedDevice>, device: PairedDevice): List<PairedDevice> {
        val kept = devices.filterNot {
            it.key == device.key || QuizUrl.sameOrigin(it.url, device.url)
        }
        return kept + device
    }

    /** Current device first, then most recently used. */
    fun sorted(devices: List<PairedDevice>, currentKey: String?): List<PairedDevice> =
        devices.sortedWith(
            compareByDescending<PairedDevice> { it.key == currentKey }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.name },
        )
}
