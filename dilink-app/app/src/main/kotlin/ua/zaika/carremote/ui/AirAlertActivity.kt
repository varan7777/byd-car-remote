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
    private val placeLabels = PlaceLabelsOverlay()
    private lateinit var banner: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var radiusLabel: TextView
    private lateinit var callout: ThreatCallout

    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }
    private val regions by lazy { RegionLocator(applicationContext) }
    private val raions by lazy { RegionLocator(applicationContext, ua.zaika.carremote.air.NeptunClient(), "raions.geojson") }

    private var allShapes: List<OblastShape> = emptyList()
    private var raionShapes: List<OblastShape> = emptyList()
    private var shadedKeys: Set<String> = emptySet()
    private var raionLevels: Map<String, String> = emptyMap()
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
            setTileSource(TileSourceFactory.MAPNIK) // standard OSM: normal colours, no key
            setMultiTouchControls(true)             // pinch zoom
            setUseDataConnection(true)
            controller.setZoom(11.0)                // ~city/district level
            controller.setCenter(GeoPoint(49.0, 31.5)) // Ukraine, until the first GPS fix
            overlays.add(CopyrightOverlay(this@AirAlertActivity)) // "© OpenStreetMap contributors"
            // Town names drawn by us ABOVE the alert shading and borders (the tiles' own names sit under them).
            overlays.add(placeLabels)
            overlays.add(threatsOverlay)
            // At night the OSM tiles are inverted to a dark navy map, like the factory navigation.
            if (!Ui.isLight(this@AirAlertActivity)) overlayManager.tilesOverlay.setColorFilter(NIGHT_TILES)
            threatsOverlay.onThreatTap = { showThreat(it) }
            threatsOverlay.onEmptyTap = { callout.hide() }
            threatsOverlay.onDrawn = { followThreat() }
        }

        banner = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f; typeface = sfSemibold
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFF243042.toInt(), 16)
            text = "Завантаження повітряної обстановки…"
        }

        callout = ThreatCallout(this) { callout.hide() }
        settingsPanel = buildSettingsPanel()
        val recenter = filledPill("Моє місце", Ui.BLUE) { recenter() }

        // Factory DiLink layout: a navy side panel on the left, the content (map) on the right.
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@AirAlertActivity))
            setPadding(dp(22), dp(20), dp(22), dp(20))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "‹"; setTextColor(Ui.textc(context)); textSize = 34f; typeface = sf
                    gravity = Gravity.CENTER
                    background = tileBg(radius = 22)
                    setOnClickListener { finish() }
                    contentDescription = "Назад"
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(14), 0) })
                addView(TextView(context).apply {
                    text = "Повітряна тривога"; setTextColor(Ui.textc(context)); textSize = 24f; typeface = sfSemibold
                })
            })
            addView(TextView(context).apply {
                text = "NEPTUN · карта загроз навколо авто"; setTextColor(Ui.text2c(context)); textSize = 14f; typeface = sf
                setPadding(0, dp(4), 0, dp(16))
            })
            addView(banner, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(settingsPanel, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(16), 0, 0) })
            addView(legend(), LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(10), 0, 0) })
            addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tintedPill("Журнал тривог", Ui.textc(context)) { showJournal() },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(0, 0, dp(10), 0) })
                addView(recenter, LinearLayout.LayoutParams(0, WRAP, 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.bg(this@AirAlertActivity))
            addView(side, LinearLayout.LayoutParams(dp(380), MATCH))
            addView(FrameLayout(context).apply {
                setPadding(0, dp(12), dp(12), dp(12))
                clipToPadding = true
                // The callout shares the map's frame, so marker pixels are its pixels too.
                addView(FrameLayout(context).apply {
                    addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
                    addView(callout, FrameLayout.LayoutParams(dp(360), WRAP))
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            }, LinearLayout.LayoutParams(0, MATCH, 1f))
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
            raionShapes = withContext(Dispatchers.IO) { runCatching { raions.shapes() }.getOrDefault(emptyList()) }
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
        // Red: air-raid in our oblast or a threat within radius. Amber: threats but far. Green: clear.
        val c = when {
            s.regionLevel == "red" || s.within.isNotEmpty() || (s.oblastAlert != null && s.regionLevel != "yellow") -> 0xFF8E2A24.toInt()
            s.regionLevel == "yellow" -> 0xFF8A7410.toInt()
            s.regionLevel != null -> 0xFF5B2E8C.toInt() // other official level (nuclear / chemical)
            s.nearest != null -> 0xFF7A5410.toInt()
            s.error != null -> 0xFF243042.toInt()
            else -> 0xFF1E4A36.toInt()
        }
        (banner.background as? android.graphics.drawable.GradientDrawable)?.setColor(c) ?: banner.setBackgroundColor(c)
        threatsOverlay.threats = s.all
        threatsOverlay.targets = s.targets
        callout.threatId?.let { id -> s.all.firstOrNull { it.id == id }?.let { showThreat(it) } ?: callout.hide() }
        threatsOverlay.car = s.fix
        if (!centered && s.fix != null) {
            centered = true
            val car = GeoPoint(s.fix.first, s.fix.second)
            val near = s.nearest
            // Open on the car and the nearest threat together, so a threat far away isn't off-screen.
            if (near == null) map.controller.setCenter(car)
            else map.post {
                map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPoints(listOf(car, GeoPoint(near.lat, near.lon))), false, dp(80))
                if (map.zoomLevelDouble > 11.0) map.controller.setZoom(11.0)
            }
        }
        if (s.alertKeys != shadedKeys || s.raionLevels != raionLevels) {
            shadedKeys = s.alertKeys
            raionLevels = s.raionLevels
            applyShading()
        }
        map.invalidate()
    }

    /** Rebuild the oblast borders, shading the ones in [shadedKeys] red. */
    private fun applyShading() {
        if (allShapes.isEmpty()) return
        map.overlays.removeAll { it is Polygon }
        // Raions under an alert: yellow or red fill (yellow alerts are only ever by raion).
        for (sh in raionShapes) {
            val level = raionLevels[sh.key] ?: continue
            for (ring in sh.rings) {
                val pts = ArrayList<GeoPoint>(ring.size / 2)
                var i = 0
                while (i < ring.size) { pts.add(GeoPoint(ring[i + 1], ring[i])); i += 2 }
                map.overlays.add(0, Polygon().apply {
                    points = pts
                    // A light tint and a clear edge: the map and town names must stay readable under it.
                    fillPaint.color = if (level == "yellow") 0x1FF2C230 else 0x1FE24A3B
                    // Raion edges thin and dashed, so they don't read as oblast borders.
                    outlinePaint.color = if (level == "yellow") 0xCCE0A800.toInt() else 0xCCFF4D4D.toInt()
                    outlinePaint.strokeWidth = 1.5f * resources.displayMetrics.density
                    outlinePaint.pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
                    setOnClickListener { _, _, _ -> false }
                })
            }
        }
        // Every oblast gets a clear border; the ones under alert are also shaded red.
        val border = if (Ui.isLight(this)) 0xCC2A3344.toInt() else 0xCCDDE4F0.toInt()
        for (sh in allShapes) for (ring in sh.rings) {
            val alert = sh.key in shadedKeys
            val pts = ArrayList<GeoPoint>(ring.size / 2)
            var i = 0
            while (i < ring.size) { pts.add(GeoPoint(ring[i + 1], ring[i])); i += 2 }
            map.overlays.add(0, Polygon().apply {
                points = pts
                fillPaint.color = if (alert) 0x1FE24A3B else 0x00000000
                outlinePaint.color = if (alert) 0xCCFF4D4D.toInt() else border
                outlinePaint.strokeWidth = (if (alert) 3.5f else 2.5f) * resources.displayMetrics.density
                setOnClickListener { _, _, _ -> false } // taps go through to the threat markers
            })
        }
        map.invalidate()
    }

    /** What each marker on the map means, drawn with the map's own glyphs. */
    private fun legend(): View {
        val items = listOf("uav" to "БпЛА (шахед)", "jet_uav" to "Реактивний БпЛА", "recon" to "Розвідник",
            "fpv" to "FPV-дрон", "missile" to "Крилата ракета", "ballistic" to "Балістика",
            "kab" to "КАБ", "mig31k" to "МіГ-31К")
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            items.chunked(2).forEach { pair ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEach { (kind, name) ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(object : View(context) {
                                override fun onDraw(c: android.graphics.Canvas) =
                                    threatsOverlay.drawGlyph(c, kind, width / 2f, height / 2f, 10f * d, d)
                            }, LinearLayout.LayoutParams(dp(30), dp(28)))
                            addView(TextView(context).apply {
                                text = name; setTextColor(Ui.text2c(context)); textSize = 14f; typeface = sf
                                setPadding(dp(6), 0, 0, 0)
                            })
                        }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    }
                })
            }
            addView(TextView(context).apply {
                text = "«ПС» — з каналу Повітряних сил, сірий — давні дані, коло — «курс сюди», «×3» — група."
                setTextColor(Ui.text2c(context)); textSize = 13f; typeface = sf; setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun buildSettingsPanel(): LinearLayout {
        val current = prefs.getInt(CarRemoteService.KEY_RADIUS, CarRemoteService.DEFAULT_RADIUS)
        val txt = Ui.textc(this)
        radiusLabel = TextView(this).apply {
            setTextColor(txt); textSize = 15f; typeface = sfSemibold
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
            text = "Голос тривоги"; setTextColor(txt); textSize = 15f; typeface = sf
            isChecked = prefs.getBoolean(CarRemoteService.KEY_VOICE, true)
            val off = 0xFF3A4556.toInt()
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Ui.BLUE else off)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CarRemoteService.KEY_VOICE, on).apply()
                trackTintList = android.content.res.ColorStateList.valueOf(if (on) Ui.BLUE else off)
            }
        }
        val test = tintedPill("Перевірити сигнал", Ui.textc(this)) { CarRemoteService.testAlarm(this@AirAlertActivity) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (Ui.isLight(this@AirAlertActivity)) rounded(Ui.card(this@AirAlertActivity), 16) else tileBg()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(radiusLabel)
            addView(slider)
            addView(voice)
            addView(test)
        }
    }

    /** What NEPTUN says about one tracked object, in a bubble pinned to its marker. */
    private fun showThreat(t: ua.zaika.carremote.air.AirThreat) {
        val f = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("uk"))
        val now = System.currentTimeMillis()
        fun ago(ms: Long?) = ms?.let { val m = ((now - it) / 60_000).coerceAtLeast(0); "${f.format(java.util.Date(it))} (${if (m < 1) "щойно" else "$m хв тому"})" }
        val fix = CarRemoteService.airState?.fix
        val dist = fix?.let { Geo.distanceKm(it.first, it.second, t.lat, t.lon) }
        val conf = when (t.confidence) { "high" -> "висока"; "medium" -> "середня"; "low" -> "низька"; else -> t.confidence }
        val rows = listOfNotNull(
            t.title.takeIf { it.isNotBlank() && it != t.label }?.let { "Тип" to it },
            "Де" to listOf(t.locality, t.district, t.region).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "—" },
            dist?.let { "Від вас" to "%.0f км".format(it) },
            "Курс" to (t.heading?.let { "$it° (${compass(it)})" } ?: "невідомий"),
            "Оновлено" to (ago(t.updatedMs) ?: "—"),
            "Достовірність" to "$conf, підтверджень: ${t.sourceCount}, ±%.0f км".format(t.uncertaintyKm),
            t.speedKmh?.let { "Швидкість" to "≈%.0f км/год".format(it) },
            t.airForce?.let { "Повітряні сили" to it.removePrefix("🏍").trim() },
        )
        val first = callout.threatId != t.id
        callout.show(t.id, t.label, t.explanation, rows)
        if (first) callout.post { followThreat() } // place it before the next map frame
    }

    /** Keep the bubble's pointer on its marker as the map pans, zooms or the threat moves. */
    private fun followThreat() {
        val id = callout.threatId ?: return
        threatsOverlay.positionOf(id)?.let { (x, y) -> callout.pointAt(x, y) }
    }

    /** Air alerts around the car over the past week, newest first. */
    private fun showJournal() {
        val log = ua.zaika.carremote.air.AlertLog.all(applicationContext)
        val now = System.currentTimeMillis()
        val week = log.filter { now - it.startMs < 7 * 86_400_000L }
        val totalMin = week.sumOf { ((it.endMs ?: now) - it.startMs) / 60_000 }
        val near = week.flatMap { it.nearIds }.toSet().size
        val f = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale("uk"))
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale("uk"))
        lateinit var dialog: android.app.AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = rounded(Ui.card(this@AirAlertActivity), 20)
            addView(TextView(context).apply { text = "Журнал тривог"; setTextColor(Ui.textc(context)); textSize = 22f; typeface = sfSemibold })
            addView(TextView(context).apply {
                text = if (week.isEmpty()) "За тиждень тривог біля авто не було."
                    else "За тиждень: ${week.size} · разом ${totalMin / 60} год ${totalMin % 60} хв · загроз у радіусі: $near"
                setTextColor(Ui.text2c(context)); textSize = 15f; typeface = sf; setPadding(0, dp(6), 0, dp(12))
            })
            log.take(30).forEach { e ->
                val end = e.endMs?.let { t.format(java.util.Date(it)) } ?: "триває"
                val mins = ((e.endMs ?: now) - e.startMs) / 60_000
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                    addView(TextView(context).apply {
                        text = "${f.format(java.util.Date(e.startMs))}–$end"; setTextColor(Ui.textc(context)); textSize = 15f; typeface = sf
                    }, LinearLayout.LayoutParams(dp(170), WRAP))
                    addView(TextView(context).apply {
                        text = "${ua.zaika.carremote.air.AlertLog.rankName(e.maxRank)} · $mins хв" +
                            if (e.nearIds.isNotEmpty()) " · у радіусі: ${e.nearIds.size}" else ""
                        setTextColor(if (e.maxRank >= 2) Ui.RED else Ui.text2c(context)); textSize = 15f; typeface = sf
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                })
            }
            addView(filledPill("Закрити", Ui.BLUE) { dialog.dismiss() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, dp(12), 0, 0) })
        }
        dialog = android.app.AlertDialog.Builder(this).setView(android.widget.ScrollView(this).apply { addView(box) }).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun compass(deg: Int) = listOf("Пн", "ПнСх", "Сх", "ПдСх", "Пд", "ПдЗх", "Зх", "ПнЗх")[((deg % 360 + 22) / 45) % 8]

    private fun recenter() {
        val fix = Geo.lastKnown(applicationContext) ?: return
        map.controller.animateTo(GeoPoint(fix.first, fix.second))
        map.controller.setZoom(12.0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Invert OSM's light tiles into a dark navy night map with bright labels. */
        val NIGHT_TILES = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
            -0.21f, -0.41f, -0.08f, 0f, 200f,
            -0.21f, -0.41f, -0.08f, 0f, 212f,
            -0.21f, -0.41f, -0.08f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        )))
        const val RADIUS_MIN = 10
        const val RADIUS_MAX = 100

    }
}
