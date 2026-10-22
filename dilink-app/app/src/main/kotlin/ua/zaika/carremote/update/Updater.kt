package ua.zaika.carremote.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ua.zaika.carremote.BuildConfig
import ua.zaika.carremote.adb.AdbShell
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * App updates published from the varan.team admin. The app only checks on its own; installing
 * happens when the owner taps «Оновити». The APK is verified three times: size and SHA-256 from
 * the hub, then the signing certificate must match this app's, and finally Android itself refuses
 * an update signed by anyone else. Installation goes through the head unit's own ADB.
 */
object Updater {
    data class Release(val code: Int, val name: String, val url: String, val sha256: String, val size: Long, val notes: String)

    private const val LATEST = "https://varan.team/api/app/latest"
    private const val PREF = "upd_release"
    private const val REMOTE = "/data/local/tmp/cr_update.apk"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).build()

    private fun parse(j: JSONObject?): Release? = j?.let {
        val url = it.optString("url")
        if (!url.startsWith("https://varan.team/byd/")) return null
        Release(it.optInt("versionCode"), it.optString("versionName"), url, it.optString("sha256"), it.optLong("size"), it.optString("notes"))
    }

    /** The last release the hub told us about, if it is newer than this build. */
    fun available(prefs: SharedPreferences): Release? =
        runCatching { parse(JSONObject(prefs.getString(PREF, null) ?: return null)) }.getOrNull()
            ?.takeIf { it.code > BuildConfig.VERSION_CODE }

    /** Asks the hub; remembers the answer for the settings screen. Blocking — call off the main thread. */
    fun check(prefs: SharedPreferences): Release? {
        client.newCall(Request.Builder().url(LATEST).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body?.string().orEmpty().trim()
            if (body == "null" || body.isEmpty()) prefs.edit().remove(PREF).apply()
            else prefs.edit().putString(PREF, JSONObject(body).toString()).apply()
        }
        return available(prefs)
    }

    /** Downloads, verifies and hands the APK to the package manager. The app restarts by itself. */
    fun install(context: Context, release: Release, progress: (String) -> Unit) {
        val file = File(context.cacheDir, "update.apk")
        progress("Завантаження…")
        client.newCall(Request.Builder().url(release.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Не вдалося завантажити оновлення")
            val total = release.size.coerceAtLeast(1)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var shown = -1
            r.body!!.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (done > release.size) throw IOException("Файл більший, ніж має бути")
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown && pct % 5 == 0) { shown = pct; progress("Завантаження $pct%…") }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != release.size || !sha.equals(release.sha256, ignoreCase = true)) {
                file.delete(); throw IOException("Файл пошкоджений або підмінений — оновлення скасовано")
            }
        }
        progress("Перевірка підпису…")
        if (!sameSigner(context, file)) { file.delete(); throw IOException("Підпис не збігається — оновлення скасовано") }
        progress("Встановлення… Додаток перезапуститься сам")
        AdbShell(context).use { adb ->
            if (!adb.push(file, REMOTE)) throw IOException("Немає доступу ADB: ${adb.lastError ?: "невідома помилка"}")
            // Detached: installing replaces (and kills) this very process, the shell then starts it again.
            adb.exec("(setsid sh -c 'pm install -r $REMOTE >/data/local/tmp/cr_update.log 2>&1; rm -f $REMOTE; " +
                "am start -n ${context.packageName}/.BootstrapActivity >/dev/null 2>&1 || am start -n ${context.packageName}/.MainActivity' " +
                "</dev/null >/dev/null 2>&1 &)")
        }
        file.delete()
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flag = if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val theirs = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
        if (theirs.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flag)
        fun certs(info: android.content.pm.PackageInfo) =
            if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
            else info.signatures?.map { it.toCharsString() }?.toSet()
        val a = certs(theirs); val b = certs(mine)
        return !a.isNullOrEmpty() && a == b
    }
}
