package ua.zaika.carremote.car

import android.content.SharedPreferences
import ua.zaika.carremote.adb.AdbShell

/**
 * «Тихий режим», on demand only: when the owner sends it from Telegram, switch the head unit's
 * backlight off and send the daytime running lights off, so a car kept online («Доступність на
 * стоянці», or simply charging) does not stand lit up in the yard. It never starts by itself from
 * the car's state — parking after a drive must not black the screen out. It ends on command, or
 * by itself as soon as the car is unlocked, a door opens, someone sits in or a gear is engaged.
 *
 * The screen goes off the way DiLink's own «screen off» button does it: SystemUI calls BYD's
 * IDisplayManager.turnBacklightOff (transaction 45; 46 = on). A touch on the panel lights it again.
 * Android's screen_brightness setting is ignored by DiLink, so it is not used.
 */
class ParkedQuiet(private val car: Autoservice, private val adb: AdbShell, private val prefs: SharedPreferences) {

    /** Returns a message for the owner when something changed, null otherwise. Call every poll. */
    fun poll(snap: CarSnapshot?): String? {
        when (prefs.getString(KEY_REQUEST, null)) {
            "on" -> { prefs.edit().remove(KEY_REQUEST).apply(); return goQuiet() }
            "off" -> {
                prefs.edit().remove(KEY_REQUEST).apply()
                restore()
                return "☀️ Тихий режим вимкнено: екран увімкнено."
            }
        }
        if (!prefs.getBoolean(ACTIVE, false)) return null
        val stillParked = snap != null && snap.locked == true && snap.doorsOpen.none { it == true } &&
            nobodyInside() && car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1
        if (!stillParked && snap != null) restore()
        return null
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
        return "🌙 Тихий режим: $screen, ходові вогні — $drl. " +
            "Авто лишається на зв'язку; відчините авто або натисніть «☀️ Екран увімкнути»."
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
        const val KEY_ENABLED = "parked_quiet" // legacy switch, no longer read
        const val KEY_REQUEST = "parked_quiet_request"
        private const val ACTIVE = "parked_quiet_active"
        private const val SAVED_MODE = "parked_quiet_mode"
        private const val SAVED_LEVEL = "parked_quiet_level"
        private const val TX_BACKLIGHT_OFF = 45
        private const val TX_BACKLIGHT_ON = 46
    }
}
