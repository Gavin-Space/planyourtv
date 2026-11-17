package app.orchard.tv.quiz

import org.json.JSONObject

/**
 * Parent choices for the DeepSeek voice reading.
 *
 * [initialized] is false until the parent saves the AI settings on a build that
 * understands speech. An absent record therefore keeps the switch looking "on"
 * in the form without starting to upload quiz text after an update.
 */
data class QuizSpeechSettings(
    val initialized: Boolean = false,
    val enabled: Boolean = true,
    val readPrompt: Boolean = true,
    val readOptions: Boolean = true,
    val readAnswer: Boolean = true,
) {
    val hasContent: Boolean get() = readPrompt || readOptions || readAnswer

    /** Runtime gate: the parent enabled at least one part and saved the setting. */
    val active: Boolean get() = initialized && enabled && hasContent

    fun toJson(): JSONObject = JSONObject()
        .put("initialized", initialized)
        .put("enabled", enabled)
        .put("readPrompt", readPrompt)
        .put("readOptions", readOptions)
        .put("readAnswer", readAnswer)

    companion object {
        fun fromJson(raw: JSONObject?): QuizSpeechSettings {
            if (raw == null) return QuizSpeechSettings()
            return QuizSpeechSettings(
                initialized = raw.optBoolean("initialized", false),
                enabled = raw.optBoolean("enabled", true),
                readPrompt = raw.optBoolean("readPrompt", true),
                readOptions = raw.optBoolean("readOptions", true),
                readAnswer = raw.optBoolean("readAnswer", true),
            )
        }
    }
}
