package ua.zaika.carremote.air

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ukrainian spoken air-raid warnings from phrases recorded into the app (assets/voice, Piper voice
 * «tetiana», dataset CC0). DiLink's own TTS speaks no Ukrainian and the head unit won't bind a
 * third-party engine (RHVoice) unless it is already running, so the app carries its own phrases:
 * «Увага», the threat type, «за N кілометрів» (1..100 — warnings are spoken only inside the alarm
 * radius), «Курс на вас», «Повітряна тривога» and every oblast. Plays them back to back on the alarm
 * route at the owner's alarm volume. Logs under "AirSpeaker".
 */
class Speaker(context: Context) {

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val regions: Map<String, String> by lazy {
        runCatching {
            val j = JSONObject(appContext.assets.open("voice/regions.json").bufferedReader().readText())
            j.keys().asSequence().associateWith { j.getString(it) }
        }.getOrDefault(emptyMap())
    }

    /** Clip for an oblast name as NEPTUN gives it ("Київська область", "м. Київ"), or null. */
    fun regionClip(name: String): String? = regions[name.trim()]

    /** Plays [clips] (names under assets/voice) in order at [volume] 0..1. Queued after any in progress. */
    fun speak(clips: List<String>, volume: Float) {
        if (clips.isEmpty()) return
        worker.execute {
            Log.i(TAG, "speak $clips")
            clips.forEach { play(it, volume) }
        }
    }

    private fun play(clip: String, volume: Float) {
        val afd = runCatching { appContext.assets.openFd("voice/$clip.m4a") }.getOrNull()
            ?: return Log.w(TAG, "no clip $clip").let { }
        val done = java.util.concurrent.CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            mp.setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
            mp.start()
            done.await(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "clip $clip failed", e)
        } finally {
            mp.release()
        }
    }

    fun shutdown() = worker.shutdown()

    private companion object { const val TAG = "AirSpeaker" }
}
