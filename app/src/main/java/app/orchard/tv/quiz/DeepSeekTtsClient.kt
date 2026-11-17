package app.orchard.tv.quiz

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek speech synthesis.
 *
 * The endpoint is fixed instead of following the configured model base URL:
 * the same DeepSeek key is used, but a custom OpenAI-compatible gateway must
 * never receive that key for a TTS request it does not support. Errors contain
 * only an HTTP status or a class name; question text, answer text and the key
 * are never put in an exception message or a log line.
 */
internal object DeepSeekTtsClient {
    private const val ENDPOINT = "https://api.deepseek.com/v1/tools/tts"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 30_000
    // A wrong answer now reads the explanation too. The longest stored
    // explanation is 600 Chinese characters, which can produce several minutes
    // of 24 kHz PCM; the old 4 MB cap would cut or reject that reading.
    private const val MAX_AUDIO_BYTES = 16 * 1024 * 1024
    private const val DEFAULT_VOICE = "stella"

    /** Raw DeepSeek speech output: 24 kHz, 16-bit signed PCM, mono. */
    const val SAMPLE_RATE_HZ = 24_000
    const val BYTES_PER_SAMPLE = 2

    /**
     * The speech endpoint has its own parameters. It does not accept the chat
     * model used for quiz generation, so no "model" field is sent here.
     */
    internal fun requestBody(text: String, voice: String = DEFAULT_VOICE): String =
        JSONObject()
            .put("text", text)
            .put("voice_id", voice)
            .put("format", "pcm")
            .toString()

    fun synthesize(apiKey: String, text: String, voice: String = DEFAULT_VOICE): ByteArray {
        require(apiKey.isNotBlank()) { "TTS_KEY_MISSING" }
        require(text.isNotBlank()) { "TTS_TEXT_EMPTY" }
        val body = requestBody(text, voice).toByteArray(Charsets.UTF_8)
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "audio/pcm, application/octet-stream")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("TTS_HTTP_$code")
            if (connection.contentType.orEmpty().contains("json", ignoreCase = true)) {
                throw IllegalStateException("TTS_NOT_AUDIO")
            }
            val stream = connection.inputStream
            return stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    require(out.size() <= MAX_AUDIO_BYTES) { "TTS_RESPONSE_TOO_LARGE" }
                }
                require(out.size() > 0) { "TTS_RESPONSE_EMPTY" }
                require(out.size() % BYTES_PER_SAMPLE == 0) { "TTS_RESPONSE_INVALID" }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
