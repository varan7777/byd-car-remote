package ua.zaika.carremote.car

import android.content.SharedPreferences
import ua.zaika.carremote.adb.AdbShell

/**
 * «Тихий режим», automatic: when the car stands in P, locked, doors shut, climate off, nobody in
 * the seats and the screen untouched for ten minutes, for two minutes in a row, switch the head unit's backlight and the daytime running lights off, so a car kept
 * online («Доступність на стоянці», or simply charging) does not stand lit up in the yard. As soon
 * as anyone is back — unlocked, a door opens, a seat is taken, the driver's belt buckled, a gear
 * engaged — both come back on.
 *
 * The screen goes off the way DiLink's own «screen off» button does it: SystemUI calls BYD's
 * IDisplayManager.turnBacklightOff (transaction 45; 46 = on). A touch on the panel lights it again.
 * Android's screen_brightness setting is ignored by DiLink, so it is not used.
 */
class ParkedQuiet(private val car: Autoservice, private val adb: AdbShell, private val prefs: SharedPreferences) {

    private var emptySince = 0L

    /** Call every poll. Never messages the owner: it just follows who is in the car. */
    fun poll(snap: CarSnapshot?) {
        snap ?: return
        // The driver's seat has no sensor and people lock themselves in, so also count the
        // climate running and a recent touch on the screen as someone being there.
        val empty = snap.locked == true && snap.doorsOpen.none { it == true } &&
            car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1 && !someoneInside(car) &&
            car.getInt(Fids.DEV_CLIMATE, Fids.AC_STATUS) != 1 && !touchedWithin(TOUCH_QUIET_MS)
        val active = prefs.getBoolean(ACTIVE, false)
        if (!empty) {
            emptySince = 0
            if (active) restore()
            return
        }
        val now = System.currentTimeMillis()
        if (emptySince == 0L) emptySince = now
        if (!active && now - emptySince >= QUIET_AFTER_MS) goQuiet()
    }

    /** True when the head unit's screen was touched in the last [ms] (or that can't be read). */
    private fun touchedWithin(ms: Long): Boolean {
        val out = adb.exec("dumpsys power | grep -m1 mLastUserActivityTime; cat /proc/uptime") ?: return true
        val last = Regex("mLastUserActivityTime\\S*=(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: return true
        val uptime = Regex("^(\\d+)\\.", RegexOption.MULTILINE).find(out.substringAfter('\n'))
            ?.groupValues?.get(1)?.toLongOrNull()?.times(1000) ?: return true
        return uptime - last < ms
    }

    private fun goQuiet() {
        legacyRestore()
        prefs.edit().putBoolean(ACTIVE, true).apply()
        adb.exec("service call display $TX_BACKLIGHT_OFF i32 0 i64 0")
        car.write(Fids.DEV_LIGHTS, Fids.DRL_SET, 2)
    }

    private fun restore() {
        legacyRestore()
        adb.exec("service call display $TX_BACKLIGHT_ON i32 0 i64 0") ?: return
        car.write(Fids.DEV_LIGHTS, Fids.DRL_SET, 1)
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
        private const val QUIET_AFTER_MS = 2 * 60_000L
        private const val TOUCH_QUIET_MS = 10 * 60_000L
        private const val ACTIVE = "parked_quiet_active"
        private const val SAVED_MODE = "parked_quiet_mode"
        private const val SAVED_LEVEL = "parked_quiet_level"
        private const val TX_BACKLIGHT_OFF = 45
        private const val TX_BACKLIGHT_ON = 46
    }
}

/**
 * Anyone in the car: a seat sensor says occupied, or the driver's belt is buckled. The driver's
 * seat has no sensor on Song Plus, so a driver sitting unbuckled is not seen — callers that need
 * "the cabin is empty" also require the car to be locked.
 */
fun someoneInside(car: Autoservice): Boolean =
    Fids.OCCUPANCY.any { car.getInt(Fids.DEV_SEATS, it) == 2 } ||
        car.getInt(Fids.DEV_BELTS, Fids.SEATBELT_DRIVER) == 1
