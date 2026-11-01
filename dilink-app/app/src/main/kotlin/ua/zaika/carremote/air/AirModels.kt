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
) {
    /** Minutes since NEPTUN last confirmed the position, or null if it gave no time. */
    fun ageMin(now: Long = System.currentTimeMillis()): Int? = updatedMs?.let { ((now - it) / 60_000).toInt().coerceAtLeast(0) }

    /** Short label with the gist of the threat type, for the on-screen banner. */
    val label: String get() = when (type) {
        "uav" -> "БпЛА"
        "recon" -> "Розвід. БпЛА"
        "missile" -> "Ракета"
        "ballistic" -> "Балістика"
        "kab" -> "КАБ"
        "mig31k" -> "МіГ-31К"
        else -> title.ifBlank { "Загроза" }
    }

    companion object {
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
