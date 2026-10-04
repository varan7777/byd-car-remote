package ua.zaika.carremote.air

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Minimal Ukrainian text-to-speech for spoken air-raid warnings. Silently no-ops if the head
 * unit has no TTS engine or no Ukrainian voice, so the beep + banner still carry the alert.
 */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val engine = tts ?: return@TextToSpeech
            val r = runCatching { engine.setLanguage(Locale("uk", "UA")) }.getOrNull()
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                runCatching { engine.language = Locale.getDefault() } // better than nothing
            }
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready) runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "air") }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
