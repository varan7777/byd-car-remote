package ua.zaika.carremote.car

import android.content.SharedPreferences
import android.os.SystemClock
import ua.zaika.carremote.adb.AdbShell

/**
 * Locked, closed and nobody in the seats for a minute: dim the head unit to minimum and send the
 * daytime running lights off, so a car kept online («Доступність на стоянці», or simply charging)
 * does not stand lit up in the yard. The car itself stays awake and reachable — only the screen
 * and the DRL go quiet. Unlocking, opening a door or sitting in brings the screen back.
 *
 * DiLink does not let an app switch the panel backlight off, so "screen off" is minimum brightness.
 * The previous brightness is kept in prefs, so a restart of the app while dimmed still restores it.
 */
class ParkedQuiet(private val car: Autoservice, private val adb: AdbShell, private val prefs: SharedPreferences) {
    private var since = 0L

    /** Returns a message for the owner when the car has just gone quiet, null otherwise. */
    fun poll(enabled: Boolean, snap: CarSnapshot?): String? {
        val quiet = enabled && snap != null && snap.locked == true && snap.doorsOpen.none { it == true } &&
            nobodyInside() && car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1
        if (!quiet) {
            since = 0
            if (prefs.getBoolean(ACTIVE, false)) restore()
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (since == 0L) since = now
        if (prefs.getBoolean(ACTIVE, false) || now - since < HOLD_MS) return null
        return goQuiet()
    }

    private fun nobodyInside() = Fids.OCCUPANCY.none { car.getInt(Fids.DEV_SEATS, it) == 2 }

    private fun goQuiet(): String {
        val mode = adb.exec("settings get system screen_brightness_mode")?.trim()
        val level = adb.exec("settings get system screen_brightness")?.trim()
        prefs.edit().putBoolean(ACTIVE, true).putString(SAVED_MODE, mode).putString(SAVED_LEVEL, level).apply()
        adb.exec("settings put system screen_brightness_mode 0; settings put system screen_brightness 0")
        val sent = car.write(Fids.DEV_LIGHTS, Fids.DRL_SET, 2)
        Thread.sleep(800)
        val drl = when {
            !sent -> "авто не прийняло команду"
            car.getInt(Fids.DEV_LIGHTS, Fids.DRL_STATE) == 1 -> "команду прийнято, але вогні досі горять"
            else -> "вимкнено"
        }
        return "🌙 Авто зачинене й порожнє: екран притемнено, ходові вогні — $drl. " +
            "Авто лишається на зв'язку; відчините — екран повернеться."
    }

    private fun restore() {
        val mode = prefs.getString(SAVED_MODE, null)?.takeIf { it.matches(Regex("[01]")) } ?: "0"
        val level = prefs.getString(SAVED_LEVEL, null)?.takeIf { it.matches(Regex("\\d{1,3}")) } ?: "128"
        if (adb.exec("settings put system screen_brightness $level; settings put system screen_brightness_mode $mode") != null)
            prefs.edit().putBoolean(ACTIVE, false).apply()
    }

    companion object {
        const val KEY_ENABLED = "parked_quiet"
        private const val ACTIVE = "parked_quiet_active"
        private const val SAVED_MODE = "parked_quiet_mode"
        private const val SAVED_LEVEL = "parked_quiet_level"
        private const val HOLD_MS = 60_000L
    }
}
