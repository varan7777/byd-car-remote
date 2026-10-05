package ua.zaika.carremote.air

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Ukrainian text-to-speech for spoken air-raid warnings.
 *
 * The DiLink's own TTS engine (com.byd.autovoice) speaks neither Ukrainian nor English, so we
 * prefer RHVoice — a free, offline, open-source engine with Ukrainian voices — and fall back to
 * the system default if it isn't installed. Speaks on the ALARM stream (same reliably-audible
 * route as the beep). No-ops silently if no usable voice exists, so the beep + banner still
 * carry the alert. Logs under "AirSpeaker".
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        initWith(RHVOICE) // prefer RHVoice (has Ukrainian); falls back to default on failure
    }

    private fun initWith(engine: String?) {
        tts = if (engine != null) {
            TextToSpeech(appContext, { status -> onInit(status, engine) }, engine)
        } else {
            TextToSpeech(appContext) { status -> onInit(status, null) }
        }
    }

    private fun onInit(status: Int, engine: String?) {
        if (status != TextToSpeech.SUCCESS) {
            if (engine != null) {
                Log.w(TAG, "engine $engine unavailable (status=$status); falling back to default")
                runCatching { tts?.shutdown() }
                initWith(null)
            } else {
                Log.w(TAG, "TTS init failed: status=$status")
            }
            return
        }
        val tts = tts ?: return
        val uk = runCatching { tts.setLanguage(Locale("uk", "UA")) }.getOrNull()
        Log.i(TAG, "engine=${runCatching { tts.defaultEngine }.getOrNull()} setLanguage(uk-UA)=${langName(uk)}")
        if ((uk == TextToSpeech.LANG_MISSING_DATA || uk == TextToSpeech.LANG_NOT_SUPPORTED || uk == null) && engine != null) {
            // RHVoice is here but somehow has no Ukrainian voice yet; still try the default engine.
            Log.w(TAG, "no Ukrainian on $engine; falling back to default")
            runCatching { this.tts?.shutdown() }
            initWith(null)
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { Log.i(TAG, "onStart $utteranceId") }
            override fun onDone(utteranceId: String?) { Log.i(TAG, "onDone $utteranceId") }
            override fun onError(utteranceId: String?) { Log.w(TAG, "onError $utteranceId") }
            override fun onError(utteranceId: String?, errorCode: Int) { Log.w(TAG, "onError $utteranceId code=$errorCode") }
        })
        ready = true
        Log.i(TAG, "ready (engine=$engine)")
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

    private companion object {
        const val TAG = "AirSpeaker"
        const val RHVOICE = "com.github.olga_yakovleva.rhvoice.android"
    }
}
