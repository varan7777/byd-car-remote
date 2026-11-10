package ua.zaika.carremote.car

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * «Очистити повітря» the way BYD's manuals say to clean the cabin: recirculation with the fan at
 * full, here for [RUN_MS], then the climate goes back to how it was.
 *
 * The factory quick-clean (`QUICK_CLEAN_AIR_SET`) belongs to an optional purifier module, does not
 * close the outside-air flap, and on Artem's Song Plus it drew air from the street (10.11). So the
 * app sets the flap and the fan itself, each write read back.
 *
 * The run survives the app being killed: the state before it is kept in prefs and [tick] (called
 * by the service) restores it when the time is up. If the driver changed the climate meanwhile,
 * nothing is restored: their choice wins.
 */
object CleanAir {
    const val RUN_MS = 10 * 60_000L

    // Climate, dev 1000 (BYD SDK; read and write addresses differ).
    private const val POWER = 1077936144      // 1 on, 0 off  (= Fids.AC_STATUS)
    private const val POWER_SET = 501219364
    private const val CYCLE = 1077936148      // 0 outside air, 1 recirculation
    private const val CYCLE_SET = 501219355
    private const val WIND = 1077936156       // fan 0..7
    private const val WIND_SET = 501219340
    private const val MODE = 1077936146       // 0 auto, 1 manual
    private const val MODE_SET = 501219352
    private const val RECIRCULATE = 1
    private const val WIND_MAX = 7

    private const val PREF = "clean_air_run"

    fun running(prefs: SharedPreferences): Boolean = prefs.getString(PREF, null) != null

    /** Starts a run (or restarts its timer). Returns what to tell the driver. */
    fun start(car: Autoservice, prefs: SharedPreferences): String {
        val dev = Fids.DEV_CLIMATE
        val before = prefs.getString(PREF, null)?.let { JSONObject(it) } ?: JSONObject().apply {
            put("power", car.getInt(dev, POWER) ?: JSONObject.NULL)
            put("cycle", car.getInt(dev, CYCLE) ?: JSONObject.NULL)
            put("wind", car.getInt(dev, WIND) ?: JSONObject.NULL)
            put("mode", car.getInt(dev, MODE) ?: JSONObject.NULL)
        }
        if (car.getInt(dev, POWER) != 1) { car.write(dev, POWER_SET, 1); Thread.sleep(SETTLE_MS) }
        car.write(dev, CYCLE_SET, RECIRCULATE)
        Thread.sleep(SETTLE_MS)
        car.write(dev, WIND_SET, WIND_MAX)
        // The climate unit applies writes with a delay, so give it a moment before reading back.
        var cycle: Int? = null; var wind: Int? = null
        for (i in 0 until 4) {
            Thread.sleep(SETTLE_MS)
            cycle = car.getInt(dev, CYCLE); wind = car.getInt(dev, WIND)
            if (cycle == RECIRCULATE && wind == WIND_MAX) break
        }
        if (cycle != RECIRCULATE && wind != WIND_MAX) {
            if (before.optInt("power", -1) == 0) car.write(dev, POWER_SET, 0)
            prefs.edit().remove(PREF).apply()
            return "⚠️ Очищення повітря: авто не прийняло рециркуляцію і вентилятор (заслінка=$cycle, вентилятор=$wind)."
        }
        prefs.edit().putString(PREF, before.put("until", System.currentTimeMillis() + RUN_MS).toString()).apply()
        val notes = buildList {
            if (cycle != RECIRCULATE) add("рециркуляція не підтвердилась")
            if (wind != WIND_MAX) add("вентилятор $wind замість $WIND_MAX")
        }
        return "✅ Очищення повітря: рециркуляція, вентилятор на максимум, ${RUN_MS / 60_000} хв. Потім клімат повернеться, як був." +
            if (notes.isEmpty()) "" else "\n⚠️ " + notes.joinToString(", ")
    }

    /** Call regularly: once the run is over, puts the climate back as it was. */
    fun tick(car: Autoservice, prefs: SharedPreferences) {
        val run = prefs.getString(PREF, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (System.currentTimeMillis() < run.optLong("until")) return
        val dev = Fids.DEV_CLIMATE
        val power = car.getInt(dev, POWER) ?: return // car asleep: try again on the next tick
        prefs.edit().remove(PREF).apply()
        // Changed by hand meanwhile (turned off, fan or flap moved): leave it as the driver set it.
        if (power != 1 || car.getInt(dev, CYCLE) != RECIRCULATE || car.getInt(dev, WIND) != WIND_MAX) return
        fun was(k: String) = if (run.isNull(k)) null else run.optInt(k)
        if (was("power") == 0) { car.write(dev, POWER_SET, 0); return }
        was("cycle")?.let { car.write(dev, CYCLE_SET, it); Thread.sleep(SETTLE_MS) }
        if (was("mode") == 0) car.write(dev, MODE_SET, 0) // auto picks the fan again
        else was("wind")?.let { car.write(dev, WIND_SET, it) }
    }

    private const val SETTLE_MS = 700L
}
