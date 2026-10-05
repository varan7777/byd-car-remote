package ua.zaika.carremote.air

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** The air situation around the car, recomputed on every poll. */
data class AirState(
    val fix: Pair<Double, Double>?,              // car GPS, or null if unknown
    val nearest: AirThreat?,                     // closest threat at any distance
    val nearestKm: Double?,
    val approaching: Boolean,                    // nearest is moving roughly toward us
    val within: List<Pair<AirThreat, Double>>,   // threats inside the alarm radius, nearest first
    val oblastAlert: AirAlert?,                  // official alert for our oblast, or null
    val totalThreats: Int,
    val serverTime: String?,
    val error: String?,
    val all: List<AirThreat> = emptyList(),      // every tracked threat, for the map
    val alertKeys: Set<String> = emptySet(),     // keys of all oblasts under alert, for shading
) {
    /** Worth a beep and a red banner: official siren for our oblast, or a threat within radius. */
    val isAlarm: Boolean get() = oblastAlert != null || within.isNotEmpty()

    private fun threatLine(radiusKm: Double): String {
        val n = nearest ?: return "🟢 Поблизу чисто"
        val km = nearestKm ?: return "🟢 Поблизу чисто"
        val f = fix ?: return "${n.label} ~${km.roundToInt()} км"
        val where = Geo.compass(Geo.bearingDeg(f.first, f.second, n.lat, n.lon))
        val appr = if (approaching) " · курс на вас" else ""
        val tag = if (km <= radiusKm) "🚨" else "•"
        return "$tag ${n.label} ~${km.roundToInt()} км ($where)$appr"
    }

    /** Full multi-line text for the map screen banner. */
    fun bannerText(radiusKm: Double): String {
        if (error != null) return "🛰️ $error"
        val lines = ArrayList<String>()
        oblastAlert?.let {
            val why = it.reasons.firstOrNull()?.let { r -> " — $r" } ?: ""
            lines.add("🔴 Повітряна тривога: ${it.name}$why")
        }
        lines.add(threatLine(radiusKm))
        lines.add("Загроз у країні: $totalThreats")
        return lines.joinToString("\n")
    }

    /** Short spoken warning for TTS when an alarm first fires. */
    fun voiceAlert(): String {
        oblastAlert?.let { return "Повітряна тривога. ${it.name}." }
        val n = nearest
        val km = nearestKm
        if (n != null && km != null) {
            val appr = if (approaching) " Курс на вас." else ""
            return "Увага. ${n.label} за ${km.roundToInt()} кілометрів.$appr"
        }
        return "Повітряна тривога."
    }

    /** One compact line for the dashboard. */
    fun oneLine(radiusKm: Double): String {
        if (error != null) return "🛰️ Повітряні загрози: $error"
        val siren = oblastAlert?.let { "🔴 Тривога: ${it.name}  ·  " } ?: ""
        return siren + threatLine(radiusKm)
    }
}

/**
 * Polls NEPTUN and turns it into an [AirState] relative to the car's current position.
 * Runs on the caller's coroutine (do it on a background dispatcher); cancel to stop.
 */
class AirAlertMonitor(
    private val context: Context,
    @Volatile var radiusKm: Double = 40.0,
    private val periodMs: Long = 10_000,
    private val client: NeptunClient = NeptunClient(),
    private val regions: RegionLocator = RegionLocator(context),
) {
    suspend fun run(onState: (AirState) -> Unit) {
        while (coroutineContext.isActive) {
            onState(withContext(Dispatchers.IO) { poll() })
            delay(periodMs)
        }
    }

    private fun poll(): AirState {
        val fix = Geo.lastKnown(context)
        return try {
            val threats = client.threats()
            val alerts = client.oblastAlerts()
            val alertKeys = alerts.map { it.key }.toSet()
            if (fix == null) {
                AirState(null, null, null, false, emptyList(), null, threats.list.size, threats.serverTime,
                    "немає GPS-координат авто", all = threats.list, alertKeys = alertKeys)
            } else {
                val (lat, lon) = fix
                val ranked = threats.list
                    .map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }
                    .sortedBy { it.second }
                val nearest = ranked.firstOrNull()
                val approaching = nearest?.let { (t, _) ->
                    t.heading?.let { h ->
                        Geo.angleDiff(Geo.bearingDeg(t.lat, t.lon, lat, lon), h.toDouble()) < 60
                    } ?: false
                } ?: false
                val within = ranked.filter { it.second <= radiusKm }
                val oblastKey = regions.oblastKeyAt(lat, lon)
                val oblastAlert = oblastKey?.let { k -> alerts.firstOrNull { it.key == k } }
                AirState(fix, nearest?.first, nearest?.second, approaching, within, oblastAlert,
                    threats.list.size, threats.serverTime, null, all = threats.list, alertKeys = alertKeys)
            }
        } catch (e: Exception) {
            AirState(fix, null, null, false, emptyList(), null, 0, null,
                "нема зв'язку з NEPTUN (${e.message ?: e.javaClass.simpleName})")
        }
    }
}
