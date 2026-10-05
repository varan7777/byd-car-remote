package ua.zaika.carremote.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import ua.zaika.carremote.air.AirThreat
import ua.zaika.carremote.air.Geo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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

    private var anchors: Map<String, Anchor> = emptyMap()
    private val history = HashMap<String, ArrayDeque<Pt>>()

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val carFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39D98A.toInt(); style = Paint.Style.FILL }
    private val carRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39D98A.toInt(); style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = mapView.resources.displayMetrics.density
        arrow.strokeWidth = 2f * d
        trail.strokeWidth = 2f * d
        carRing.strokeWidth = 2f * d
        label.textSize = 12f * d
        val proj = mapView.projection
        val head = Point()
        val seg = Point()
        val now = System.currentTimeMillis()

        for (t in threats) {
            val color = colorFor(t.type)
            val (hlat, hlon) = predict(t, now)
            proj.toPixels(GeoPoint(hlat, hlon), head)
            val hx = head.x.toFloat(); val hy = head.y.toFloat()

            // Trail: fade from oldest (faint) to the current head (bright).
            val hist = history[t.id]
            if (hist != null && hist.isNotEmpty()) {
                trail.color = color
                var px = Float.NaN; var py = Float.NaN
                val n = hist.size
                hist.forEachIndexed { i, pt ->
                    proj.toPixels(GeoPoint(pt.lat, pt.lon), seg)
                    val cx = seg.x.toFloat(); val cy = seg.y.toFloat()
                    if (!px.isNaN()) {
                        trail.alpha = (40 + 150 * i / n).coerceAtMost(190)
                        canvas.drawLine(px, py, cx, cy, trail)
                    }
                    px = cx; py = cy
                }
                if (!px.isNaN()) { trail.alpha = 210; canvas.drawLine(px, py, hx, hy, trail) }
            }

            dot.color = color
            canvas.drawCircle(hx, hy, 5f * d, halo)
            canvas.drawCircle(hx, hy, 4f * d, dot)
            t.heading?.let { hdg ->
                val r = Math.toRadians(hdg.toDouble())
                val len = 13f * d
                arrow.color = color
                canvas.drawLine(hx, hy, hx + (sin(r) * len).toFloat(), hy - (cos(r) * len).toFloat(), arrow)
            }
        }

        car?.let { (lat, lon) ->
            proj.toPixels(GeoPoint(lat, lon), head)
            val x = head.x.toFloat(); val y = head.y.toFloat()
            canvas.drawCircle(x, y, 10f * d, carRing)
            canvas.drawCircle(x, y, 4f * d, carFill)
            canvas.drawText("ви", x + 13f * d, y + 4f * d, label)
        }
    }

    /** Dead-reckoned head position: advance the anchor along the heading, capped so it can't run away. */
    private fun predict(t: AirThreat, now: Long): Pair<Double, Double> {
        val a = anchors[t.id] ?: return t.lat to t.lon
        val h = t.heading ?: return a.lat to a.lon
        val speed = speedKmh(t.type)
        if (speed <= 0) return a.lat to a.lon
        val elapsedH = min(now - a.sinceMs, MAX_DRIFT_MS).coerceAtLeast(0) / 3_600_000.0
        return Geo.dest(a.lat, a.lon, h.toDouble(), speed * elapsedH)
    }

    private fun speedKmh(type: String): Double = when (type) {
        "uav" -> 160.0        // Shahed-type cruise
        "recon" -> 120.0
        "missile" -> 700.0    // subsonic cruise missile
        "ballistic", "kab", "mig31k" -> 0.0 // too fast / near target — show where NEPTUN places it
        else -> 0.0
    }

    private fun colorFor(type: String): Int = when (type) {
        "missile", "ballistic", "mig31k" -> 0xFFFF4D4D.toInt()
        "kab" -> 0xFFFFC14D.toInt()
        "uav", "recon" -> 0xFFFF8A3D.toInt()
        else -> 0xFFD0D7E2.toInt()
    }

    private companion object {
        const val MAX_DRIFT_MS = 180_000L   // cap head extrapolation at 3 min between updates
        const val MAX_TRAIL = 40            // trail points kept per track
        const val TRAIL_AGE_MS = 900_000L   // drop trail points older than 15 min
    }
}
