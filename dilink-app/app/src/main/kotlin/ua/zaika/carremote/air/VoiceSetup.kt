package ua.zaika.carremote.air

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import okhttp3.OkHttpClient
import okhttp3.Request
import ua.zaika.carremote.adb.AdbShell
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gets a Ukrainian voice onto a head unit that has none. DiLink's own TTS (com.byd.autovoice)
 * speaks neither Ukrainian nor English, so spoken alerts need RHVoice — free, offline, open source.
 * We fetch the official build from F-Droid (pinned version + SHA-256), install it through the head
 * unit's own ADB like our updates, then open RHVoice's voice list for the owner to add Ukrainian.
 */
object VoiceSetup {
    const val RHVOICE = "com.github.olga_yakovleva.rhvoice.android"
    private const val APK_URL = "https://f-droid.org/repo/com.github.olga_yakovleva.rhvoice.android_118040.apk"
    private const val APK_SHA256 = "fdb0f7d6f3ec455477a84aa8cc86d1f2d580cb180d69a0c780240975c2efd288"
    private const val APK_SIZE = 15921542L
    private const val REMOTE = "/data/local/tmp/cr_rhvoice.apk"

    enum class State { NO_ENGINE, NO_UKRAINIAN, READY }

    fun installed(context: Context) =
        runCatching { context.packageManager.getPackageInfo(RHVOICE, 0); true }.getOrDefault(false)

    /** Asks RHVoice whether it can speak Ukrainian. Calls back on the main thread. */
    fun check(context: Context, done: (State) -> Unit) {
        if (!installed(context)) { done(State.NO_ENGINE); return }
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext, { status ->
            val ok = status == TextToSpeech.SUCCESS &&
                (tts?.isLanguageAvailable(Locale("uk", "UA")) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
            runCatching { tts?.shutdown() }
            done(if (ok) State.READY else State.NO_UKRAINIAN)
        }, RHVOICE)
    }

    /** Downloads, verifies and installs RHVoice. Blocking — call off the main thread. */
    fun install(context: Context, progress: (String) -> Unit) {
        val file = File(context.cacheDir, "rhvoice.apk")
        progress("Завантаження голосового рушія…")
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(APK_URL).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Не вдалося завантажити RHVoice (HTTP ${r.code})")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var shown = -1
            r.body!!.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (done > APK_SIZE) throw IOException("Файл більший, ніж має бути")
                        val pct = (done * 100 / APK_SIZE).toInt()
                        if (pct != shown && pct % 10 == 0) { shown = pct; progress("Завантаження $pct%…") }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != APK_SIZE || !sha.equals(APK_SHA256, ignoreCase = true)) {
                file.delete(); throw IOException("Файл пошкоджений або підмінений — встановлення скасовано")
            }
        }
        progress("Встановлення…")
        AdbShell(context).use { adb ->
            if (!adb.push(file, REMOTE)) throw IOException("Немає доступу ADB: ${adb.lastError ?: "невідома помилка"}")
            val out = adb.exec("pm install -r $REMOTE; rm -f $REMOTE").orEmpty()
            if (!out.contains("Success")) throw IOException("Не вдалося встановити RHVoice: ${out.trim().take(120)}")
        }
        file.delete()
    }

    /** Opens RHVoice's own list of voices, where the owner adds a Ukrainian one. */
    fun openVoiceList(context: Context) {
        val data = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(RHVOICE)
        val launch = context.packageManager.getLaunchIntentForPackage(RHVOICE)
        val intent = (if (data.resolveActivity(context.packageManager) != null) data else launch) ?: return
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
