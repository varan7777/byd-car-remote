package ua.zaika.carremote.air

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One oblast's outer rings for drawing: each ring is a flat [lon,lat,lon,lat,…] array. */
data class OblastShape(val key: String, val rings: List<DoubleArray>)

/**
 * Maps a GPS point to its oblast using NEPTUN's own oblast polygons, so "alert for my area"
 * works without Google Play services (the head unit has none). The ~160 KB GeoJSON is
 * fetched once and cached on disk; oblast borders are static, so a monthly re-fetch is plenty.
 *
 * feature.properties.key ("київська") matches the key used by /api/v1/alerts oblasts[].
 */
class RegionLocator(
    private val context: Context,
    private val client: NeptunClient = NeptunClient(),
) {
    // key -> MultiPolygon coordinates: array of polygons, each an array of rings, each [lon,lat].
    @Volatile private var features: List<Pair<String, JSONArray>>? = null

    /** Oblast key containing the point, or null if unknown / data not yet available. */
    fun oblastKeyAt(lat: Double, lon: Double): String? {
        val feats = features ?: load() ?: return null
        for ((key, polys) in feats) {
            for (p in 0 until polys.length()) {
                val outer = polys.optJSONArray(p)?.optJSONArray(0) ?: continue
                if (pointInRing(lon, lat, outer)) return key
            }
        }
        return null
    }

    /** Outer rings of every oblast, for drawing the country outline. Call off the main thread. */
    fun shapes(): List<OblastShape> {
        val feats = features ?: load() ?: return emptyList()
        val out = ArrayList<OblastShape>(feats.size)
        for ((key, polys) in feats) {
            val rings = ArrayList<DoubleArray>()
            for (p in 0 until polys.length()) {
                val outer = polys.optJSONArray(p)?.optJSONArray(0) ?: continue
                val n = outer.length()
                val flat = DoubleArray(n * 2)
                for (i in 0 until n) {
                    val pt = outer.optJSONArray(i) ?: continue
                    flat[i * 2] = pt.optDouble(0)       // lon
                    flat[i * 2 + 1] = pt.optDouble(1)   // lat
                }
                rings.add(flat)
            }
            if (rings.isNotEmpty()) out.add(OblastShape(key, rings))
        }
        return out
    }

    private fun load(): List<Pair<String, JSONArray>>? {
        val json = readCached() ?: fetchAndCache() ?: return null
        return try {
            val arr = json.optJSONArray("features") ?: return null
            val out = ArrayList<Pair<String, JSONArray>>(arr.length())
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val key = f.optJSONObject("properties")?.optString("key")?.takeIf { it.isNotBlank() } ?: continue
                val geom = f.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                val polys = when (geom.optString("type")) {
                    "MultiPolygon" -> coords                 // [polygon][ring][pt]
                    "Polygon" -> JSONArray().put(coords)      // wrap to one-element [polygon]
                    else -> continue
                }
                out.add(key to polys)
            }
            out.also { features = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFile() = File(context.filesDir, "oblasts.geojson")

    private fun readCached(): JSONObject? {
        val f = cacheFile()
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600_000) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun fetchAndCache(): JSONObject? = runCatching {
        client.geoJson("oblasts.geojson").also { runCatching { cacheFile().writeText(it.toString()) } }
    }.getOrNull()

    /** Ray-casting point-in-polygon on the outer ring (oblasts have no relevant holes). */
    private fun pointInRing(x: Double, y: Double, ring: JSONArray): Boolean {
        var inside = false
        val n = ring.length()
        var j = n - 1
        for (i in 0 until n) {
            val pi = ring.optJSONArray(i) ?: continue
            val pj = ring.optJSONArray(j)
            if (pj == null) { j = i; continue }
            val xi = pi.optDouble(0); val yi = pi.optDouble(1)
            val xj = pj.optDouble(0); val yj = pj.optDouble(1)
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside
            j = i
        }
        return inside
    }
}
