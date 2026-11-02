package ua.zaika.carremote.air

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Journal of air alerts around the car: when each one started and ended, how bad it got
 * (yellow, red, a threat inside the radius) and how many threats came inside the radius.
 * Fed by the service on every NEPTUN poll; read by the alert screen.
 */
object AlertLog {
    data class Entry(val startMs: Long, val endMs: Long?, val maxRank: Int, val nearIds: Set<String>, val lastMs: Long = startMs) {
        fun toJson() = JSONObject().put("start", startMs).put("end", endMs ?: JSONObject.NULL)
            .put("rank", maxRank).put("near", JSONArray(nearIds.toList())).put("last", lastMs)

        companion object {
            fun from(j: JSONObject) = Entry(
                j.getLong("start"), if (j.isNull("end")) null else j.getLong("end"), j.optInt("rank"),
                j.optJSONArray("near")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
                j.optLong("last", j.getLong("start")),
            )
        }
    }

    private const val FILE = "alert_log.json"
    private const val MAX_ROWS = 500
    private val lock = Any()

    private fun file(context: Context) = android.util.AtomicFile(File(context.filesDir, FILE))

    fun all(context: Context): List<Entry> = synchronized(lock) {
        runCatching { JSONArray(String(file(context).readFully())).let { a -> (0 until a.length()).map { Entry.from(a.getJSONObject(it)) } } }
            .getOrDefault(emptyList()).sortedByDescending { it.startMs }
    }

    /** Written whole through AtomicFile, so a kill mid-write (the head unit does that) can't corrupt it. */
    private fun save(context: Context, list: List<Entry>) = synchronized(lock) {
        val a = JSONArray()
        list.sortedByDescending { it.startMs }.take(MAX_ROWS).forEach { a.put(it.toJson()) }
        val f = file(context)
        val out = f.startWrite()
        runCatching { out.write(a.toString().toByteArray()); f.finishWrite(out) }.onFailure { f.failWrite(out) }
    }

    /**
     * Opens, extends or closes the current entry from the latest state; call on every successful poll.
     * An entry ends at the last moment it was still an alert, once it has been calm for a few minutes
     * (or polls stopped — the car slept) — so one alert is one row, not dozens.
     */
    fun track(context: Context, s: AirState) = synchronized(lock) {
        val rank = s.alarmRank()
        val list = all(context).toMutableList()
        val now = System.currentTimeMillis()
        val near = s.within.map { it.first.id }.toSet()
        var open = list.firstOrNull { it.endMs == null }
        var changed = false
        if (open != null && now - open.lastMs > GAP_MS) {
            list[list.indexOf(open)] = open.copy(endMs = open.lastMs); open = null; changed = true
        }
        if (rank > 0) {
            if (open == null) list.add(Entry(now, null, rank, near, now))
            else list[list.indexOf(open)] = open.copy(maxRank = maxOf(open.maxRank, rank), nearIds = open.nearIds + near, lastMs = now)
            changed = true
        } else if (open != null && s.alertsUnknown) {
            list[list.indexOf(open)] = open.copy(lastMs = now); changed = true // unknown isn't calm
        } else if (open != null && now - open.lastMs >= ua.zaika.carremote.CarRemoteService.CALM_HOLD_MS) {
            list[list.indexOf(open)] = open.copy(endMs = open.lastMs); changed = true
        }
        if (changed) save(context, list)
    }

    private const val GAP_MS = 10 * 60_000L

    fun rankName(rank: Int) = when (rank) {
        3 -> "Загроза в радіусі"
        2 -> "Червона"
        1 -> "Жовта"
        else -> "—"
    }
}
