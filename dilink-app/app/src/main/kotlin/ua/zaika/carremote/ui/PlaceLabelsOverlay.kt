package ua.zaika.carremote.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Settlement names drawn above the alert shading and borders, so they stay readable however the
 * map is tinted (the OSM tiles' own names sit underneath those layers). Names come from the
 * bundled gazetteer (assets/geo/places.tsv): cities first, then towns, then villages as you zoom
 * in; a name that would overlap one already drawn is skipped.
 */
class PlaceLabelsOverlay : Overlay() {

    private class Place(val name: String, val lat: Double, val lon: Double, val rank: Int)

    @Volatile private var places: List<Place>? = null
    @Volatile private var loading = false

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sfSemibold }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sfSemibold; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val pt = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val all = places ?: return load(mapView)
        val d = mapView.resources.displayMetrics.density
        val light = Ui.isLight(mapView.context)
        val zoom = mapView.zoomLevelDouble
        // Which kinds show at this zoom: 0 city, 1 town, 2 village.
        val maxRank = when { zoom < 8 -> 0; zoom < 11.5 -> 1; else -> 2 }
        if (zoom < 6) return
        val box = mapView.boundingBox
        val proj = mapView.projection
        val taken = ArrayList<RectF>()
        var drawn = 0
        for (p in all) {
            if (p.rank > maxRank) break // sorted by rank
            if (p.lat < box.latSouth || p.lat > box.latNorth || p.lon < box.lonWest || p.lon > box.lonEast) continue
            text.textSize = (if (p.rank == 0) 15f else if (p.rank == 1) 13f else 12f) * d
            text.color = if (light) 0xFF1C2433.toInt() else 0xFFF2F5FA.toInt()
            halo.textSize = text.textSize
            halo.strokeWidth = 3.5f * d
            halo.color = if (light) 0xE6FFFFFF.toInt() else 0xE60B1020.toInt()
            proj.toPixels(GeoPoint(p.lat, p.lon), pt)
            val w = text.measureText(p.name)
            val r = RectF(pt.x - w / 2 - 4 * d, pt.y - text.textSize, pt.x + w / 2 + 4 * d, pt.y + 4 * d)
            if (taken.any { RectF.intersects(it, r) }) continue
            taken += r
            canvas.drawText(p.name, pt.x - w / 2, pt.y.toFloat(), halo)
            canvas.drawText(p.name, pt.x - w / 2, pt.y.toFloat(), text)
            if (++drawn >= MAX_LABELS) break
        }
    }

    private fun load(mapView: MapView) {
        if (loading) return
        loading = true
        val assets = mapView.context.applicationContext.assets
        Thread {
            places = runCatching {
                assets.open("geo/places.tsv").bufferedReader().useLines { lines ->
                    lines.filter { !it.startsWith("#") }.mapNotNull { l ->
                        val c = l.split('\t')
                        if (c.size < 4) return@mapNotNull null
                        val rank = when (c[3]) { "c" -> 0; "t" -> 1; "v" -> 2; else -> return@mapNotNull null }
                        Place(c[0], c[1].toDoubleOrNull() ?: return@mapNotNull null, c[2].toDoubleOrNull() ?: return@mapNotNull null, rank)
                    }.sortedBy { it.rank }.toList()
                }
            }.getOrDefault(emptyList())
            mapView.postInvalidate()
        }.apply { isDaemon = true }.start()
    }

    private companion object { const val MAX_LABELS = 90 }
}
