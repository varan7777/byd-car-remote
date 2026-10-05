package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polygon
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.air.AirState
import ua.zaika.carremote.air.Geo
import ua.zaika.carremote.air.OblastShape
import ua.zaika.carremote.air.RegionLocator
import java.io.File

/**
 * Air-raid / UAV screen for the head unit.
 *
 * Shows an OpenStreetMap (osmdroid, plain Canvas — no WebGL, so it renders on cheap DiLink
 * units and zooms to street level) with threat markers + trails, the car, and alert oblasts
 * shaded red. The air situation and the audible/spoken alarm are produced by [CarRemoteService];
 * this screen renders the state it publishes and lets the user tune radius/voice (and test the
 * alarm).
 */
class AirAlertActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null

    private lateinit var map: MapView
    private val threatsOverlay = ThreatsOverlay()
    private lateinit var banner: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var radiusLabel: TextView

    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }
    private val regions by lazy { RegionLocator(applicationContext) }

    private var allShapes: List<OblastShape> = emptyList()
    private var shadedKeys: Set<String> = emptySet()
    private var centered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarRemoteService.start(this) // make sure the air watch is running even if opened cold

        // osmdroid needs a UA (OSM tiles reject blank ones) and a cache dir; use app storage
        // so no runtime permission is required.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            val base = File(cacheDir, "osm")
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK) // standard OSM: normal colours, labels, no key
            setMultiTouchControls(true)             // pinch zoom
            setUseDataConnection(true)
            controller.setZoom(11.0)                // ~city/district level
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

        val recenter = Button(this).apply {
            text = "📍 Моє місце"
            setOnClickListener { recenter() }
        }
        val gear = Button(this).apply {
            text = "⚙"
            setOnClickListener {
                settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(gear)
            addView(recenter)
        }

        settingsPanel = buildSettingsPanel()

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(banner, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(settingsPanel, FrameLayout.LayoutParams(dp(300), WRAP, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(64), dp(12), 0)
            })
            addView(buttons, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            })
        })
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        // Pull the service's latest state every ~2 s, but redraw ~2.5×/s in between so the
        // dead-reckoned threat markers glide instead of jumping once per NEPTUN poll.
        job = ui.launch {
            var tick = 0
            while (isActive) {
                if (tick % 5 == 0) render(CarRemoteService.airState) else map.invalidate()
                tick++
                delay(400)
            }
        }
        ui.launch {
            allShapes = withContext(Dispatchers.IO) { regions.shapes() }
            applyShading() // shade now if an alert arrived before shapes finished loading
        }
    }

    override fun onPause() {
        job?.cancel()
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui.cancel()
        map.onDetach()
        super.onDestroy()
    }

    private fun render(s: AirState?) {
        if (s == null) return
        val radius = prefs.getInt(CarRemoteService.KEY_RADIUS, CarRemoteService.DEFAULT_RADIUS).toDouble()
        banner.text = s.bannerText(radius)
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

    private fun buildSettingsPanel(): LinearLayout {
        val current = prefs.getInt(CarRemoteService.KEY_RADIUS, CarRemoteService.DEFAULT_RADIUS)
        radiusLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            text = "Радіус тривоги: $current км"
        }
        val slider = SeekBar(this).apply {
            max = RADIUS_MAX - RADIUS_MIN
            progress = (current - RADIUS_MIN).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val km = RADIUS_MIN + p
                    radiusLabel.text = "Радіус тривоги: $km км"
                    prefs.edit().putInt(CarRemoteService.KEY_RADIUS, km).apply() // service applies it live
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val voice = Switch(this).apply {
            text = "Голос тривоги"; setTextColor(Color.WHITE); textSize = 15f
            isChecked = prefs.getBoolean(CarRemoteService.KEY_VOICE, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(CarRemoteService.KEY_VOICE, on).apply() }
        }
        val test = Button(this).apply {
            text = "🔊 Перевірити сигнал"
            setOnClickListener { CarRemoteService.testAlarm(this@AirAlertActivity) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xF01A2130.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(radiusLabel)
            addView(slider)
            addView(voice)
            addView(test)
        }
    }

    private fun recenter() {
        val fix = Geo.lastKnown(applicationContext) ?: return
        map.controller.animateTo(GeoPoint(fix.first, fix.second))
        map.controller.setZoom(12.0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val RADIUS_MIN = 10
        const val RADIUS_MAX = 100
    }
}
