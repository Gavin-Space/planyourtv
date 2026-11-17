package app.orchard.tv.parent

/**
 * Converts a page's `accept` attribute into MIME types for the system picker.
 *
 * SAF cannot filter by file extension, so extensions only matter when the page
 * lists nothing else (then every file type is allowed). When the page lists
 * both MIME types and extensions, the MIME types still describe what the page
 * wants and keep a media upload out of a generic file browser. The page that
 * asked for the file validates it.
 */
object MimeChooser {

    fun types(acceptTypes: List<String>?): Array<String> {
        val accept = acceptTypes.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        return accept
            .filter { it.contains('/') && it.length <= MAX_TYPE_CHARS }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }
            ?: EVERYTHING
    }

    private val EVERYTHING = arrayOf("*/*")
    private const val MAX_TYPE_CHARS = 100
}
