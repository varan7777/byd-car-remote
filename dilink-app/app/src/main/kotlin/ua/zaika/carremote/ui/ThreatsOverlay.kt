package ua.zaika.carremote.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import ua.zaika.carremote.air.AirThreat
import kotlin.math.cos
import kotlin.math.sin

/** Draws NEPTUN threat markers (coloured dot + heading arrow) and the car onto an osmdroid map. */
class ThreatsOverlay : Overlay() {

    var threats: List<AirThreat> = emptyList()
    var car: Pair<Double, Double>? = null

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val carFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF39D98A.toInt(); style = Paint.Style.FILL }
    private val carRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF39D98A.toInt(); style = Paint.Style.STROKE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val d = mapView.resources.displayMetrics.density
        arrow.strokeWidth = 2f * d
        carRing.strokeWidth = 2f * d
        label.textSize = 12f * d
        val proj = mapView.projection
        val p = Point()

        for (t in threats) {
            proj.toPixels(GeoPoint(t.lat, t.lon), p)
            val x = p.x.toFloat(); val y = p.y.toFloat()
            dot.color = colorFor(t.type)
            canvas.drawCircle(x, y, 5f * d, halo)
            canvas.drawCircle(x, y, 4f * d, dot)
            t.heading?.let { hdg ->
                val r = Math.toRadians(hdg.toDouble())
                val len = 13f * d
                arrow.color = dot.color
                canvas.drawLine(x, y, x + (sin(r) * len).toFloat(), y - (cos(r) * len).toFloat(), arrow)
            }
        }

        car?.let { (lat, lon) ->
            proj.toPixels(GeoPoint(lat, lon), p)
            val x = p.x.toFloat(); val y = p.y.toFloat()
            canvas.drawCircle(x, y, 10f * d, carRing)
            canvas.drawCircle(x, y, 4f * d, carFill)
            canvas.drawText("ви", x + 13f * d, y + 4f * d, label)
        }
    }

    private fun colorFor(type: String): Int = when (type) {
        "missile", "ballistic", "mig31k" -> 0xFFFF4D4D.toInt()
        "kab" -> 0xFFFFC14D.toInt()
        "uav", "recon" -> 0xFFFF8A3D.toInt()
        else -> 0xFFD0D7E2.toInt()
    }
}
