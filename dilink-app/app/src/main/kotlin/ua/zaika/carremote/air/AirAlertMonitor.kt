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
    val regionLevel: String? = null,             // "red" | "yellow" for our raion or oblast, null when calm
    val raionLevels: Map<String, String> = emptyMap(), // raion key → level, for shading the map
) {
    /** Worth a beep and a red banner: official siren for our oblast, or a threat within radius. */
    val isAlarm: Boolean get() = oblastAlert != null || within.isNotEmpty()

    private fun threatLine(radiusKm: Double): String {
        val n = nearest ?: return "🟢 Поблизу чисто"
        val km = nearestKm ?: return "🟢 Поблизу чисто"
        val f = fix ?: return "${n.label} ~${km.roundToInt()} км"
        val where = Geo.compass(Geo.bearingDeg(f.first, f.second, n.lat, n.lon))
        // "курс на вас" only makes sense when it's actually near — not for a drone 200 km away.
        val appr = if (approaching && km <= radiusKm) " · курс на вас" else ""
        val tag = if (km <= radiusKm) "🚨" else "•"
        return "$tag ${n.label} ~${km.roundToInt()} км ($where)$appr"
    }

    /** Full multi-line text for the map screen banner. */
    fun bannerText(radiusKm: Double): String {
        if (error != null) return "🛰️ $error"
        val lines = ArrayList<String>()
        if (oblastAlert != null || regionLevel != null) {
            val why = oblastAlert?.reasons?.firstOrNull()?.let { r -> " — $r" } ?: ""
            val where = oblastAlert?.name?.let { ": $it" } ?: ""
            lines.add("${AlertNotifier.levelEmoji(regionLevel, oblastAlert != null)} " +
                "${AlertNotifier.levelName(regionLevel, oblastAlert != null)}$where$why")
        }
        lines.add(threatLine(radiusKm))
        lines.add("Загроз у країні: $totalThreats")
        // NEPTUN is an aggregator: say how fresh its newest position is.
        all.mapNotNull { it.updatedMs }.maxOrNull()?.let { last ->
            val m = ((System.currentTimeMillis() - last) / 60_000).coerceAtLeast(0)
            lines.add("Дані NEPTUN: оновлено ${if (m < 1) "щойно" else "$m хв тому"} · неофіційні")
        }
        return lines.joinToString("\n")
    }

    /** How bad it is around the car: 0 calm, 1 yellow (or another official level), 2 red, 3 a threat in the radius. */
    fun alarmRank(): Int = when {
        within.isNotEmpty() -> 3
        regionLevel == "red" || (oblastAlert != null && regionLevel != "yellow") -> 2
        regionLevel != null -> 1
        else -> 0
    }

    /** The official part of [alarmRank]: 1 yellow, 2 red, 0 none (a nearby threat alone doesn't count). */
    fun officialRank(): Int = when {
        regionLevel == "red" || (oblastAlert != null && regionLevel != "yellow") -> 2
        regionLevel != null -> 1
        else -> 0
    }

    /** Spoken warning when things get worse, as recorded phrases (see [Speaker]). */
    fun voiceClips(speaker: Speaker): List<String> {
        if (within.isEmpty()) when {
            oblastAlert != null && regionLevel != "yellow" ->
                return listOfNotNull("tryvoha", speaker.regionClip(oblastAlert.name))
            regionLevel == "red" -> return listOf("tryvoha_rayon")
            regionLevel == "yellow" -> return listOf("zhovta")
            regionLevel != null -> return listOf("tryvoha_rayon")
        }
        val n = nearest ?: return listOf("tryvoha")
        val km = nearestKm?.roundToInt() ?: return listOf("uvaha", threatClip(n))
        val dist = if (km < 1) "poruch" else "km${km.coerceAtMost(100)}"
        return listOfNotNull("uvaha", threatClip(n), dist, "kurs".takeIf { approaching })
    }

    private fun threatClip(t: AirThreat) = when {
        t.jet -> "t_jet"
        t.type in setOf("uav", "recon", "fpv", "missile", "ballistic", "kab", "mig31k") -> "t_${t.type}"
        else -> "t_other"
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
    private val raions: RegionLocator = RegionLocator(context, client, "raions.geojson"),
) {
    /** Positions NEPTUN reported per track (when they changed), for speed when NEPTUN gives no trail. */
    private val seen = HashMap<String, ArrayDeque<Triple<Double, Double, Long>>>()

    private fun withSpeed(list: List<AirThreat>): List<AirThreat> {
        val now = System.currentTimeMillis()
        seen.keys.retainAll(list.map { it.id }.toSet())
        return list.map { t ->
            val h = seen.getOrPut(t.id) { ArrayDeque() }
            val at = t.updatedMs ?: now
            val last = h.lastOrNull()
            when {
                last == null -> h.addLast(Triple(t.lat, t.lon, at))
                last.first == t.lat && last.second == t.lon -> Unit
                // One fix a minute is plenty, so 30 points span the 30 minutes speed and trail need.
                at - last.third >= 60_000 || h.size == 1 -> h.addLast(Triple(t.lat, t.lon, at))
                else -> { h.removeLast(); h.addLast(Triple(t.lat, t.lon, at)) }
            }
            // Age against the newest fix, not the head unit's clock, so the latest point always stays.
            while (h.size > 30 || (h.size > 1 && h.last().third - h.first().third > 1_800_000)) h.removeFirst()
            val path = h.map { it.first to it.second }
            val speed = t.speedKmh ?: AirThreat.steadySpeed(h.toList(), t.uncertaintyKm)
            t.copy(speedKmh = speed, path = path)
        }
    }

    suspend fun run(onState: (AirState) -> Unit) {
        while (coroutineContext.isActive) {
            onState(withContext(Dispatchers.IO) { poll() })
            delay(periodMs)
        }
    }

    private fun poll(): AirState {
        val fix = Geo.lastKnown(context)
        return try {
            val threats = client.threats().let { th -> th.copy(list = withSpeed(th.list)) }
            val all = client.alerts()
            val alerts = all.oblasts
            val alertKeys = alerts.map { it.key }.toSet()
            val raionLevels = all.raions.associate { it.key to it.level }
            if (fix == null) {
                AirState(null, null, null, false, emptyList(), null, threats.list.size, threats.serverTime,
                    "немає GPS-координат авто", all = threats.list, alertKeys = alertKeys, raionLevels = raionLevels)
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
                val oblastKey = regions.keyAt(lat, lon)
                val oblastAlert = oblastKey?.let { k -> alerts.firstOrNull { it.key == k } }
                val raionAlert = raions.keyAt(lat, lon)?.let { k -> all.raions.firstOrNull { it.key == k } }
                val levels = listOfNotNull(oblastAlert?.level, raionAlert?.level)
                val regionLevel = when { "red" in levels -> "red"; "yellow" in levels -> "yellow"; else -> null }
                AirState(fix, nearest?.first, nearest?.second, approaching, within, oblastAlert,
                    threats.list.size, threats.serverTime, null, all = threats.list, alertKeys = alertKeys,
                    regionLevel = regionLevel, raionLevels = raionLevels)
            }
        } catch (e: Exception) {
            AirState(fix, null, null, false, emptyList(), null, 0, null,
                "нема зв'язку з NEPTUN (${e.message ?: e.javaClass.simpleName})")
        }
    }
}
