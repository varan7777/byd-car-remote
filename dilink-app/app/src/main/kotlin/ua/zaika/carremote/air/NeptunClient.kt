package ua.zaika.carremote.air

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Read-only client for the NEPTUN air-threat API (neptun.in.ua).
 *
 * No API key, open CORS; we only ever GET. NEPTUN asks callers to poll the REST snapshot
 * no more than once every 5 s, so keep the monitor's period at/above that. NEPTUN is an
 * information aggregator, not an official alert system — treat it as one source.
 */
class NeptunClient(private val base: String = "https://neptun.in.ua") {

    data class Threats(val serverTime: String?, val list: List<AirThreat>)

    fun threats(): Threats {
        val o = getJson("$base/api/v1/threats")
        val arr = o.optJSONArray("threats")
        val list = ArrayList<AirThreat>(arr?.length() ?: 0)
        if (arr != null) for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { AirThreat.parse(it)?.let(list::add) }
        }
        return Threats(o.optStringOrNull("serverTime"), list)
    }

    /** Official alerts keyed by oblast; the oblast level is enough to warn a driver. */
    fun oblastAlerts(): List<AirAlert> {
        val o = getJson("$base/api/v1/alerts")
        val arr = o.optJSONArray("oblasts") ?: return emptyList()
        val out = ArrayList<AirAlert>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val reasons = a.optJSONArray("reasons")
                ?.let { r -> (0 until r.length()).map { r.optString(it) } }
                ?: emptyList()
            out.add(AirAlert(a.optString("key"), a.optString("name"), a.optString("level", "red"), reasons))
        }
        return out
    }

    /** Fetch a GeoJSON file served at the site root (e.g. "oblasts.geojson"). */
    fun geoJson(path: String): JSONObject = getJson("$base/$path")

    private fun getJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CarRemote-DiLink/air")
        }
        try {
            val code = c.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name)
}
