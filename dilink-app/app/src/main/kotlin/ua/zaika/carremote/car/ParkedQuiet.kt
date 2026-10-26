package ua.zaika.carremote.car

import android.content.SharedPreferences
import android.os.SystemClock
import ua.zaika.carremote.adb.AdbShell

/**
 * Locked, closed and nobody in the seats for a minute: switch the head unit's backlight off and send
 * the daytime running lights off, so a car kept online («Доступність на стоянці», or simply charging)
 * does not stand lit up in the yard. The car itself stays awake and reachable — only the screen
 * and the DRL go quiet. Unlocking, opening a door or sitting in brings the screen back.
 *
 * The screen goes off the way DiLink's own «screen off» button does it: SystemUI calls BYD's
 * IDisplayManager.turnBacklightOff (transaction 45; 46 = on). A touch on the panel lights it again.
 * Android's screen_brightness setting is ignored by DiLink, so it is not used.
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
        legacyRestore()
        prefs.edit().putBoolean(ACTIVE, true).apply()
        adb.exec("service call display $TX_BACKLIGHT_OFF i32 0 i64 0")
        Thread.sleep(500)
        val screenOff = backlightOff()
        val sent = car.write(Fids.DEV_LIGHTS, Fids.DRL_SET, 2)
        Thread.sleep(800)
        val drl = when {
            !sent -> "авто не прийняло команду"
            car.getInt(Fids.DEV_LIGHTS, Fids.DRL_STATE) == 1 -> "команду прийнято, але вогні досі горять"
            else -> "вимкнено"
        }
        val screen = if (screenOff) "екран вимкнено" else "екран вимкнути не вдалося"
        return "🌙 Авто зачинене й порожнє: $screen, ходові вогні — $drl. " +
            "Авто лишається на зв'язку; відчините — екран увімкнеться."
    }

    /** True when DiLink reports its one-key backlight-off state. */
    private fun backlightOff() =
        adb.exec("dumpsys display | grep -m1 mOneKeyBacklight")?.contains("mOneKeyBacklight=true") == true

    private fun restore() {
        legacyRestore()
        adb.exec("service call display $TX_BACKLIGHT_ON i32 0 i64 0") ?: return
        prefs.edit().putBoolean(ACTIVE, false).apply()
    }

    /** Undo the earlier build's minimum-brightness "dim", once. */
    private fun legacyRestore() {
        val level = prefs.getString(SAVED_LEVEL, null)?.takeIf { it.matches(Regex("\\d{1,3}")) } ?: return
        val mode = prefs.getString(SAVED_MODE, null)?.takeIf { it.matches(Regex("[01]")) } ?: "0"
        if (adb.exec("settings put system screen_brightness $level; settings put system screen_brightness_mode $mode") != null)
            prefs.edit().remove(SAVED_LEVEL).remove(SAVED_MODE).apply()
    }

    companion object {
        const val KEY_ENABLED = "parked_quiet"
        private const val ACTIVE = "parked_quiet_active"
        private const val SAVED_MODE = "parked_quiet_mode"
        private const val SAVED_LEVEL = "parked_quiet_level"
        private const val HOLD_MS = 60_000L
        private const val TX_BACKLIGHT_OFF = 45
        private const val TX_BACKLIGHT_ON = 46
    }
}
