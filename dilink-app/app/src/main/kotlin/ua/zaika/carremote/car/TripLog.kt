package ua.zaika.carremote.car

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One completed/ongoing drive, derived from odometer + BMS lifetime-kWh deltas. */
data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val kwh: Double?,
    val startSoc: Double?,
    val endSoc: Double?,
) {
    val durationMin: Int get() = (((endMs - startMs) / 60_000L).toInt()).coerceAtLeast(0)
    val per100: Double? get() = kwh?.let { if (distanceKm >= 0.5) it / distanceKm * 100 else null }

    companion object {
        fun from(o: JSONObject): Trip {
            val kStart = o.optDouble("startKwh", Double.NaN)
            val kEnd = o.optDouble("endKwh", Double.NaN)
            val kwh = if (kStart.isNaN() || kEnd.isNaN()) null else (kEnd - kStart).takeIf { it >= 0 }
            return Trip(
                startMs = o.optLong("startMs"),
                endMs = o.optLong("endMs"),
                distanceKm = (o.optDouble("endOdo") - o.optDouble("startOdo")).coerceAtLeast(0.0),
                kwh = kwh,
                startSoc = o.optDouble("startSoc").takeIf { !it.isNaN() },
                endSoc = o.optDouble("endSoc").takeIf { !it.isNaN() },
            )
        }
    }
}

/**
 * Persistent trip journal. Each power session is one trip: distance from the odometer, energy
 * from the BMS lifetime-kWh counter (the same real figure [TripTracker] uses). Written to disk
 * on every update, so a trip survives the app being killed at ignition-off; a quick restart
 * mid-drive continues the last trip instead of starting a new one.
 */
class TripLog(context: Context) {

    private val file = File(context.filesDir, "trips.json")
    private val trips = ArrayList<JSONObject>()
    private var current: JSONObject? = null

    init {
        runCatching {
            if (file.exists()) {
                val arr = JSONArray(file.readText())
                for (i in 0 until arr.length()) trips.add(arr.getJSONObject(i))
            }
        }
        // Keep the file from growing forever.
        while (trips.size > MAX_TRIPS) trips.removeAt(0)
    }

    private fun persist() = runCatching {
        val arr = JSONArray()
        trips.forEach { arr.put(it) }
        file.writeText(arr.toString())
    }

    /** Begin a trip, or continue the last one if we are clearly still on the same drive. */
    fun onStart(snap: CarSnapshot) {
        val odo = snap.mileageKm ?: return
        val now = System.currentTimeMillis()
        val last = trips.lastOrNull()
        val continues = last != null &&
            now - last.optLong("endMs") < CONTINUE_GAP_MS &&
            (odo - last.optDouble("endOdo")) in -0.5..2.0
        if (continues) { current = last; return }
        current = JSONObject().apply {
            put("startMs", now); put("endMs", now)
            put("startOdo", odo.toDouble()); put("endOdo", odo.toDouble())
            // Omit (not NaN — JSONObject.put rejects NaN) when a reading is unavailable.
            snap.lifetimeKwh?.let { put("startKwh", it.toDouble()); put("endKwh", it.toDouble()) }
            snap.soc?.let { put("startSoc", it.toDouble()); put("endSoc", it.toDouble()) }
        }
        trips.add(current!!)
        while (trips.size > MAX_TRIPS) trips.removeAt(0)
        persist()
    }

    /** Extend the current trip's end fields. */
    fun onUpdate(snap: CarSnapshot) {
        val c = current ?: return
        snap.mileageKm?.let { c.put("endOdo", it.toDouble()) }
        snap.lifetimeKwh?.let { c.put("endKwh", it.toDouble()) }
        snap.soc?.let { c.put("endSoc", it.toDouble()) }
        c.put("endMs", System.currentTimeMillis())
        persist()
    }

    /** Recorded trips, newest first, ignoring ignition-on-without-driving noise. */
    fun recent(limit: Int = 100): List<Trip> =
        trips.map(Trip::from).filter { it.distanceKm >= 0.3 }.reversed().take(limit)

    fun clear() {
        trips.clear(); current = null; persist()
    }

    private companion object {
        const val MAX_TRIPS = 500
        const val CONTINUE_GAP_MS = 15 * 60_000L
    }
}
