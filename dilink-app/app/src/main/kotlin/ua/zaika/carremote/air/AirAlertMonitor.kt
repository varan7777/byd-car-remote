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
    val targets: List<Sighting> = emptyList(),   // Air Force «курс на X» with no known position: X, for the map
    val targetNear: Sighting? = null,            // such a target inside the alarm radius
    val alertsUnknown: Boolean = false,          // official alerts unavailable: the level shown is the last known
) {
    /** Worth a beep and a red banner: official siren for our oblast, or a threat within radius. */
    val isAlarm: Boolean get() = oblastAlert != null || within.isNotEmpty() || targetNear != null

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
        targetNear?.let { lines.add("🚨 Курс на ваш район: ${it.placeName.ifBlank { "поруч" }}") }
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
        within.isNotEmpty() || targetNear != null -> 3
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
        // A town in our radius named as the target is the most urgent thing to say, alert or not.
        if (within.isEmpty()) targetNear?.let { t -> return listOf("uvaha", threatClip(t.kind, t.jet), "kurs") }
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

    private fun threatClip(t: AirThreat) = threatClip(t.type, t.jet)

    private fun threatClip(type: String, jet: Boolean) = when {
        jet && type == "uav" -> "t_jet"
        type in setOf("uav", "recon", "fpv", "missile", "ballistic", "kab", "mig31k") -> "t_$type"
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
private val DRONES = setOf("uav", "recon", "fpv")

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

    /** The Air Force channel: first-hand, minutes ahead of NEPTUN, and it names jet drones. */
    private val airForce = AirForceFeed()
    private val gazetteer by lazy {
        runCatching { Gazetteer.parse(context.assets.open("geo/places.tsv").bufferedReader().readText()) }.getOrNull()
    }
    private var parsedFrom: List<AirForceParser.Post>? = null
    private var sightings: List<Sighting> = emptyList()

    /** Air Force sightings from the last 20 minutes (re-parsed only when new posts arrive). */
    private fun airForceSightings(): List<Sighting> {
        airForce.refreshAsync()
        val posts = airForce.posts
        if (posts !== parsedFrom) {
            parsedFrom = posts
            sightings = gazetteer?.let { g -> runCatching { AirForceParser.parse(posts, g) }.getOrNull() } ?: emptyList()
        }
        val now = nowMs()
        return sightings.filter { now - it.atMs in -120_000L..lifetimeMs(it) }
    }

    /**
     * How long a post's position stays meaningful: a jet or a missile covers 50+ km in five minutes,
     * so an older position would point the map (and the alarm) at an empty place.
     */
    private fun lifetimeMs(s: Sighting): Long = when {
        s.targetOnly -> 15 * 60_000L
        s.kind in setOf("missile", "ballistic", "mig31k") -> 5 * 60_000L
        s.jet -> 8 * 60_000L
        s.kind == "kab" -> 10 * 60_000L
        else -> 15 * 60_000L
    }

    /**
     * Air Force sightings of one drone come post after post («…курс на X», «X.», «Над Y.»): chain them
     * (same kind, next one within 60 km and 15 min of the last), so one drone is one marker with a
     * stable id and a trail, at its newest position.
     */
    private fun chains(af: List<Sighting>): List<List<Sighting>> {
        val out = ArrayList<MutableList<Sighting>>()
        for (s in af.filter { !it.targetOnly }.sortedBy { it.atMs }) {
            // Only a LATER post continues a drone: two lines of one post are two drones.
            val c = out.filter { c -> val l = c.last()
                sameKind(l.kind, s.kind) && s.atMs - l.atMs in 1..15 * 60_000 && Geo.distanceKm(l.lat, l.lon, s.lat, s.lon) <= 60
            }.minByOrNull { c -> Geo.distanceKm(c.last().lat, c.last().lon, s.lat, s.lon) }
            if (c != null) { chainIds.putIfAbsent(s.id, chainId(c.first())); c += s } else out += mutableListOf(s)
        }
        if (chainIds.size > 2000) chainIds.clear()
        return out
    }

    /** A drone keeps the id of the post that first reported it, even after that post ages out. */
    private val chainIds = HashMap<String, String>()
    private fun chainId(s: Sighting) = chainIds.getOrPut(s.id) { s.id }

    /**
     * NEPTUN tracks plus Air Force sightings. A drone the channel reports where NEPTUN already has one
     * (same kind, the NEAREST track within 30 km) enriches that track — and moves it to the channel's
     * position when that is newer; the rest become tracks of their own.
     */
    private fun merge(neptun: List<AirThreat>, af: List<Sighting>): List<AirThreat> {
        val out = neptun.toMutableList()
        val used = HashSet<Int>()
        absorbed.clear()
        for (chain in chains(af).sortedByDescending { it.last().atMs }) {
            val s = chain.last()
            val afId = "af:${chainId(chain.first())}"
            // How far the same drone can be from NEPTUN's (lagging) fix: its speed × the lag, 30–90 km.
            fun reach(t: AirThreat): Double {
                val lagH = kotlin.math.abs(s.atMs - (t.updatedMs ?: s.atMs)) / 3_600_000.0
                val kmh = if (s.jet) 500.0 else if (s.kind in DRONES) 180.0 else 700.0
                return (kmh * lagH + 15).coerceIn(30.0, 90.0).coerceAtLeast(s.uncertaintyKm)
            }
            // A drone once matched to a NEPTUN track stays with it, so it doesn't split in two
            // the moment NEPTUN's (lagging) fix drifts out of reach.
            val linked = trackOf[afId]?.let { id -> out.indexOfFirst { it.id == id && sameKind(it.type, s.kind) } }
                ?.takeIf { it >= 0 && it !in used && Geo.distanceKm(out[it].lat, out[it].lon, s.lat, s.lon) <= 150 }
            val i = linked ?: out.withIndex().filter { (j, t) ->
                j !in used && t.source == "neptun" && sameKind(t.type, s.kind) &&
                    Geo.distanceKm(t.lat, t.lon, s.lat, s.lon) <= reach(t)
            }.minByOrNull { (_, t) -> Geo.distanceKm(t.lat, t.lon, s.lat, s.lon) }?.index
            if (i != null) {
                used += i
                val t = out[i]
                trackOf[afId] = t.id; absorbed += afId
                val fresher = s.uncertaintyKm <= 20 && s.atMs > (t.updatedMs ?: 0)
                out[i] = t.copy(
                    airForce = s.text.takeIf { s.jet } ?: t.airForce,
                    explanation = t.explanation.ifBlank { s.text },
                    lat = if (fresher) s.lat else t.lat, lon = if (fresher) s.lon else t.lon,
                    updatedMs = if (fresher) s.atMs else t.updatedMs,
                    uncertaintyKm = if (fresher) s.uncertaintyKm else t.uncertaintyKm,
                    positionQuality = if (fresher) TrackMemory.AIR_FORCE else t.positionQuality,
                    heading = if (fresher) s.headingDeg ?: t.heading else t.heading ?: s.headingDeg,
                )
                continue
            }
            out += AirThreat(
                id = afId, type = s.kind, title = if (s.jet) "Реактивний БпЛА" else "",
                region = "", locality = s.placeName, lat = s.lat, lon = s.lon,
                heading = s.headingDeg ?: chain.takeLast(2).takeIf { it.size == 2 }?.let { (p, q) ->
                    if (p.lat == q.lat && p.lon == q.lon) null else Geo.bearingDeg(p.lat, p.lon, q.lat, q.lon).toInt() },
                confidence = if (s.uncertaintyKm <= 15) "medium" else "low", sourceCount = chain.size,
                uncertaintyKm = s.uncertaintyKm, explanation = s.text, updatedMs = s.atMs,
                positionQuality = TrackMemory.AIR_FORCE, airForce = s.text.takeIf { s.jet }, source = "airforce",
                trail = chain.map { Triple(it.lat, it.lon, it.atMs) },
            )
        }
        if (trackOf.size > 500) trackOf.clear()
        return out
    }

    /** Which NEPTUN track each Air Force drone was matched to; and those matched this poll. */
    private val trackOf = HashMap<String, String>()
    private val absorbed = HashSet<String>()
    private val memory = TrackMemory()

    /** A position too old to sound the alarm for: 15 min for a Shahed, 5 for anything jet-fast. */
    private fun stale(t: AirThreat): Boolean {
        val age = t.updatedMs?.let { nowMs() - it } ?: return false
        val fast = t.jet || t.type in setOf("missile", "ballistic", "mig31k")
        return age > (if (fast) 5 else 15) * 60_000L
    }

    /**
     * "Now" on NEPTUN's clock: the head unit's own clock can be minutes off, and every age test
     * (stale, lifetimes) would then hide real threats or keep old ones alive.
     */
    @Volatile private var clockOffsetMs = 0L
    private fun nowMs() = System.currentTimeMillis() + clockOffsetMs

    private fun sameKind(a: String, b: String) = a == b || (a in DRONES && b in DRONES)

    @Volatile private var dirty = false
    private val historyFile = java.io.File(context.filesDir, "track_history.json")

    /** History survives restarts and sleep (the head unit kills us often), so trails keep their shape. */
    private fun loadHistory() = runCatching {
        if (!historyFile.exists() || System.currentTimeMillis() - historyFile.lastModified() > 3_600_000) return@runCatching
        val o = org.json.JSONObject(historyFile.readText())
        for (id in o.keys()) {
            val a = o.getJSONArray(id)
            seen[id] = ArrayDeque((0 until a.length()).map { i ->
                val p = a.getJSONArray(i); Triple(p.getDouble(0), p.getDouble(1), p.getLong(2))
            })
        }
    }

    private fun saveHistory() = runCatching {
        if (!dirty) return@runCatching
        dirty = false
        val o = org.json.JSONObject()
        seen.forEach { (id, h) -> o.put(id, org.json.JSONArray(h.map { org.json.JSONArray(listOf(it.first, it.second, it.third)) })) }
        val tmp = java.io.File(historyFile.path + ".tmp")
        tmp.writeText(o.toString()); tmp.renameTo(historyFile)
    }

    init { loadHistory() }

    private fun withSpeed(list: List<AirThreat>, keepOthers: Boolean = false): List<AirThreat> {
        val now = System.currentTimeMillis()
        // When NEPTUN didn't answer this poll, keep its tracks' history for the next good one.
        if (!keepOthers && seen.keys.retainAll(list.map { it.id }.toSet())) dirty = true
        return list.map { t ->
            val h = seen.getOrPut(t.id) { ArrayDeque() }
            // NEPTUN's own earlier fixes fill in what we didn't see (before we started, or while asleep).
            t.trail.filter { p -> h.none { kotlin.math.abs(it.third - p.third) < 30_000 } }
                .forEach { h.addLast(it) }
            if (t.trail.isNotEmpty()) { val sorted = h.sortedBy { it.third }; h.clear(); h.addAll(sorted) }
            val at = t.updatedMs ?: now
            val last = h.lastOrNull()
            val sizeBefore = h.size; val lastBefore = last
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
            if (h.size != sizeBefore || h.lastOrNull() != lastBefore) dirty = true
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

    /** The last good /alerts answer: a NEPTUN hiccup must not read as «no alert» (and a false «відбій»). */
    private var lastAlerts: NeptunClient.Alerts? = null

    private fun poll(): AirState {
        val fix = Geo.lastKnown(context)
        // NEPTUN and the Air Force channel are independent: one failing doesn't silence the other.
        val neptun = runCatching { client.threats() }
        neptun.getOrNull()?.serverTime?.let { AirThreat.parseIso(it) }?.let { clockOffsetMs = it - System.currentTimeMillis() }
        val af = runCatching { airForceSightings() }.getOrDefault(emptyList())
        val fresh = runCatching { client.alerts() }.getOrNull()
        if (fresh != null) lastAlerts = fresh
        // Without fresh official alerts keep the last known level and say so: the service then never
        // steps down (no false «відбій»), while drones from NEPTUN or the channel still sound the alarm.
        val alertsUnknown = fresh == null
        val all = fresh ?: lastAlerts ?: NeptunClient.Alerts(emptyList(), emptyList())
        if (neptun.isFailure && fresh == null && af.isEmpty()) {
            val e = neptun.exceptionOrNull()
            return AirState(fix, null, null, false, emptyList(), null, 0, null,
                "нема зв'язку з NEPTUN (${e?.message ?: e?.javaClass?.simpleName})")
        }
        // Memory before speed: a fix the map has already moved past must not enter the track history.
        val merged = memory.apply(merge(neptun.getOrNull()?.list ?: emptyList(), af), nowMs(), absorbed, neptunDown = neptun.isFailure)
        val list = withSpeed(merged, keepOthers = neptun.isFailure)
        memory.markJets(list.filter { it.jet }.map { it.id })
        saveHistory()
        val serverTime = neptun.getOrNull()?.serverTime
        val alerts = all.oblasts
        val alertKeys = alerts.map { it.key }.toSet()
        val raionLevels = all.raions.associate { it.key to it.level }
        val targets = af.filter { it.targetOnly }
        if (fix == null) {
            return AirState(null, null, null, false, emptyList(), null, list.size, serverTime,
                "немає GPS-координат авто", all = list, alertKeys = alertKeys, raionLevels = raionLevels, targets = targets)
        }
        val (lat, lon) = fix
        // Only positions known well enough and fresh enough speak for the car: an oblast-wide guess
        // (±40–60 km) or an old fix of a fast threat must not drive the banner, the voice or the alarm.
        val ranked = list.filter { (it.source == "neptun" || it.uncertaintyKm <= 20) && !stale(it) }
            .map { it to Geo.distanceKm(lat, lon, it.lat, it.lon) }.sortedBy { it.second }
        val nearest = ranked.firstOrNull()
        val approaching = nearest?.let { (t, _) ->
            t.heading?.let { h -> Geo.angleDiff(Geo.bearingDeg(t.lat, t.lon, lat, lon), h.toDouble()) < 60 } ?: false
        } ?: false
        val within = ranked.filter { (_, km) -> km <= radiusKm }
        // «Курс на <town>» where the town is inside the radius: warn now, before anyone has a position.
        // Only a named town counts — «курс на Вінниччину» lands on the oblast's middle, not on us.
        val targetNear = targets.filter { it.uncertaintyKm <= 20 && Geo.distanceKm(lat, lon, it.lat, it.lon) <= radiusKm }
            .maxByOrNull { it.atMs }
        val oblastKey = regions.keyAt(lat, lon)
        val oblastAlert = oblastKey?.let { k -> alerts.firstOrNull { it.key == k } }
        val raionAlert = raions.keyAt(lat, lon)?.let { k -> all.raions.firstOrNull { it.key == k } }
        val levels = listOfNotNull(oblastAlert?.level, raionAlert?.level)
        val regionLevel = when { "red" in levels -> "red"; "yellow" in levels -> "yellow"; else -> null }
        return AirState(fix, nearest?.first, nearest?.second, approaching, within, oblastAlert,
            list.size, serverTime, null, all = list, alertKeys = alertKeys,
            regionLevel = regionLevel, raionLevels = raionLevels, targets = targets, targetNear = targetNear,
            alertsUnknown = alertsUnknown)
    }
}
