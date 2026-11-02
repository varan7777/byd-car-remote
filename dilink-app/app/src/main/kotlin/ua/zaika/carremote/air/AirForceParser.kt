package ua.zaika.carremote.air

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One settlement of the bundled gazetteer (assets/geo/places.tsv). [kind]: c city, t town, v village. */
data class Place(val name: String, val lat: Double, val lon: Double, val kind: Char, val oblast: String)

/**
 * A threat placed on the map from one line of an Air Force post.
 * [uncertaintyKm] is how far the real position may be: ~5–15 km for a named settlement, 40–60 km
 * when the post names only an oblast (and maybe its side). [headingDeg] is 0 = north, clockwise.
 */
data class Sighting(
    val id: String,
    val atMs: Long,
    val kind: String, // uav | missile | ballistic | kab | mig31k | recon | unknown
    val jet: Boolean,
    val lat: Double,
    val lon: Double,
    val uncertaintyKm: Double,
    val headingDeg: Int?,
    val destLat: Double?,
    val destLon: Double?,
    val text: String,
    val placeName: String,
    /** True when the post names only where the threat is heading: lat/lon are that target, not its position. */
    val targetOnly: Boolean = false,
)

/** Settlement names indexed by a case-insensitive stem, so «Вінницю», «Каневі», «Черкас» find their place. */
class Gazetteer(val places: List<Place>) {

    internal class Region(
        val key: String, val lat: Double, val lon: Double,
        val latLo: Double, val latHi: Double, val lonLo: Double, val lonHi: Double,
    ) {
        /** The middle of the oblast moved toward [side] (deg, -1 = centre), staying inside its core. */
        fun point(side: Int?): Pair<Double, Double> {
            if (side == null || side < 0) return lat to lon
            val r = Math.toRadians(side.toDouble())
            val dy = cos(r); val dx = sin(r)
            val la = lat + 0.7 * dy * (if (dy > 0) latHi - lat else lat - latLo)
            val lo = lon + 0.7 * dx * (if (dx > 0) lonHi - lon else lon - lonLo)
            return la to lo
        }
    }

    private val index: Map<String, List<Place>>
    internal val regions: Map<String, Region>

    init {
        val idx = HashMap<String, MutableList<Place>>()
        for (p in places) for (k in Names.nameKeys(p.name)) idx.getOrPut(k) { ArrayList() }.add(p)
        index = idx
        regions = places.groupBy { it.oblast }.mapValues { (key, ps) ->
            val lats = ps.map { it.lat }.sorted(); val lons = ps.map { it.lon }.sorted()
            fun q(l: List<Double>, f: Double) = l[((l.size - 1) * f).roundToInt()]
            Region(key, lats.average(), lons.average(), q(lats, 0.1), q(lats, 0.9), q(lons, 0.1), q(lons, 0.9))
        }
    }

    /** Places whose name matches these words in any grammatical case. */
    internal fun lookup(words: List<String>): List<Place> =
        Names.textKeys(words).flatMap { index[it].orEmpty() }.distinct()

    /**
     * The place a mention means: one in a [preferred] oblast if any, else the only city, the only
     * town, or the only village of that name. Ambiguous names give null.
     */
    internal fun choose(cands: List<Place>, preferred: List<String>): Place? {
        if (cands.isEmpty()) return null
        for (ob in preferred) {
            val inOb = cands.filter { sameOblast(ob, it.oblast) }
            if (inOb.isNotEmpty()) return inOb.minByOrNull { rank(it.kind) }
        }
        for (k in charArrayOf('c', 't', 'v')) {
            val tier = cands.filter { it.kind == k }
            if (tier.size == 1) return tier[0]
            if (tier.size > 1) return if (tier.distinctBy { it.oblast }.size == 1) tier[0] else null
        }
        return null
    }

    internal fun capital(oblast: String): Place? {
        val name = CAPITALS[oblast] ?: return null
        return choose(lookup(name.split(' ')), listOf(oblast))
    }

    /** The closest settlement to a point (to tell which oblast a position falls in). */
    fun nearest(lat: Double, lon: Double): Place? {
        val k = cos(Math.toRadians(lat))
        return places.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) * k * k }
    }

    companion object {
        fun parse(tsv: String): Gazetteer = Gazetteer(tsv.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            val lat = f[1].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[2].toDoubleOrNull() ?: return@mapNotNull null
            Place(f[0], lat, lon, f[3].firstOrNull() ?: 'v', f[4])
        }.toList())

        internal fun sameOblast(a: String, b: String) =
            a == b || (a == "київська" && b == "м. київ") || (a == "м. київ" && b == "київська")

        private fun rank(k: Char) = when (k) { 'c' -> 0; 't' -> 1; else -> 2 }

        private val CAPITALS = mapOf(
            "вінницька" to "Вінниця", "волинська" to "Луцьк", "дніпропетровська" to "Дніпро",
            "донецька" to "Донецьк", "житомирська" to "Житомир", "закарпатська" to "Ужгород",
            "запорізька" to "Запоріжжя", "івано-франківська" to "Івано-Франківськ", "київська" to "Київ",
            "кіровоградська" to "Кропивницький", "луганська" to "Луганськ", "львівська" to "Львів",
            "миколаївська" to "Миколаїв", "одеська" to "Одеса", "полтавська" to "Полтава",
            "рівненська" to "Рівне", "сумська" to "Суми", "тернопільська" to "Тернопіль",
            "харківська" to "Харків", "херсонська" to "Херсон", "хмельницька" to "Хмельницький",
            "черкаська" to "Черкаси", "чернівецька" to "Чернівці", "чернігівська" to "Чернігів",
        )
    }
}

/** Ukrainian name stems: endings dropped and the і/о/е alternation (Канів–Каневі, Ріг–Рогу) folded. */
internal object Names {
    private val SUFFIXES = listOf(
        "ого", "ому", "ими",
        "ою", "ею", "ої", "ій", "ий", "ах", "ях", "ів", "ам", "ям", "ом", "ем", "им",
        "а", "я", "у", "ю", "е", "є", "і", "ї", "и", "о", "ь", "й",
    )
    private const val VOWELS = "аеєиіїоуюяь"

    fun norm(s: String) = s.lowercase().replace("'", "").replace("’", "").replace("ʼ", "").replace("`", "")

    private fun alt(s: String): String {
        if (s.length >= 3) {
            val p = s[s.length - 2]; val l = s.last()
            if (p in "іоеєї" && l !in VOWELS) return s.substring(0, s.length - 2) + "*" + l
        }
        return s
    }

    private fun namePart(p: String): List<String> {
        val n = norm(p)
        val suf = SUFFIXES.firstOrNull { it != "ів" && n.endsWith(it) && n.length - it.length >= 3 }
        val out = mutableListOf(alt(if (suf != null) n.dropLast(suf.length) else n))
        if (n.endsWith("ець") && n.length > 4) out += n.dropLast(3) + "ц" // Липовець – Липовця
        return out
    }

    private fun textPart(p: String): Set<String> {
        val n = norm(p)
        val out = linkedSetOf(alt(n))
        for (s in SUFFIXES) if (n.endsWith(s) && n.length - s.length >= 3) out += alt(n.dropLast(s.length))
        return out
    }

    private fun word(w: String, part: (String) -> Collection<String>): List<String> =
        w.split('-').filter { it.isNotEmpty() }.fold(listOf("")) { acc, piece ->
            val ks = part(piece)
            // «Петропавлівська-Борщагівка» and «Петропавлівська Борщагівка» are the same name.
            acc.flatMap { a -> ks.map { if (a.isEmpty()) it else "$a $it" } }
        }

    private fun words(ws: List<String>, part: (String) -> Collection<String>): List<String> =
        ws.fold(listOf("")) { acc, w ->
            val ks = word(w, part)
            acc.flatMap { a -> ks.map { if (a.isEmpty()) it else "$a $it" } }
        }

    fun nameKeys(name: String): List<String> = words(name.trim().split(Regex("\\s+")), ::namePart)
    fun textKeys(ws: List<String>): Set<String> = words(ws, ::textPart).toSet()
}

object AirForceParser {
    data class Post(val id: String, val atMs: Long, val text: String)

    internal class Analysis(val sightings: List<Sighting>, val resolved: List<String>, val dropped: List<String>)

    fun parse(posts: List<Post>, gaz: Gazetteer): List<Sighting> = analyze(posts, gaz).sightings

    /** [parse] plus which threat lines were placed and which were not (for coverage checks). */
    internal fun analyze(posts: List<Post>, gaz: Gazetteer): Analysis {
        val out = ArrayList<Sighting>(); val resolved = ArrayList<String>(); val dropped = ArrayList<String>()
        for (post in posts.sortedBy { it.atMs }) {
            val lines = post.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val followUp = lines.none { kindOf(it) != null } && post.text.length <= 100 && lines.size <= 2
            val recent = if (followUp) out.filter { post.atMs - it.atMs in 0..30 * 60_000L } else emptyList()
            if (followUp && recent.isEmpty()) continue
            // The thread a follow-up continues: the newest sighting first (the last line of the newest post).
            val anchors = recent.asReversed()
            var header: Pair<String, Boolean>? = null
            var headerOblast: String? = recent.lastOrNull()?.let { gaz.nearest(it.lat, it.lon)?.oblast }
            var headerCity: Place? = null
            lines.forEachIndexed { idx, line ->
                if (line.length > 220 || line.startsWith("❗") || line.startsWith("💥")) return@forEachIndexed
                val own = kindOf(line)
                val toks = tokens(line)
                val words = toks.filter { !it.punct }
                if (line.startsWith("⚠") && own == null && words.size in 1..3) {
                    headerCity = gaz.choose(gaz.lookup(words.map { it.raw }), emptyList())
                    return@forEachIndexed
                }
                if (line.endsWith(":")) {
                    header = own ?: header
                    toks.indices.firstNotNullOfOrNull { oblastAt(toks, it) }?.let { headerOblast = it.key }
                    return@forEachIndexed
                }
                val kind = own ?: header ?: if (followUp) Pair("unknown", false) else return@forEachIndexed
                val fixes = LineReader(toks, gaz, headerOblast, headerCity, followUp, anchors).fixes()
                val before = out.size
                fixes.forEachIndexed { k, f0 ->
                    var f = f0
                    var kd = kind
                    if (followUp) {
                        val near: Sighting
                        if (f.ctx) {
                            // «Курс на захід.»: the newest threat turned; a follow-up with nothing in it says nothing.
                            near = anchors.first()
                            if (f.heading == null || near.targetOnly) return@forEachIndexed
                            f = Fix(near.lat, near.lon, near.uncertaintyKm, f.heading, null, near.placeName, false)
                        } else {
                            // «На Липовець.», «Теремки.» continue the thread: the newest threat near that place.
                            val target = f.dest ?: (f.lat to f.lon)
                            val radius = if (f.fromDest) FOLLOW_DEST_KM else FOLLOW_HERE_KM
                            near = anchors.filter { distKm(it.lat, it.lon, target.first, target.second) <= radius }
                                .minByOrNull { distKm(it.lat, it.lon, target.first, target.second) }
                                ?: return@forEachIndexed // a same-named village far from the thread is a different place
                            val d = distKm(near.lat, near.lon, target.first, target.second)
                            if (near.targetOnly) {
                                // The thread is itself only a target: nothing to move from.
                            } else if (f.fromDest && d < 100) {
                                f = Fix(near.lat, near.lon, near.uncertaintyKm,
                                    bearing(near.lat, near.lon, target.first, target.second), target, near.placeName, false)
                            } else if (!f.fromDest && f.heading == null && d > 2) {
                                // It moved from the previous point to here.
                                f = Fix(f.lat, f.lon, f.unc, bearing(near.lat, near.lon, f.lat, f.lon), null, f.label, false)
                            }
                        }
                        kd = near.kind to near.jet
                    }
                    out += Sighting(
                        id = if (k == 0) "${post.id}:$idx" else "${post.id}:$idx.$k",
                        atMs = post.atMs, kind = kd.first, jet = kd.second,
                        lat = f.lat, lon = f.lon, uncertaintyKm = f.unc, headingDeg = f.heading,
                        destLat = f.dest?.first, destLon = f.dest?.second, text = line, placeName = f.label,
                        targetOnly = f.targetOnly,
                    )
                }
                if (out.size > before) resolved += line else dropped += line
            }
        }
        return Analysis(out, resolved, dropped)
    }

    /** How far from the thread a follow-up's place may be: where it is now, and where it is heading. */
    private const val FOLLOW_HERE_KM = 60.0
    private const val FOLLOW_DEST_KM = 150.0
    /** «на Київ з півночі»: how far out on that side it is placed. */
    private const val FROM_SIDE_KM = 25.0

    // ---- threat type ----

    private val KAB = Regex("(?<!\\p{L})каб")

    internal fun kindOf(line: String): Pair<String, Boolean>? {
        val l = line.lowercase()
        return when {
            KAB.containsMatchIn(l) || (l.contains("керован") && l.contains("бомб")) -> "kab" to false
            l.contains("балістик") || l.contains("балістичн") -> "ballistic" to false
            l.contains("міг-31") || l.contains("міг31") || l.contains("кинджал") -> "mig31k" to false
            l.contains("розвід") -> "recon" to false
            l.contains("реактивн") || line.contains("🏍") -> "uav" to true
            l.contains("бандерол") -> "uav" to true
            l.contains("бпла") || l.contains("шахед") || l.contains("безпілот") || l.contains("дрон") ||
                l.contains("гербер") || line.contains("🛵") -> "uav" to false
            l.contains("крилат") || l.contains("ракет") || line.contains("🚀") -> "missile" to false
            line.contains("💣") -> "kab" to false
            else -> null
        }
    }

    // ---- tokens ----

    internal class Tok(val raw: String, val low: String, val punct: Boolean) {
        val cap get() = !punct && raw[0].isUpperCase()
        override fun toString() = raw
    }

    private val TOKEN = Regex("[\\p{L}\\p{N}'][\\p{L}\\p{N}'\\-]*|[,;:/()➡]|(?<![\\p{L}\\p{N}])[-–—](?![\\p{L}\\p{N}])")

    internal fun tokens(line: String): List<Tok> {
        var s = line.replace("\uFE0F", "").replace("➡", " ➡ ")
        s = s.replace(Regex("(?<!\\p{L})н\\.\\s*п\\.?", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("(?<!\\p{L})(м|с|смт)\\.\\s*(?=\\p{Lu})"), " ")
        s = s.replace('’', '\'').replace('ʼ', '\'').replace('`', '\'')
        return TOKEN.findAll(s).mapNotNull { m ->
            val v = m.value
            if (v.length == 1 && v[0] in "-–—") return@mapNotNull Tok("-", "-", true)
            val raw = v.trimEnd('-')
            if (raw.isEmpty()) return@mapNotNull null
            val punct = !raw[0].isLetterOrDigit() && raw[0] != '\''
            Tok(raw, if (punct) raw else Names.norm(raw), punct)
        }.toList()
    }

    // ---- oblasts ----

    internal class ObForm(val key: String, val case: Char)

    private val SHCHYNA = mapOf("а" to 'N', "у" to 'A', "и" to 'G', "і" to 'L', "ою" to 'I')
    private val ADJ = mapOf("а" to 'N', "у" to 'A', "ої" to 'G', "ій" to 'L', "их" to 'G', "і" to 'G', "ою" to 'I')
    private class Ob(val key: String, val nouns: List<Pair<String, Map<String, Char>>>, val adj: String)
    private fun ob(key: String, adj: String, vararg nouns: String) = Ob(key, nouns.map { it to SHCHYNA }, adj)
    private val OBLASTS = listOf(
        ob("вінницька", "вінницьк", "вінниччин"),
        Ob("волинська", listOf("волин" to mapOf("ь" to 'A', "і" to 'L', "ню" to 'I')), "волинськ"),
        ob("дніпропетровська", "дніпропетровськ", "дніпропетровщин", "дніпровщин"),
        ob("донецька", "донецьк", "донеччин"),
        ob("житомирська", "житомирськ", "житомирщин"),
        Ob("закарпатська", listOf("закарпатт" to mapOf("я" to 'A', "і" to 'L')), "закарпатськ"),
        ob("запорізька", "запорізьк", "запоріжжин"),
        Ob("івано-франківська", listOf("івано-франківщин" to SHCHYNA, "прикарпатт" to mapOf("я" to 'A', "і" to 'L')), "івано-франківськ"),
        ob("київська", "київськ", "київщин"),
        ob("кіровоградська", "кіровоградськ", "кіровоградщин"),
        ob("луганська", "луганськ", "луганщин"),
        ob("львівська", "львівськ", "львівщин"),
        ob("миколаївська", "миколаївськ", "миколаївщин"),
        ob("одеська", "одеськ", "одещин"),
        ob("полтавська", "полтавськ", "полтавщин"),
        ob("рівненська", "рівненськ", "рівненщин"),
        ob("сумська", "сумськ", "сумщин"),
        ob("тернопільська", "тернопільськ", "тернопільщин"),
        ob("харківська", "харківськ", "харківщин"),
        ob("херсонська", "херсонськ", "херсонщин"),
        ob("хмельницька", "хмельницьк", "хмельниччин"),
        ob("черкаська", "черкаськ", "черкащин"),
        ob("чернівецька", "чернівецьк", "чернівеччин", "буковин"),
        ob("чернігівська", "чернігівськ", "чернігівщин"),
        Ob("автономна республіка крим", listOf("крим" to mapOf("" to 'A', "у" to 'L')), "кримськ"),
    )

    internal fun oblastAt(toks: List<Tok>, i: Int): ObForm? {
        val t = toks[i]; if (t.punct) return null
        val w = t.low
        for (o in OBLASTS) {
            for ((stem, ends) in o.nouns) if (w.startsWith(stem)) {
                if (stem == "крим" && !t.cap) continue
                ends[w.substring(stem.length)]?.let { return ObForm(o.key, it) }
            }
            if (w.startsWith(o.adj)) {
                val case = ADJ[w.substring(o.adj.length)] ?: continue
                val ahead = toks.drop(i + 1).filter { !it.punct }.take(3)
                if (ahead.any { it.low.startsWith("област") }) return ObForm(o.key, case)
            }
        }
        return null
    }

    // ---- sides ----

    private class SideWord(val deg: Int, val form: Char) // form: A acc, L loc, G gen, J adjective, C comparative
    private val COMPOUND = Regex("^(північно|південно)-(східн|західн)\\p{L}*$")

    private fun sideWord(w: String): SideWord? {
        COMPOUND.find(w)?.let { m ->
            val n = m.groupValues[1] == "північно"; val e = m.groupValues[2] == "східн"
            return SideWord(if (n) (if (e) 45 else 315) else (if (e) 135 else 225), 'J')
        }
        return when (w) {
            "північ" -> SideWord(0, 'A'); "півночі" -> SideWord(0, 'L')
            "південь" -> SideWord(180, 'A'); "півдні" -> SideWord(180, 'L'); "півдня" -> SideWord(180, 'G')
            "схід" -> SideWord(90, 'A'); "сході" -> SideWord(90, 'L'); "сходу" -> SideWord(90, 'G')
            "захід" -> SideWord(270, 'A'); "заході" -> SideWord(270, 'L'); "заходу" -> SideWord(270, 'G')
            "центр" -> SideWord(-1, 'A'); "центрі" -> SideWord(-1, 'L'); "центру" -> SideWord(-1, 'G')
            "північніше" -> SideWord(0, 'C'); "південніше" -> SideWord(180, 'C')
            "східніше" -> SideWord(90, 'C'); "західніше" -> SideWord(270, 'C')
            else -> when {
                w.startsWith("північн") -> SideWord(0, 'J')
                w.startsWith("південн") -> SideWord(180, 'J')
                w.startsWith("східн") -> SideWord(90, 'J')
                w.startsWith("західн") -> SideWord(270, 'J')
                w.startsWith("центральн") -> SideWord(-1, 'J')
                else -> null
            }
        }
    }

    // ---- one line ----

    internal class Fix(
        val lat: Double, val lon: Double, val unc: Double, val heading: Int?,
        val dest: Pair<Double, Double>?, val label: String, val fromDest: Boolean,
        /** Only the target is known: lat/lon are the target itself. */
        val targetOnly: Boolean = false,
        /** Placed only by the oblast the context names, nothing from the line itself. */
        val ctx: Boolean = false,
    )

    /** A place, oblast or bare side the line names, with its role: L where it is, D where it goes, O where it came from. */
    private class Mention(
        var role: Char, val bare: Boolean, val type: Char, // P place, R oblast, S side only
        val tok: Int, val via: String, val sep: String,
        val cands: List<Place> = emptyList(), var place: Place? = null,
        val oblast: String? = null, var side: Int? = null, var near: String? = null,
    )

    private val LOC_PREPS = setOf("повз", "біля", "поблизу", "над", "через", "між", "межі", "районі", "околицях", "околиці", "неподалік", "поруч")
    private val FROM = setOf("з", "із", "зі")
    private val STOP = setOf("бпла", "каб", "каби", "чорного", "азовського", "перебувайте", "ситуація", "увага")

    private class LineReader(
        val toks: List<Tok>, val gaz: Gazetteer,
        val headerOblast: String?, val headerCity: Place?, val followUp: Boolean,
        /** For a follow-up post: the thread's recent sightings, newest first; names resolve near them. */
        val anchors: List<Sighting> = emptyList(),
    ) {
        val ms = ArrayList<Mention>()
        val lineOblasts = ArrayList<String>()
        /** Oblasts named where it is or came from (not where it goes): they pick the right same-named village. */
        val hereOblasts = ArrayList<String>()
        var sea = false

        private var role = 'S'
        private var bare = false
        private var via = ""
        private var sep = ""
        private var pendingSide: Pair<Int, Char>? = null

        private fun next(i: Int): Int { var j = i + 1; while (j < toks.size && toks[j].punct) j++; return j }
        private fun prev(i: Int): Tok? { var j = i - 1; while (j >= 0 && toks[j].punct) j--; return if (j >= 0) toks[j] else null }
        private fun low(i: Int) = if (i < toks.size) toks[i].low else ""

        private fun setRole(r: Char, v: String, b: Boolean = false) { flushSide(); role = r; via = v; bare = b; sep = "" }

        /** «на X» without a course word before it: a destination, unless a later «курс …» makes X the position. */
        private fun bareDest() { if (!(role == 'D' && !bare)) { flushSide(); role = 'D'; bare = true; via = "на"; sep = "" } }

        private fun flushSide() {
            val ps = pendingSide ?: return
            pendingSide = null
            val last = ms.lastOrNull()
            if (ps.second == 'L' && last != null && last.role == 'L' && last.type == 'P' && last.side == null) { last.side = ps.first; return }
            ms += Mention(ps.second, false, 'S', -1, via, sep, side = ps.first)
        }

        private fun add(m: Mention) {
            pendingSide?.let { if (it.second == m.role) { m.side = it.first; pendingSide = null } }
            if (m.type == 'R' && m.side == null && sep == "та") {
                val last = ms.lastOrNull()
                if (last != null && last.type == 'R' && last.role == m.role && last.side != null) m.side = last.side
            }
            ms += m; sep = ""
        }

        init { scan() }

        private fun scan() {
            var i = 0
            while (i < toks.size) {
                val t = toks[i]
                if (t.punct) {
                    when (t.raw) { "/" -> sep = "/"; "(", ")" -> {}; else -> { flushSide(); sep = "," } }
                    i++; continue
                }
                val w = t.low
                when {
                    w == "та" || w == "і" || w == "й" -> { sep = "та"; i++; continue }
                    w == "курс" || w == "курсом" || w == "➡" -> { setRole('D', "курс"); i++; continue }
                    w == "напрямку" || w == "напрямок" -> { setRole('D', "курс"); i++; continue }
                    w in LOC_PREPS -> { setRole('L', w); i++; continue }
                    w in FROM -> { setRole('O', "з"); i++; continue }
                    w == "від" || w == "у" || w == "в" -> { i++; continue }
                    w == "на" -> { i = placeQualifier(i) ?: run { onNa(i); i + 1 }; continue }
                }
                val sw = sideWord(w)
                if (sw != null) { i = onSide(i, sw); continue }
                if (w.startsWith("обласн") && low(next(i)).startsWith("центр")) {
                    val ob = lineOblasts.lastOrNull() ?: headerOblast
                    val cap = ob?.let { gaz.capital(it) }
                    if (cap != null) add(Mention(role, bare, 'P', i, via, sep, listOf(cap), cap))
                    i = next(i) + 1; continue
                }
                if (w == "містом" || w == "міста" || w == "місто") {
                    if (headerCity != null) add(Mention(if (role == 'S') 'L' else role, bare, 'P', i, via, sep, listOf(headerCity), headerCity))
                    i++; continue
                }
                if (role == 'O' && (w == "моря" || w == "акваторії" || w == "морем" || w == "моря")) { sea = true; i++; continue }
                val ob = oblastAt(toks, i)
                if (ob != null) { onOblast(i, ob); i++; continue }
                if (t.cap && w !in STOP && (role != 'S' || followUp)) {
                    val r = if (role == 'S') 'L' else role
                    val n2 = if (i + 1 < toks.size && toks[i + 1].cap) gaz.lookup(listOf(t.raw, toks[i + 1].raw)) else emptyList()
                    val cands = n2.ifEmpty { gaz.lookup(listOf(t.raw)) }
                    if (cands.isNotEmpty()) {
                        add(Mention(r, bare, 'P', i, via, sep, cands))
                        i += if (n2.isNotEmpty()) 2 else 1
                        continue
                    }
                }
                i++
            }
            flushSide()
        }

        /**
         * «курсом на Козятин на півночі Вінниччини»: the side and oblast after a target say where that
         * target is, not where the threat is. Returns the index after them, or null.
         */
        private fun placeQualifier(i: Int): Int? {
            val last = ms.lastOrNull() ?: return null
            if (role != 'D' || last.role != 'D' || last.type != 'P' || i - last.tok > 2) return null
            val j = next(i); if (j >= toks.size) return null
            val sw = sideWord(toks[j].low) ?: return null
            if (sw.form != 'L') return null
            val k = next(j); if (k >= toks.size) return null
            val ob = oblastAt(toks, k) ?: return null
            if (ob.case != 'G' || last.cands.none { Gazetteer.sameOblast(ob.key, it.oblast) }) return null
            last.near = ob.key
            lineOblasts += ob.key
            return k + 1
        }

        private fun onNa(i: Int) {
            val j = next(i); if (j >= toks.size) return
            val nw = toks[j].low
            val sw = sideWord(nw)
            when {
                sw != null -> when (sw.form) {
                    'A' -> bareDest()
                    'L', 'G', 'C' -> setRole('L', "на")
                    else -> {}
                }
                nw == "межі" -> {}
                else -> {
                    val ob = oblastAt(toks, j)
                    if (ob != null) {
                        when (ob.case) {
                            'L' -> if (role != 'D') setRole('L', "на")
                            'A', 'N' -> bareDest()
                            else -> {}
                        }
                    } else if (toks[j].cap || nw.startsWith("обласн")) bareDest()
                }
            }
        }

        private fun onSide(i: Int, sw: SideWord): Int {
            val p = prev(i)?.low
            var j = i + 1
            val r = when {
                p != null && p in FROM -> 'O'
                sw.form == 'J' -> {
                    val n = next(i); val nw = low(n)
                    when {
                        nw == "напрямку" -> { j = n + 1; if (p == "на") 'L' else 'D' }
                        nw == "напрямок" || nw == "курсом" || nw == "курс" -> { j = n + 1; 'D' }
                        nw == "частині" || nw == "частина" -> { j = n + 1; 'L' }
                        p == "курс" || p == "курсом" -> 'D'
                        p == "на" -> 'L'
                        else -> 'D'
                    }
                }
                sw.form == 'A' -> 'D'
                else -> 'L'
            }
            if (r != role) {
                if (r == 'D') { if (!(role == 'D' && !bare)) setRole('D', "на", true) } else setRole(r, if (r == 'O') "з" else "на")
            }
            if (pendingSide == null) pendingSide = sw.deg to r
            return j
        }

        private fun onOblast(i: Int, ob: ObForm) {
            lineOblasts += ob.key
            if (role != 'D') hereOblasts += ob.key
            val last = ms.lastOrNull { it.type == 'P' }
            if (role == 'S' && !followUp) return // «Київщина: …» — context only
            // «повз Канів на Черкащині», «Переяслав (Київщина)», «на Теплик на Вінниччині»: names the place's oblast.
            if (last != null && i - last.tok <= 4 && (ob.case == 'L' || ob.case == 'N') && pendingSide == null) {
                last.near = ob.key; return
            }
            if (role == 'D' && ob.case == 'L') return
            add(Mention(if (role == 'S') 'L' else role, bare, 'R', i, via, sep, oblast = ob.key))
        }

        // ---- resolution ----

        private fun point(m: Mention): Pair<Double, Double>? = when (m.type) {
            'P' -> m.place?.let { it.lat to it.lon }
            'R' -> gaz.regions[m.oblast]?.point(m.side)
            else -> null
        }

        /**
         * A place a follow-up names: first the candidate near the thread's newest sighting (within
         * [FOLLOW_HERE_KM]), then the one the name means on its own if the thread is not far from it,
         * and for a destination any candidate within [FOLLOW_DEST_KM] of the thread. Else none.
         */
        private fun nearAnchor(m: Mention, preferred: List<String>): Place? {
            fun closest(radius: Double): Place? {
                for (a in anchors) {
                    val best = m.cands.minByOrNull { distKm(a.lat, a.lon, it.lat, it.lon) } ?: return null
                    if (distKm(a.lat, a.lon, best.lat, best.lon) <= radius) return best
                }
                return null
            }
            val radius = if (m.role == 'D') FOLLOW_DEST_KM else FOLLOW_HERE_KM
            closest(FOLLOW_HERE_KM)?.let { return it }
            gaz.choose(m.cands, preferred)?.let { p -> if (anchors.any { distKm(it.lat, it.lon, p.lat, p.lon) <= radius }) return p }
            return if (m.role == 'D') closest(FOLLOW_DEST_KM) else null
        }

        fun fixes(): List<Fix> {
            val here = hereOblasts + listOfNotNull(headerOblast, headerCity?.oblast)
            for (m in ms) if (m.type == 'P' && m.place == null) {
                val preferred = listOfNotNull(m.near) + here + (if (m.role == 'D') lineOblasts else emptyList())
                m.place = if (anchors.isNotEmpty()) nearAnchor(m, preferred) else gaz.choose(m.cands, preferred)
            }
            val ok = ms.filter { it.type != 'P' || it.place != null }
            val marker = ok.any { it.role == 'D' && !it.bare }
            if (marker && ok.none { it.role == 'L' && it.type == 'P' }) {
                // «на Погребище …, курс на захід», «КАБи на Донеччину, курс на …»: the first «на X» is where it is now.
                val first = ok.firstOrNull { it.role == 'D' && it.bare && it.type == 'P' }
                    ?: ok.firstOrNull { it.role == 'D' && it.bare && it.type == 'R' }?.takeIf { ok.none { o -> o.role == 'L' && o.type == 'R' } }
                first?.role = 'L'
            }
            val destList = ok.filter { it.role == 'D' && it.type != 'S' }
            val destSide = ok.firstOrNull { it.role == 'D' && it.type == 'S' && (it.side ?: -1) >= 0 }?.side
            val locP = ok.filter { it.role == 'L' && it.type == 'P' }
            var dest = (destList.filter { !it.bare } + destList.filter { it.bare }).firstOrNull()
            if (dest != null && dest.type == 'R') {
                // «у напрямку Хмельниччини (Шепетівка)», «на Вінниччину (на Оратів)»: aim at the town, not the oblast.
                destList.firstOrNull { it.type == 'P' && Gazetteer.sameOblast(dest!!.oblast!!, it.place!!.oblast) }?.let { dest = it }
            }
            val destPt = dest?.let { point(it) }
            val originSide = ok.firstOrNull { it.role == 'O' && it.type == 'S' }?.side?.takeIf { it >= 0 }

            fun heading(lat: Double, lon: Double, dp: Pair<Double, Double>?, destIsPlace: Boolean): Int? {
                if (dp != null && destIsPlace && distKm(lat, lon, dp.first, dp.second) > 2) return bearing(lat, lon, dp.first, dp.second)
                if (destSide != null) return destSide
                if (dp != null && distKm(lat, lon, dp.first, dp.second) > 15) return bearing(lat, lon, dp.first, dp.second)
                if (originSide != null) return (originSide + 180) % 360
                return null
            }
            fun fix(lat: Double, lon: Double, unc: Double, label: String, fallback: Int? = null): Fix {
                val keep = destPt != null && distKm(lat, lon, destPt.first, destPt.second) > 2
                return Fix(lat, lon, unc, heading(lat, lon, destPt, dest?.type == 'P') ?: fallback, if (keep) destPt else null, label, false)
            }
            /** «на Київ з півночі»: out on that side of the town, flying into it. */
            fun fromSide(p: Place, side: Int, unc: Double, fromDest: Boolean): Fix {
                val (la, lo) = shift(p.lat, p.lon, side, FROM_SIDE_KM)
                return Fix(la, lo, unc, bearing(la, lo, p.lat, p.lon), p.lat to p.lon, p.name, fromDest)
            }

            // 1. Named settlement where it is.
            if (locP.isNotEmpty()) {
                val a = locP[0]
                if (a.via == "між" && locP.size >= 2) {
                    val b = locP[1].place!!; val p = a.place!!
                    val la = (p.lat + b.lat) / 2; val lo = (p.lon + b.lon) / 2
                    return listOf(fix(la, lo, max(10.0, distKm(p.lat, p.lon, b.lat, b.lon) / 2), "${p.name}–${b.name}"))
                }
                // «повз Немирів, Гнівань, Козятин»: it is at the last one, coming from the one before.
                var run = 1
                var comma = false
                while (run < locP.size && ms.indexOf(locP[run]) == ms.indexOf(locP[run - 1]) + 1 &&
                    (locP[run].sep == "," || (locP[run].sep == "та" && comma))) { if (locP[run].sep == ",") comma = true; run++ }
                if (!comma) run = 1
                var last = locP[run - 1]
                // «⚠ Київ / над містом (Оболонь)»: the district says where in the city.
                if (run == 1 && locP.size >= 2 && headerCity != null && a.place == headerCity) {
                    val d = locP[1].place!!
                    if (d != headerCity && distKm(d.lat, d.lon, headerCity.lat, headerCity.lon) < 25) last = locP[1]
                }
                val p = last.place!!
                val from = if (run >= 2) locP[run - 2].place!! else null
                val moved = from?.let { bearing(it.lat, it.lon, p.lat, p.lon) }
                val s = last.side
                return if (s != null && s >= 0) {
                    val (la, lo) = shift(p.lat, p.lon, s, 12.0); listOf(fix(la, lo, 12.0, p.name, moved))
                } else listOf(fix(p.lat, p.lon, if (last.via == "районі") 5.0 else 7.0, p.name, moved))
            }
            // 2. Oblast (and its side) where it is.
            val locR = ok.filter { it.role == 'L' && it.type == 'R' }
            if (locR.isNotEmpty()) {
                // «на Миколаївщині у напрямку Баштанки зі сходу»: the town and the side say more than the oblast.
                val town = dest?.takeIf { it.type == 'P' }?.place
                if (town != null && originSide != null && locR.any { Gazetteer.sameOblast(it.oblast!!, town.oblast) }) {
                    return listOf(fromSide(town, originSide, 20.0, false))
                }
                val pts = locR.mapNotNull { m -> point(m)?.let { m to it } }
                if (pts.isNotEmpty()) {
                    val first = pts[0].first
                    if ((first.via == "межі" || first.via == "через" || first.via == "між") && pts.size >= 2) {
                        val la = pts.map { it.second.first }.average(); val lo = pts.map { it.second.second }.average()
                        val spread = pts.maxOf { distKm(la, lo, it.second.first, it.second.second) }
                        val unc = if (first.via == "через") max(60.0, spread) else 40.0
                        return listOf(fix(la, lo, unc, pts.joinToString("/") { it.first.oblast!! }))
                    }
                    return pts.distinctBy { it.first.oblast to it.first.side }.map { (m, p) ->
                        fix(p.first, p.second, if ((m.side ?: -1) >= 0) 50.0 else 60.0, m.oblast!!)
                    }
                }
            }
            // 3. Only a side: of the city in the post header, or of the oblast the line or header names.
            val locS = ok.firstOrNull { it.role == 'L' && it.type == 'S' }
            if (locS != null) {
                val s = locS.side ?: -1
                if (headerCity != null) {
                    val (la, lo) = if (s >= 0) shift(headerCity.lat, headerCity.lon, s, 12.0) else headerCity.lat to headerCity.lon
                    return listOf(fix(la, lo, 12.0, headerCity.name))
                }
                val ob = hereOblasts.firstOrNull() ?: headerOblast
                gaz.regions[ob]?.point(s)?.let { return listOf(fix(it.first, it.second, if (s >= 0) 50.0 else 60.0, ob!!)) }
            }
            // 4–5. Where it came from.
            ok.firstOrNull { it.role == 'O' && it.type == 'P' }?.let { m ->
                val p = m.place!!; return listOf(fix(p.lat, p.lon, 10.0, p.name))
            }
            ok.firstOrNull { it.role == 'O' && it.type == 'R' }?.let { m ->
                val r = gaz.regions[m.oblast] ?: return@let
                if ((m.side ?: -1) >= 0) { val p = r.point(m.side); return listOf(fix(p.first, p.second, 50.0, m.oblast!!)) }
                return if (destPt != null) {
                    listOf(fix((r.lat + destPt.first) / 2, (r.lon + destPt.second) / 2, 60.0, m.oblast!!))
                } else listOf(fix(r.lat, r.lon, 60.0, m.oblast!!))
            }
            if (sea) return emptyList() // «з Чорного моря» without a point on land
            // 6. Only where it goes. With a side it comes from, it is out on that side; otherwise only the target is known.
            val d0 = dest
            if (d0 != null && destPt != null) {
                val firstDests = listOf(d0) + destList.filter {
                    it !== d0 && it.type == 'P' && (it.sep == "та" || it.sep == "/") && it.role == d0.role
                }
                return firstDests.mapNotNull { d ->
                    val dp = point(d) ?: return@mapNotNull null
                    if (d.type == 'P') {
                        val p = d.place!!
                        if (originSide != null) fromSide(p, originSide, 15.0, true)
                        else Fix(dp.first, dp.second, 5.0, null, dp, p.name, true, targetOnly = true)
                    } else {
                        val unc = if ((d.side ?: -1) >= 0) 50.0 else 60.0
                        if (originSide != null) {
                            // «на Київщину з півночі», «на північ Черкащини з півдня»: in that side of the oblast.
                            val (la, lo) = gaz.regions[d.oblast]!!.point(originSide)
                            val h = if ((d.side ?: -1) >= 0 && distKm(la, lo, dp.first, dp.second) > 15) bearing(la, lo, dp.first, dp.second)
                            else (originSide + 180) % 360
                            Fix(la, lo, 50.0, h, null, d.oblast!!, false)
                        } else Fix(dp.first, dp.second, unc, null, dp, d.oblast!!, false, targetOnly = true)
                    }
                }
            }
            // 7. Only the oblast named before the threat («Київщина: БпЛА курсом на захід»).
            val ctx = hereOblasts.firstOrNull() ?: headerOblast
            if (ctx != null && (destSide != null || ms.isEmpty())) {
                gaz.regions[ctx]?.let { r -> return listOf(Fix(r.lat, r.lon, 60.0, destSide, null, ctx, false, ctx = true)) }
            }
            return emptyList()
        }
    }

    // ---- geometry ----

    internal fun distKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    internal fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).roundToInt() % 360
    }

    internal fun shift(lat: Double, lon: Double, deg: Int, km: Double): Pair<Double, Double> {
        val r = Math.toRadians(deg.toDouble())
        val dLat = km * cos(r) / 111.2
        val dLon = km * sin(r) / (111.2 * max(0.1, abs(cos(Math.toRadians(lat)))))
        return (lat + dLat) to (lon + dLon)
    }
}
