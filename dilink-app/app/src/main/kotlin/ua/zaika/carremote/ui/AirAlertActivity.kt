package ua.zaika.carremote.ui

import android.app.Activity
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polygon
import ua.zaika.carremote.air.AirAlertMonitor
import ua.zaika.carremote.air.AirState
import ua.zaika.carremote.air.Geo
import ua.zaika.carremote.air.OblastShape
import ua.zaika.carremote.air.RegionLocator
import ua.zaika.carremote.air.Speaker
import java.io.File

/**
 * Air-raid / UAV screen for the head unit.
 *
 * Default view is a raster OpenStreetMap (osmdroid) that renders on plain Canvas — no WebGL —
 * so it works on cheap DiLink units, and it zooms to street level. Threats and the car are
 * drawn as an overlay; oblasts under alert are shaded. A toggle opens NEPTUN's richer online
 * (WebGL) map in a WebView for units that can show it. The proximity alarm (banner + beep) is
 * computed in Kotlin over plain HTTP and works regardless of which map is up.
 */
class AirAlertActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    private lateinit var map: MapView
    private val threatsOverlay = ThreatsOverlay()
    private lateinit var banner: TextView
    private lateinit var toggle: Button
    private var web: WebView? = null
    private var showWeb = false

    private val regions by lazy { RegionLocator(applicationContext) }
    private val monitor by lazy { AirAlertMonitor(applicationContext, regions = regions) }
    private var tone: ToneGenerator? = null
    private var speaker: Speaker? = null
    private var alarmActive = false

    private var allShapes: List<OblastShape> = emptyList()
    private var shadedKeys: Set<String> = emptySet()
    private var centered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid needs a UA (OSM tiles reject blank ones) and a cache dir; use app storage
        // so no runtime permission is required.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            val base = File(cacheDir, "osm")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)      // pinch zoom
            setUseDataConnection(true)
            controller.setZoom(11.0)         // ~city/district level
            controller.setCenter(GeoPoint(49.0, 31.5)) // Ukraine, until the first GPS fix
            overlays.add(CopyrightOverlay(this@AirAlertActivity)) // "© OpenStreetMap contributors"
            overlays.add(threatsOverlay)
        }

        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC0E1420.toInt())
            textSize = 17f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = "Завантаження повітряної обстановки…"
        }

        toggle = Button(this).apply {
            text = "🌐 Онлайн-карта"
            setOnClickListener { setShowWeb(!showWeb) }
        }
        val recenter = Button(this).apply {
            text = "📍 Моє місце"
            setOnClickListener { recenter() }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(toggle)
            addView(recenter)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(banner, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(buttons, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            })
        })

        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
        speaker = Speaker(this)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        job = ui.launch { monitor.run(::render) }
        // Oblast outlines for alert shading (loads/caches GeoJSON off the main thread).
        ui.launch {
            allShapes = withContext(Dispatchers.IO) { regions.shapes() }
            applyShading() // shade now if an alert was already seen before shapes finished loading
        }
    }

    override fun onPause() {
        job?.cancel()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.cancel()
        tone?.release(); tone = null
        speaker?.shutdown(); speaker = null
        web?.destroy()
        map.onDetach()
        super.onDestroy()
    }

    private fun render(s: AirState) {
        banner.text = s.bannerText(monitor.radiusKm)
        banner.setBackgroundColor(
            when {
                s.isAlarm -> 0xE6B3261E.toInt()
                s.error != null -> 0xE6704000.toInt()
                else -> 0xCC12351F.toInt()
            }
        )
        threatsOverlay.threats = s.all
        threatsOverlay.car = s.fix
        if (!centered && s.fix != null) {
            map.controller.setCenter(GeoPoint(s.fix.first, s.fix.second))
            centered = true
        }
        if (s.alertKeys != shadedKeys) {
            shadedKeys = s.alertKeys
            applyShading()
        }
        map.invalidate()
        if (s.isAlarm && !alarmActive) { beep(); speaker?.speak(s.voiceAlert()) }
        alarmActive = s.isAlarm
    }

    /** Rebuild the red polygons for the oblasts in [shadedKeys] (cheap: a handful at most). */
    private fun applyShading() {
        if (allShapes.isEmpty()) return
        map.overlays.removeAll { it is Polygon }
        for (sh in allShapes) if (sh.key in shadedKeys) for (ring in sh.rings) {
            val pts = ArrayList<GeoPoint>(ring.size / 2)
            var i = 0
            while (i < ring.size) { pts.add(GeoPoint(ring[i + 1], ring[i])); i += 2 }
            map.overlays.add(0, Polygon().apply {
                points = pts
                fillPaint.color = 0x55E24A3B
                outlinePaint.color = 0x88FF4D4D.toInt()
                outlinePaint.strokeWidth = 2f
            })
        }
        map.invalidate()
    }

    private fun recenter() {
        val fix = Geo.lastKnown(applicationContext) ?: return
        if (showWeb) centerWebOnCar(fix) else {
            map.controller.animateTo(GeoPoint(fix.first, fix.second))
            map.controller.setZoom(12.0)
        }
    }

    private fun setShowWeb(on: Boolean) {
        showWeb = on
        toggle.text = if (on) "🗺 Проста карта" else "🌐 Онлайн-карта"
        map.visibility = if (on) View.GONE else View.VISIBLE
        if (on) ensureWeb().visibility = View.VISIBLE else web?.visibility = View.GONE
    }

    private fun ensureWeb(): WebView {
        web?.let { return it }
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Geo.lastKnown(applicationContext)?.let { centerWebOnCar(it) }
                }
            }
            loadUrl("https://neptun.in.ua/embed/map?lang=uk")
        }
        // Above the map, below the banner and buttons.
        ((findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0) as FrameLayout)
            .addView(w, 1, FrameLayout.LayoutParams(MATCH, MATCH))
        web = w
        return w
    }

    private fun centerWebOnCar(fix: Pair<Double, Double>) {
        val (lat, lon) = fix
        val js = """
            (function(){
              function c(){
                if(window.__neptun&&window.__neptun.map){
                  try{window.__neptun.map.jumpTo({center:[$lon,$lat],zoom:10});}catch(e){}
                  return true;
                }
                return false;
              }
              if(!c()){ var n=0,t=setInterval(function(){ if(c()||++n>40) clearInterval(t); },300); }
            })();
        """.trimIndent()
        web?.evaluateJavascript(js, null)
    }

    private fun beep() {
        val t = tone ?: return
        ui.launch { repeat(3) { t.startTone(ToneGenerator.TONE_PROP_BEEP2, 250); delay(350) } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
