package ua.zaika.carremote.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import ua.zaika.carremote.air.AirThreat
import ua.zaika.carremote.air.Geo
import kotlin.math.min

/**
 * Draws NEPTUN threat markers (coloured dot + heading arrow), a motion trail, and the car.
 *
 * NEPTUN's REST gives only a position + heading, so two things make movement visible:
 *  - a trail of each track's real reported positions over the last few minutes, which grows as
 *    you watch and shows where the threat is going;
 *  - dead-reckoning of the marker head between updates (advance along the heading at a nominal
 *    per-type speed), re-synced whenever NEPTUN reports a new position, so it glides smoothly.
 * At country zoom a 160 km/h drone covers under a pixel per 10 s, so the trail is the clearest
 * cue. Positions are approximate to begin with (NEPTUN flags them "approx") — this is a display
 * estimate, not a precise track.
 */
class ThreatsOverlay : Overlay() {

    private class Anchor(val lat: Double, val lon: Double, val sinceMs: Long)
    private class Pt(val lat: Double, val lon: Double, val tMs: Long)

    var threats: List<AirThreat> = emptyList()
        set(value) {
            val now = System.currentTimeMillis()
            val nextAnchors = HashMap<String, Anchor>(value.size)
            for (t in value) {
                val prev = anchors[t.id]
                nextAnchors[t.id] = if (prev != null && prev.lat == t.lat && prev.lon == t.lon) prev
                else Anchor(t.lat, t.lon, now)
                // Append to the trail only when NEPTUN actually moved the track.
                val h = history.getOrPut(t.id) { ArrayDeque() }
                val last = h.lastOrNull()
                if (last == null || last.lat != t.lat || last.lon != t.lon) h.addLast(Pt(t.lat, t.lon, now))
                while (h.size > MAX_TRAIL) h.removeFirst()
                while (h.isNotEmpty() && now - h.first().tMs > TRAIL_AGE_MS) h.removeFirst()
            }
            anchors = nextAnchors
            val live = value.mapTo(HashSet()) { it.id }
            history.keys.retainAll(live)
            field = value
        }
    var car: Pair<Double, Double>? = null
    /** Air Force «курс на X» with no position yet: drawn as a dashed target ring at X. */
    var targets: List<ua.zaika.carremote.air.Sighting> = emptyList()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Called with the threat whose marker was tapped. */
    var onThreatTap: ((AirThreat) -> Unit)? = null
    /** Called when the map is tapped away from any marker. */
    var onEmptyTap: (() -> Unit)? = null
    /** Called after every redraw, so things pinned to a marker can follow it. */
    var onDrawn: (() -> Unit)? = null
    private val drawn = HashMap<String, Pair<Float, Float>>() // last drawn marker position per id

    private var anchors: Map<String, Anchor> = emptyMap()
    private val history = HashMap<String, ArrayDeque<Pt>>()

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    // Dark outline so the drone glyph reads over any map colour.
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0B1020.toInt(); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // Bright red so the car stands out on the green/grey OSM map (green blended in).
    private val carFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt(); style = Paint.Style.FILL }
    private val carRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1744.toInt(); style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dronePath = Path()
    private val trailPath = Path()
    private val backdrop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD90B1020.toInt(); style = Paint.Style.FILL }

    /** The colour halfway to grey, fully opaque: how a marker with old data looks. */
    private fun greyed(c: Int): Int {
        fun mix(v: Int) = (v + 0x8A) / 2
        return (0xFF shl 24) or (mix((c shr 16) and 0xFF) shl 16) or (mix((c shr 8) and 0xFF) shl 8) or mix(c and 0xFF)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = mapView.resources.displayMetrics.density
        outline.strokeWidth = 1.5f * d
        trail.strokeWidth = 2f * d
        carRing.strokeWidth = 2f * d
        label.textSize = 12f * d
        label.setShadowLayer(2.5f * d, 0f, 0f, 0xE6000000.toInt()) // readable on the light day map too
        val proj = mapView.projection
        val head = Point()
        val seg = Point()
        val now = System.currentTimeMillis()

        for (t in threats) {
            val kind = kindOf(t)
            val color = colorFor(kind)
            val (hlat, hlon) = predict(t, now)
            proj.toPixels(GeoPoint(hlat, hlon), head)
            val hx = head.x.toFloat(); val hy = head.y.toFloat()

            // Trail through every position reported in the last 30 min (the service keeps them even
            // while this screen is closed), smoothed into a curve through the midpoints, ending at the head.
            val pts = t.path.takeIf { it.size >= 2 } ?: history[t.id]?.map { it.lat to it.lon } ?: emptyList()
            if (pts.isNotEmpty()) {
                val xy = pts.map { (la, lo) -> proj.toPixels(GeoPoint(la, lo), seg); seg.x.toFloat() to seg.y.toFloat() } + (hx to hy)
                // Catmull-Rom spline through every fix: a smooth curve that passes through each
                // reported position, so a turning drone leaves a curved trail.
                trailPath.reset()
                trailPath.moveTo(xy[0].first, xy[0].second)
                for (i in 0 until xy.size - 1) {
                    val p0 = xy[maxOf(i - 1, 0)]; val p1 = xy[i]; val p2 = xy[i + 1]; val p3 = xy[minOf(i + 2, xy.size - 1)]
                    trailPath.cubicTo(
                        p1.first + (p2.first - p0.first) / 6, p1.second + (p2.second - p0.second) / 6,
                        p2.first - (p3.first - p1.first) / 6, p2.second - (p3.second - p1.second) / 6,
                        p2.first, p2.second,
                    )
                }
                trail.color = color
                trail.alpha = 170
                canvas.drawPath(trailPath, trail)
            }

            // NEPTUN positions arrive late; show how old each one is and fade the stale ones.
            val age = t.ageMin(now)
            val fast = t.jet || t.type in setOf("missile", "ballistic", "mig31k")
            val stale = age != null && age >= (if (fast) 5 else STALE_MIN)
            drawn[t.id] = hx to hy
            // An opaque backdrop so borders and shading never show through the glyph; old data is
            // drawn greyer, not see-through.
            canvas.drawCircle(hx, hy, 15f * d, backdrop)
            dot.color = if (stale) greyed(color) else color
            drawThreat(canvas, kind, hx, hy, t.heading, 14f * d)
            // A group of drones carries its size in the title, e.g. «Група БпЛА (3+)».
            val group = Regex("\\((\\d+\\+?)\\)").find(t.title)?.groupValues?.get(1)
                ?: if (t.title.contains("Група")) "група" else null
            group?.let { canvas.drawText("×$it", hx + 17f * d, hy - 6f * d, label) }
            val tag = if (t.source == "airforce") "ПС · " else ""
            if (age != null) canvas.drawText(tag + if (age < 1) "щойно" else "$age хв", hx + 17f * d, hy + 18f * d, label)
        }

        for (tg in targets) {
            proj.toPixels(GeoPoint(tg.lat, tg.lon), head)
            val x = head.x.toFloat(); val y = head.y.toFloat()
            ring.color = colorFor(if (tg.jet && tg.kind == "uav") "jet_uav" else tg.kind)
            ring.strokeWidth = 2.5f * d
            ring.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
            canvas.drawCircle(x, y, 14f * d, ring)
            canvas.drawText("курс сюди", x + 17f * d, y + 4f * d, label)
        }

        car?.let { (lat, lon) ->
            proj.toPixels(GeoPoint(lat, lon), head)
            val x = head.x.toFloat(); val y = head.y.toFloat()
            canvas.drawCircle(x, y, 10f * d, carRing)
            canvas.drawCircle(x, y, 4f * d, carFill)
            canvas.drawText("ви", x + 13f * d, y + 4f * d, label)
        }
        onDrawn?.invoke()
    }

    /** A small delta-wing drone silhouette (Shahed-like): nose points along the heading. With no
     *  heading the nose points north. [dot] already holds the per-type colour. */
    /**
     * A silhouette per threat type, nose along the heading (north when unknown): Shahed delta
     * wing, recon plane, FPV quadcopter, cruise missile, ballistic rocket, guided bomb (KAB),
     * MiG-31K jet, or a question-mark diamond. [dot] already holds the per-type colour.
     */
    private fun drawThreat(canvas: Canvas, type: String, cx: Float, cy: Float, heading: Int?, s: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate((heading ?: 0).toFloat()) // 0° = north = nose up
        val p = dronePath
        p.reset()
        when (type) {
            "uav" -> { // Shahed: delta wing
                p.moveTo(0f, -s); p.lineTo(s * 0.9f, s * 0.75f); p.lineTo(0f, s * 0.3f); p.lineTo(-s * 0.9f, s * 0.75f); p.close()
            }
            "jet_uav" -> { // jet Shahed (Geran-3): slim swept arrow with a jet nozzle
                p.moveTo(0f, -s * 1.25f); p.lineTo(s * 0.22f, -s * 0.3f); p.lineTo(s * 0.95f, s * 0.7f)
                p.lineTo(s * 0.22f, s * 0.55f); p.lineTo(s * 0.3f, s * 1.1f); p.lineTo(-s * 0.3f, s * 1.1f)
                p.lineTo(-s * 0.22f, s * 0.55f); p.lineTo(-s * 0.95f, s * 0.7f); p.lineTo(-s * 0.22f, -s * 0.3f); p.close()
            }
            "recon" -> { // straight-wing recon plane
                p.addRect(-s * 0.12f, -s, s * 0.12f, s, Path.Direction.CW)
                p.addRect(-s, -s * 0.35f, s, -s * 0.05f, Path.Direction.CW)
                p.addRect(-s * 0.45f, s * 0.7f, s * 0.45f, s * 0.9f, Path.Direction.CW)
            }
            "fpv" -> { // quadcopter: X frame + four rotors
                p.addRect(-s * 0.25f, -s * 0.25f, s * 0.25f, s * 0.25f, Path.Direction.CW)
                for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) p.addCircle(x * s * 0.65f, y * s * 0.65f, s * 0.33f, Path.Direction.CW)
            }
            "missile" -> { // cruise missile: long body, small wings, tail fins
                p.addRoundRect(-s * 0.16f, -s * 1.2f, s * 0.16f, s * 1.1f, s * 0.16f, s * 0.16f, Path.Direction.CW)
                p.addRect(-s * 0.7f, -s * 0.05f, s * 0.7f, s * 0.12f, Path.Direction.CW)
                p.moveTo(0f, s * 0.75f); p.lineTo(s * 0.45f, s * 1.1f); p.lineTo(-s * 0.45f, s * 1.1f); p.close()
            }
            "ballistic" -> { // rocket: pointed nose, thick body, big fins
                p.moveTo(0f, -s * 1.3f); p.lineTo(s * 0.3f, -s * 0.7f); p.lineTo(s * 0.3f, s * 0.7f)
                p.lineTo(s * 0.7f, s * 1.15f); p.lineTo(-s * 0.7f, s * 1.15f); p.lineTo(-s * 0.3f, s * 0.7f)
                p.lineTo(-s * 0.3f, -s * 0.7f); p.close()
            }
            "kab" -> { // guided bomb: teardrop with tail fins
                p.addOval(-s * 0.35f, -s, s * 0.35f, s * 0.6f, Path.Direction.CW)
                p.moveTo(0f, s * 0.4f); p.lineTo(s * 0.55f, s * 1.05f); p.lineTo(-s * 0.55f, s * 1.05f); p.close()
            }
            "mig31k" -> { // jet: swept wings + tailplane
                p.moveTo(0f, -s * 1.2f); p.lineTo(s * 0.15f, -s * 0.4f); p.lineTo(s * 1.0f, s * 0.25f)
                p.lineTo(s * 1.0f, s * 0.45f); p.lineTo(s * 0.15f, s * 0.2f); p.lineTo(s * 0.15f, s * 0.75f)
                p.lineTo(s * 0.5f, s * 1.1f); p.lineTo(-s * 0.5f, s * 1.1f); p.lineTo(-s * 0.15f, s * 0.75f)
                p.lineTo(-s * 0.15f, s * 0.2f); p.lineTo(-s * 1.0f, s * 0.45f); p.lineTo(-s * 1.0f, s * 0.25f)
                p.lineTo(-s * 0.15f, -s * 0.4f); p.close()
            }
            else -> { // unknown: diamond
                p.moveTo(0f, -s); p.lineTo(s * 0.8f, 0f); p.lineTo(0f, s); p.lineTo(-s * 0.8f, 0f); p.close()
            }
        }
        canvas.drawPath(p, dot)
        canvas.drawPath(p, outline)
        canvas.restore()
        if (type !in KNOWN) canvas.drawText("?", cx - s * 0.25f, cy + s * 0.35f, label)
    }

    /** Dead-reckoned head position: advance the anchor along the heading, capped so it can't run away. */
    private fun predict(t: AirThreat, now: Long): Pair<Double, Double> {
        val a = anchors[t.id] ?: return t.lat to t.lon
        val h = t.heading ?: return a.lat to a.lon
        val speed = speedKmh(kindOf(t))
        if (speed <= 0) return a.lat to a.lon
        val elapsedH = min(now - a.sinceMs, MAX_DRIFT_MS).coerceAtLeast(0) / 3_600_000.0
        return Geo.dest(a.lat, a.lon, h.toDouble(), speed * elapsedH)
    }

    private fun speedKmh(type: String): Double = when (type) {
        "uav" -> 160.0        // Shahed-type cruise
        "jet_uav" -> 500.0    // jet Shahed (Geran-3)
        "recon" -> 120.0
        "missile" -> 700.0    // subsonic cruise missile
        "ballistic", "kab", "mig31k" -> 0.0 // too fast / near target — show where NEPTUN places it
        else -> 0.0
    }

    private fun colorFor(type: String): Int = when (type) {
        "missile", "ballistic", "mig31k", "jet_uav" -> 0xFFFF4D4D.toInt()
        "kab" -> 0xFFFFC14D.toInt()
        "uav", "recon" -> 0xFFFF8A3D.toInt()
        "fpv" -> 0xFFE879F9.toInt()
        else -> 0xFFD0D7E2.toInt()
    }

    /** One marker glyph as on the map, nose up, for the legend. */
    fun drawGlyph(canvas: Canvas, kind: String, cx: Float, cy: Float, size: Float, density: Float) {
        outline.strokeWidth = 1.5f * density
        label.textSize = 12f * density
        dot.color = colorFor(kind)
        drawThreat(canvas, kind, cx, cy, null, size)
    }

    /** NEPTUN types a jet Shahed as a plain "uav"; its title or speed tells it apart ([AirThreat.jet]). */
    private fun kindOf(t: AirThreat): String = if (t.jet) "jet_uav" else t.type

    /** Where the marker of [id] was last drawn, in MapView pixels. */
    fun positionOf(id: String): Pair<Float, Float>? = drawn[id]

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val d = mapView.resources.displayMetrics.density
        val hit = threats.mapNotNull { t -> drawn[t.id]?.let { (x, y) -> t to Math.hypot((x - e.x).toDouble(), (y - e.y).toDouble()) } }
            .filter { it.second <= 32 * d }.minByOrNull { it.second }?.first
        if (hit == null) { onEmptyTap?.invoke(); return false }
        onThreatTap?.invoke(hit)
        return true
    }

    private companion object {
        const val STALE_MIN = 15
        val KNOWN = setOf("uav", "jet_uav", "recon", "fpv", "missile", "ballistic", "kab", "mig31k")
        const val MAX_DRIFT_MS = 180_000L   // cap head extrapolation at 3 min between updates
        const val MAX_TRAIL = 40            // trail points kept per track
        const val TRAIL_AGE_MS = 900_000L   // drop trail points older than 15 min
    }

}
