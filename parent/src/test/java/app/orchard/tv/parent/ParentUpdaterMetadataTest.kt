package app.orchard.tv.parent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentUpdaterMetadataTest {
    private val sha = "a".repeat(64)

    @Test
    fun readsTheParentObjectBesideTvReleases() {
        val release = parseParentReleaseMetadata(
            """
            {"releases":[{"v":"1.97.001","zh":["x"],"en":["x"]}],
             "parent":{"versionCode":6,"versionName":"1.2.0","sha256":"$sha","sizeBytes":12345}}
            """.trimIndent(),
        )

        requireNotNull(release)
        assertEquals(6, release.versionCode)
        assertEquals("1.2.0", release.versionName)
        assertEquals(sha, release.sha256)
        assertEquals(12345L, release.sizeBytes)
    }

    @Test
    fun lowercasesTheChecksum() {
        val release = parseParentReleaseMetadata(
            """{"parent":{"versionCode":6,"versionName":"1.2.0","sha256":"${"A".repeat(64)}"}}""",
        )

        assertEquals(sha, release?.sha256)
    }

    @Test
    fun toleratesMissingSize() {
        val release = parseParentReleaseMetadata(
            """{"parent":{"versionCode":6,"versionName":"1.2.0","sha256":"$sha","sizeBytes":-1}}""",
        )

        assertEquals(0L, release?.sizeBytes)
    }

    @Test
    fun rejectsMetadataWithoutAParentObject() {
        assertNull(parseParentReleaseMetadata("""{"releases":[]}"""))
    }

    @Test
    fun rejectsIncompleteOrMalformedMetadata() {
        assertNull(parseParentReleaseMetadata("not json"))
        assertNull(parseParentReleaseMetadata("""{"parent":{}}"""))
        assertNull(
            parseParentReleaseMetadata(
                """{"parent":{"versionCode":6,"versionName":"1.2.0","sha256":"abc"}}""",
            ),
        )
        assertNull(
            parseParentReleaseMetadata(
                """{"parent":{"versionCode":0,"versionName":"1.2.0","sha256":"$sha"}}""",
            ),
        )
        assertNull(
            parseParentReleaseMetadata(
                """{"parent":{"versionCode":6,"versionName":" ","sha256":"$sha"}}""",
            ),
        )
    }
}
