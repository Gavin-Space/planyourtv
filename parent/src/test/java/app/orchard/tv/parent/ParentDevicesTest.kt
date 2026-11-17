package app.orchard.tv.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentDevicesTest {
    private val livingRoom = "http://192.0.2.10:8790/abcd2345/quiz"
    private val study = "http://192.0.2.11:8790/efgh6789/quiz"

    private fun device(
        key: String = "abcd2345",
        name: String = "Living room",
        url: String = livingRoom,
        lastUsedAt: Long = 1_000L,
    ) = PairedDevice(key, name, url, lastUsedAt)

    @Test
    fun migratesTheLegacySingleAddress() {
        val snapshot = ParentDevices.load(null, livingRoom, "TV 1")

        assertEquals(1, snapshot.devices.size)
        assertEquals("abcd2345", snapshot.devices.single().key)
        assertEquals("TV 1", snapshot.devices.single().name)
        assertEquals(livingRoom, snapshot.devices.single().url)
        assertEquals("abcd2345", snapshot.currentKey)
        assertTrue(snapshot.devices.single().lastUsedAt > 0L)
    }

    @Test
    fun keepsTheLegacyParentFragment() {
        val snapshot = ParentDevices.load(
            stored = null,
            legacyUrl = "http://192.0.2.10:8790/abcd2345/quiz#parent",
            defaultName = "TV 1",
        )

        assertEquals("http://192.0.2.10:8790/abcd2345/quiz#parent", snapshot.devices.single().url)
    }

    @Test
    fun ignoresABrokenStoredListAndFallsBackToLegacy() {
        val snapshot = ParentDevices.load("{not json", livingRoom, "TV 1")

        assertEquals(livingRoom, snapshot.devices.single().url)
    }

    @Test
    fun roundTripsTheList() {
        val original = DeviceSnapshot(
            devices = listOf(
                device(name = "Living room", lastUsedAt = 2_000L),
                device(key = "efgh6789", name = "Study", url = study, lastUsedAt = 1_000L),
            ),
            currentKey = "efgh6789",
        )

        val decoded = ParentDevices.decode(ParentDevices.encode(original))

        assertEquals(original.devices, decoded.devices)
        assertEquals("efgh6789", decoded.currentKey)
    }

    @Test
    fun repairsAMissingCurrentDevice() {
        val stored = ParentDevices.encode(
            DeviceSnapshot(
                devices = listOf(
                    device(lastUsedAt = 1_000L),
                    device(key = "efgh6789", name = "Study", url = study, lastUsedAt = 5_000L),
                ),
                currentKey = "gone0000",
            ),
        )

        val decoded = ParentDevices.decode(stored)

        assertEquals("efgh6789", decoded.currentKey)
    }

    @Test
    fun dropsRowsThatAreNotParentCenterAddresses() {
        val stored = """
            {"version":1,"current":"abcd2345","devices":[
              {"key":"abcd2345","name":"Living room","url":"$livingRoom","lastUsed":10},
              {"key":"bad","name":"Broken","url":"http://192.0.2.10:8790/","lastUsed":20}
            ]}
        """.trimIndent()

        val decoded = ParentDevices.decode(stored)

        assertEquals(1, decoded.devices.size)
        assertEquals("abcd2345", decoded.devices.single().key)
    }

    @Test
    fun keepsOnlyTheNewestCopyOfAToken() {
        val stored = """
            {"version":1,"current":"abcd2345","devices":[
              {"key":"abcd2345","name":"Old","url":"http://192.0.2.10:8790/abcd2345/quiz","lastUsed":10},
              {"key":"abcd2345","name":"New","url":"http://192.0.2.20:8790/abcd2345/quiz","lastUsed":20}
            ]}
        """.trimIndent()

        val decoded = ParentDevices.decode(stored)

        assertEquals(1, decoded.devices.size)
        assertEquals("New", decoded.devices.single().name)
        assertEquals("http://192.0.2.20:8790/abcd2345/quiz", decoded.devices.single().url)
    }

    @Test
    fun upsertUpdatesTheSameTvAfterAnIpChange() {
        val existing = listOf(device(lastUsedAt = 1_000L))
        val moved = device(url = "http://192.0.2.20:8790/abcd2345/quiz", lastUsedAt = 2_000L)

        val result = ParentDevices.upsert(existing, moved)

        assertEquals(1, result.size)
        assertEquals(moved.url, result.single().url)
        assertEquals(moved.lastUsedAt, result.single().lastUsedAt)
    }

    @Test
    fun upsertReplacesAStaleTokenOnTheSameOrigin() {
        val existing = listOf(device(lastUsedAt = 1_000L))
        val reset = device(key = "wxyz9876", name = "Study", url = study, lastUsedAt = 2_000L)
        val sameOriginReset = reset.copy(url = "http://192.0.2.10:8790/wxyz9876/quiz")

        val result = ParentDevices.upsert(existing, sameOriginReset)

        assertEquals(1, result.size)
        assertEquals("wxyz9876", result.single().key)
    }

    @Test
    fun upsertAddsADifferentTv() {
        val result = ParentDevices.upsert(
            listOf(device()),
            device(key = "efgh6789", name = "Study", url = study, lastUsedAt = 2_000L),
        )

        assertEquals(setOf("abcd2345", "efgh6789"), result.map { it.key }.toSet())
    }

    @Test
    fun normalizesNames() {
        assertEquals("Kids TV", ParentDevices.normalizeName("  Kids \n TV  "))
        assertEquals("Привет", ParentDevices.normalizeName("Привет"))
        assertNull(ParentDevices.normalizeName(null))
        assertNull(ParentDevices.normalizeName("   "))
        assertEquals(
            ParentDevices.MAX_NAME_LENGTH,
            ParentDevices.normalizeName("x".repeat(80))!!.length,
        )
    }

    @Test
    fun sortsTheCurrentDeviceFirst() {
        val older = device(lastUsedAt = 1_000L)
        val newer = device(key = "efgh6789", name = "Study", url = study, lastUsedAt = 9_000L)

        val result = ParentDevices.sorted(listOf(newer, older), currentKey = "abcd2345")

        assertEquals(listOf("abcd2345", "efgh6789"), result.map { it.key })
    }
}
