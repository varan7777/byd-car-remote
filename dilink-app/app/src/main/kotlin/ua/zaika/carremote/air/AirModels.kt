package ua.zaika.carremote.air

import org.json.JSONObject

/** One tracked air threat from NEPTUN /api/v1/threats. */
data class AirThreat(
    val id: String,
    val type: String,          // uav | recon | missile | ballistic | kab | mig31k | unknown
    val title: String,
    val region: String,        // oblast name, e.g. "Київська область"
    val locality: String,      // nearest settlement NEPTUN names
    val lat: Double,
    val lon: Double,
    val heading: Int?,         // course over ground in degrees, or null if unknown
    val confidence: String,    // low | medium | high
    val sourceCount: Int,
    val uncertaintyKm: Double,
    val explanation: String,   // short human text, already in Ukrainian from NEPTUN
    val updatedMs: Long? = null, // when NEPTUN last confirmed this position
    val district: String = "",
    val status: String = "",          // active | ...
    val positionQuality: String = "", // confirmed | estimated | ...
    val confirmedMs: Long? = null,
    val speedKmh: Double? = null,     // ground speed from NEPTUN's own trail, when it has enough of one
    val airForce: String? = null,     // the Air Force post about this track saying it's a jet, if any
    val path: List<Pair<Double, Double>> = emptyList(), // positions reported over the last 30 min, oldest first
    val trail: List<Triple<Double, Double, Long>> = emptyList(), // NEPTUN's own earlier fixes (lat, lon, time)
    val source: String = "neptun",    // "neptun" or "airforce" (placed from an Air Force post)
) {
    /**
     * A jet Shahed (Geran-3). NEPTUN types it as a plain "uav" and only sometimes says so in the
     * title, so the Air Force channel ([AirForceFeed]) and its speed decide too: a Shahed cruises at 150–200 km/h, the jet one at 500+.
     */
    val jet: Boolean get() = type == "uav" &&
        (airForce != null || JET_TITLE.containsMatchIn(title + " " + explanation) || (speedKmh ?: 0.0) >= JET_KMH)

    /** Minutes since NEPTUN last confirmed the position, or null if it gave no time. */
    fun ageMin(now: Long = System.currentTimeMillis()): Int? = updatedMs?.let { ((now - it) / 60_000).toInt().coerceAtLeast(0) }

    /** Short label with the gist of the threat type, for the on-screen banner. */
    val label: String get() = when (type) {
        "uav" -> if (jet) "Реактивний БпЛА" else "БпЛА"
        "recon" -> "Розвід. БпЛА"
        "fpv" -> "FPV-дрон"
        "missile" -> "Ракета"
        "ballistic" -> "Балістика"
        "kab" -> "КАБ"
        "mig31k" -> "МіГ-31К"
        else -> title.ifBlank { "Загроза" }
    }

    companion object {
        private val JET_TITLE = Regex("реактив|238|герань-3|geran-3", RegexOption.IGNORE_CASE)
        private const val JET_KMH = 350.0

        private fun trailPoints(o: JSONObject): List<Triple<Double, Double, Long>> {
            val tr = o.optJSONArray("trail") ?: return emptyList()
            return (0 until tr.length()).mapNotNull { i ->
                val p = tr.optJSONObject(i) ?: return@mapNotNull null
                val t = parseIso(p.optString("t")) ?: return@mapNotNull null
                Triple(p.optDouble("lat"), p.optDouble("lon"), t)
            }.filter { !it.first.isNaN() && !it.second.isNaN() }.sortedBy { it.third }
        }

        /**
         * Ground speed from (lat, lon, time) fixes, or null when it can't be trusted. NEPTUN positions
         * are approximate and get re-anchored, so: a base of 6–30 min, a distance well above the
         * position error, and both halves of the stretch fast — one jump of a fix is not a jet.
         */
        fun steadySpeed(points: List<Triple<Double, Double, Long>>, uncertaintyKm: Double): Double? {
            val pts = points.sortedBy { it.third }
            val last = pts.lastOrNull() ?: return null
            val from = pts.firstOrNull { last.third - it.third in 360_000..1_800_000 } ?: return null
            val mid = pts.filter { it.third > from.third && it.third < last.third }
                .minByOrNull { kotlin.math.abs(it.third - (from.third + last.third) / 2) } ?: return null
            fun kmh(a: Triple<Double, Double, Long>, b: Triple<Double, Double, Long>) =
                Geo.distanceKm(a.first, a.second, b.first, b.second) / ((b.third - a.third) / 3_600_000.0)
            val km = Geo.distanceKm(from.first, from.second, last.first, last.second)
            if (km < maxOf(15.0, 3 * uncertaintyKm)) return null
            val speed = kmh(from, last)
            return speed.takeIf { kmh(from, mid) >= 0.6 * it && kmh(mid, last) >= 0.6 * it }
        }

        fun parseIso(v: String?): Long? = v?.takeIf { it.isNotBlank() }?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
        }

        fun parse(o: JSONObject): AirThreat? {
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            return AirThreat(
                id = o.optString("id"),
                type = o.optString("type", "unknown"),
                title = o.optString("title", "Загроза"),
                region = o.optString("region", ""),
                locality = o.optString("locality", ""),
                lat = lat,
                lon = lon,
                heading = if (o.isNull("heading")) null else o.optInt("heading"),
                confidence = o.optString("displayConfidence").ifBlank { o.optString("confidenceLevel") },
                sourceCount = o.optInt("sourceCount", 0),
                uncertaintyKm = o.optDouble("uncertaintyKm", 0.0),
                explanation = o.optString("explanationShort"),
                updatedMs = parseIso(o.optString("updatedAt").ifBlank { o.optString("confirmedAt") }),
                district = o.optString("district", ""),
                status = o.optString("status", ""),
                positionQuality = o.optString("positionQuality").ifBlank { o.optString("lifecycle") },
                confirmedMs = parseIso(o.optString("confirmedAt")),
                speedKmh = steadySpeed(trailPoints(o), o.optDouble("uncertaintyKm", 0.0)),
                trail = trailPoints(o),
            )
        }
    }
}

/** Official air-raid alert for an oblast (NEPTUN /api/v1/alerts). */
data class AirAlert(
    val key: String,       // lowercased oblast name, matches oblasts.geojson properties.key
    val name: String,
    val level: String,     // red | ...
    val reasons: List<String>,
)
