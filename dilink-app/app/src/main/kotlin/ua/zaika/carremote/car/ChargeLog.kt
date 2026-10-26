package ua.zaika.carremote.car

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import ua.zaika.carremote.CarRemoteService
import java.io.File

/**
 * One charging session as the app saw it. [priceKey] names the tariff it is billed at:
 * "home", "dc1" or "dc2" (the owner can change it later); [price] is that tariff when the
 * session was recorded, so later tariff edits do not rewrite history unless re-chosen.
 */
data class ChargeSession(
    val startMs: Long,
    val endMs: Long,
    val dc: Boolean,
    val socStart: Float?,
    val socEnd: Float?,
    val kwh: Double,
    val priceKey: String?,
    val price: Double?,
) {
    val cost: Double? get() = price?.let { it * kwh }

    fun toJson() = JSONObject().put("start", startMs).put("end", endMs).put("dc", dc)
        .put("soc0", socStart?.toDouble() ?: JSONObject.NULL).put("soc1", socEnd?.toDouble() ?: JSONObject.NULL)
        .put("kwh", kwh).put("key", priceKey ?: JSONObject.NULL).put("price", price ?: JSONObject.NULL)

    companion object {
        fun from(j: JSONObject) = ChargeSession(
            j.getLong("start"), j.getLong("end"), j.getBoolean("dc"),
            j.optDouble("soc0").takeIf { !it.isNaN() }?.toFloat(), j.optDouble("soc1").takeIf { !it.isNaN() }?.toFloat(),
            j.getDouble("kwh"), j.optString("key").takeIf { it.isNotEmpty() && it != "null" },
            j.optDouble("price").takeIf { !it.isNaN() },
        )
    }
}

/**
 * The car keeps no charging journal of its own (its energy DB only has trips), so the service
 * records sessions: cable in (AC or DC) and the BMS charging → a session; cable out or a long
 * pause → it ends. Energy comes from the BMS per-session counter when it reports one, otherwise
 * from integrating pack voltage × charging current, and as a last resort from the SOC change.
 */
object ChargeLog {
    private const val FILE = "charges.json"
    private const val PACK_KWH = 87.0 // 512 V × 170 Ah, from the car's nameplate
    private const val MAX_ROWS = 2000

    private val lock = Any()

    fun all(context: Context): List<ChargeSession> = synchronized(lock) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { ChargeSession.from(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).sortedByDescending { it.startMs }
    }

    private fun save(context: Context, list: List<ChargeSession>) = synchronized(lock) {
        val a = JSONArray()
        list.sortedByDescending { it.startMs }.take(MAX_ROWS).forEach { a.put(it.toJson()) }
        File(context.filesDir, FILE).writeText(a.toString())
    }

    fun add(context: Context, s: ChargeSession) = save(context, all(context) + s)

    /** Re-bill one session at another tariff. */
    fun setTariff(context: Context, startMs: Long, key: String) {
        val price = tariff(context.getSharedPreferences("carremote", Context.MODE_PRIVATE), key)
        save(context, all(context).map { if (it.startMs == startMs) it.copy(priceKey = key, price = price) else it })
    }

    fun tariff(prefs: SharedPreferences, key: String): Double? = when (key) {
        "home" -> prefs.getString(CarRemoteService.KEY_TARIFF, "")
        "dc1" -> prefs.getString(CarRemoteService.KEY_TARIFF_DC1, "")
        "dc2" -> prefs.getString(CarRemoteService.KEY_TARIFF_DC2, "")
        else -> null
    }?.replace(',', '.')?.toDoubleOrNull()

    /** Fast-charge tariffs the owner has filled in, most expensive first. */
    fun dcTariffs(prefs: SharedPreferences): List<Pair<String, Double>> =
        listOf("dc1", "dc2").mapNotNull { k -> tariff(prefs, k)?.let { k to it } }.sortedByDescending { it.second }

    /** Default tariff for a new session: home for AC, the dearer fast-charge tariff for DC. */
    fun defaultKey(prefs: SharedPreferences, dc: Boolean): String? =
        if (!dc) "home".takeIf { tariff(prefs, it) != null } else dcTariffs(prefs).firstOrNull()?.first

    /** Watches the live readings; call every poll from the service. */
    class Recorder(private val context: Context, private val prefs: SharedPreferences, private val onDcStart: (Long) -> Unit) {
        private var startMs = 0L
        private var dc = false
        private var soc0: Float? = null
        private var lastSoc: Float? = null
        private var integrated = 0.0
        private var lastMs = 0L
        private var counter = 0.0
        private var idleSince = 0L

        val activeStart: Long? get() = startMs.takeIf { it > 0 }

        fun poll(car: Autoservice) {
            val gun = car.getInt(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE)
            val bms = car.getInt(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE)
            val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)
            val now = System.currentTimeMillis()
            val plugged = gun != null && gun in 2..4 // 2 AC, 3 DC, 4 AC+DC; 5 = V2L (discharging) is not charging
            val charging = plugged && bms == 1

            if (charging && startMs == 0L) {
                startMs = now; dc = gun == 3 || gun == 4; soc0 = soc; integrated = 0.0; counter = 0.0; lastMs = now
                if (dc && dcTariffs(prefs).size > 1) onDcStart(startMs)
            }
            if (startMs == 0L) return

            if (charging) {
                idleSince = 0
                val v = car.getInt(Fids.DEV_CHARGING, Fids.HV_VOLTAGE)
                val a = car.getFloat(Fids.DEV_CHARGING, Fids.HV_CURRENT)
                if (v != null && a != null && a < 0 && v in 200..900) {
                    val hours = (now - lastMs).coerceIn(0, 120_000) / 3_600_000.0
                    integrated += v * -a.toDouble() * hours / 1000.0
                }
                car.getFloat(Fids.DEV_CHARGING, Fids.CHARGING_CAPACITY)?.toDouble()
                    ?.takeIf { it in 0.0..200.0 }?.let { if (it > counter) counter = it }
                soc?.let { lastSoc = it }
            } else if (idleSince == 0L) idleSince = now
            lastMs = now

            // Ends when the cable is out, or the BMS has stopped charging for 10 minutes.
            if (!plugged || (idleSince != 0L && now - idleSince > 10 * 60_000)) finish(now)
        }

        private fun finish(now: Long) {
            val bySoc = if (soc0 != null && lastSoc != null) ((lastSoc!! - soc0!!) / 100.0 * PACK_KWH).coerceAtLeast(0.0) else 0.0
            val kwh = when {
                counter >= 0.3 -> counter
                integrated >= 0.3 -> integrated
                else -> bySoc
            }
            if (kwh >= 0.2) {
                val chosen = prefs.getString(PREF_CHOSEN + startMs, null)
                val key = chosen ?: defaultKey(prefs, dc)
                add(context, ChargeSession(startMs, now, dc, soc0, lastSoc, kwh, key, key?.let { tariff(prefs, it) }))
                prefs.edit().remove(PREF_CHOSEN + startMs).apply()
            }
            startMs = 0; idleSince = 0; soc0 = null; lastSoc = null
        }
    }

    /** Where the tariff pop-up stores the owner's choice for a session still in progress. */
    const val PREF_CHOSEN = "charge_tariff_"
}
