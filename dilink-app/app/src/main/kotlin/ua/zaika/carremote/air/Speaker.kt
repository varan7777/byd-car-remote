package ua.zaika.carremote.air

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Ukrainian text-to-speech for spoken air-raid warnings. Speaks on the ALARM stream (same route
 * as the beep, which is reliably audible on the head unit). No-ops silently if the device has no
 * TTS engine or no suitable voice, so the beep + banner still carry the alert. Logs under the
 * "AirSpeaker" tag so language support / playback can be diagnosed from logcat.
 */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS init failed: status=$status")
                return@TextToSpeech
            }
            val engine = tts ?: return@TextToSpeech
            val uk = runCatching { engine.setLanguage(Locale("uk", "UA")) }.getOrNull()
            Log.i(TAG, "engine=${runCatching { engine.defaultEngine }.getOrNull()} setLanguage(uk-UA)=${langName(uk)}")
            if (uk == TextToSpeech.LANG_MISSING_DATA || uk == TextToSpeech.LANG_NOT_SUPPORTED || uk == null) {
                val en = runCatching { engine.setLanguage(Locale.ENGLISH) }.getOrNull()
                Log.i(TAG, "fallback setLanguage(en)=${langName(en)}")
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { Log.i(TAG, "onStart $utteranceId") }
                override fun onDone(utteranceId: String?) { Log.i(TAG, "onDone $utteranceId") }
                override fun onError(utteranceId: String?) { Log.w(TAG, "onError $utteranceId") }
                override fun onError(utteranceId: String?, errorCode: Int) { Log.w(TAG, "onError $utteranceId code=$errorCode") }
            })
            ready = true
            Log.i(TAG, "ready")
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (!ready || engine == null) { Log.w(TAG, "speak() but not ready"); return }
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val r = runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "air") }.getOrNull()
        Log.i(TAG, "speak()->$r text=\"$text\"")
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun langName(code: Int?): String = when (code) {
        TextToSpeech.LANG_AVAILABLE -> "AVAILABLE"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "COUNTRY_AVAILABLE"
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "COUNTRY_VAR_AVAILABLE"
        TextToSpeech.LANG_MISSING_DATA -> "MISSING_DATA"
        TextToSpeech.LANG_NOT_SUPPORTED -> "NOT_SUPPORTED"
        null -> "null"
        else -> "code=$code"
    }

    private companion object { const val TAG = "AirSpeaker" }
}
