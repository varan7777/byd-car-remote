package ua.zaika.carremote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.air.AirState
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.Controls
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.ui.AirAlertActivity
import ua.zaika.carremote.ui.DashboardView
import ua.zaika.carremote.ui.SettingsActivity
import ua.zaika.carremote.ui.TripsActivity
import ua.zaika.carremote.ui.Ui
import ua.zaika.carremote.ui.dpv
import ua.zaika.carremote.ui.filledPill
import ua.zaika.carremote.ui.rounded
import ua.zaika.carremote.ui.sf
import ua.zaika.carremote.ui.sfSemibold
import ua.zaika.carremote.ui.tintedPill

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView
    private lateinit var airLine: TextView
    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = AdbShell(this)
        car = Autoservice(adb)
        dashboard = DashboardView(this)

        statusLine = TextView(this).apply {
            setTextColor(Ui.text2c(context)); textSize = 11f; typeface = sf
            setPadding(dpv(18), dpv(6), dpv(18), dpv(10))
        }
        airLine = TextView(this).apply {
            setTextColor(Ui.textc(context)); textSize = 13f; typeface = sfSemibold
            setPadding(dpv(14), dpv(7), dpv(14), dpv(7))
            text = "🛰️ Повітряна обстановка: завантаження…"
            background = rounded(Ui.card(context), 12)
            setOnClickListener { openAirMap() }
        }

        val cleanButton = tintedPill("🌬 Очистити", Ui.GREEN) { cleanAir() }
        val airButton = tintedPill("🛡 Тривоги", Ui.RED) { openAirMap() }
        val tripsButton = tintedPill("📈 Поїздки", Ui.BLUE) {
            startActivity(Intent(this, TripsActivity::class.java))
        }
        val settingsButton = tintedPill("⚙", Ui.text2c(this)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpv(12), dpv(4), dpv(12), dpv(6))
            addView(cleanButton, cell(1f)); addView(airButton, cell(1f))
            addView(tripsButton, cell(1f)); addView(settingsButton, cell(0.5f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(context))
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(airLine, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dpv(12), dpv(2), dpv(12), dpv(2)) })
            addView(buttons)
            addView(statusLine)
        })

        CarRemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        pollJob = ui.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { CarSnapshot.read(car) }
                dashboard.snapshot = snap
                statusLine.text = "Car Remote v${BuildConfig.VERSION_NAME}   ·   " +
                    "${CarRemoteService.adbStatus}   ·   ${CarRemoteService.telegramStatus}   ·   Varan7777"
                renderAir(CarRemoteService.airState)
                delay(3_000)
            }
        }
    }

    override fun onPause() { pollJob?.cancel(); super.onPause() }

    override fun onDestroy() { ui.cancel(); adb.close(); super.onDestroy() }

    private fun renderAir(s: AirState?) {
        if (s == null) return
        val radius = prefs.getInt(CarRemoteService.KEY_RADIUS, CarRemoteService.DEFAULT_RADIUS).toDouble()
        airLine.text = s.oneLine(radius)
        val light = Ui.isLight(this)
        // Red: active air-raid in our oblast, or a threat inside the radius. Yellow: threats exist
        // but far. Green: clear. Grey: no data / error.
        val red = s.oblastAlert != null || s.within.isNotEmpty()
        val yellow = !red && s.nearest != null
        val (bg, fg) = when {
            red -> (if (light) 0xFFFFDAD6.toInt() else 0xFF4A1512.toInt()) to Ui.RED
            yellow -> (if (light) 0xFFFFF0C2.toInt() else 0xFF3A2E0C.toInt()) to Ui.ORANGE
            s.error != null -> Ui.card(this) to Ui.text2c(this)
            else -> (if (light) 0xFFDDF3E2.toInt() else 0xFF13251A.toInt()) to Ui.GREEN
        }
        airLine.setTextColor(fg)
        (airLine.background as? GradientDrawable)?.setColor(bg) ?: airLine.setBackgroundColor(bg)
    }

    /** One-shot cabin air purification (verified on this car: status flips 0→1). */
    private fun cleanAir() {
        ui.launch {
            val r = withContext(Dispatchers.IO) {
                if (!car.isReachable()) null
                else {
                    val c = Controls.CLEAN_AIR
                    car.write(c.dev, c.fid, c.value)
                    car.getInt(Fids.DEV_CLIMATE, Fids.QUICK_CLEAN_AIR) == 1
                }
            }
            toast(when (r) {
                null -> "Авто не на зв'язку"
                true -> "🌬 Очищення повітря запущено"
                else -> "Команду надіслано (без підтвердження)"
            })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun openAirMap() = startActivity(Intent(this, AirAlertActivity::class.java))

    private fun cell(weight: Float) =
        LinearLayout.LayoutParams(0, WRAP, weight).apply { setMargins(dpv(5), 0, dpv(5), 0) }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
