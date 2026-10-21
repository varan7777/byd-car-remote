package ua.zaika.carremote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
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

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView
    private lateinit var airLine: TextView
    private lateinit var cleanAirButton: Button
    private lateinit var airChip: TextView
    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = AdbShell(this)
        car = Autoservice(adb)
        dashboard = DashboardView(this)
        statusLine = TextView(this).apply {
            setTextColor(Color.parseColor("#8A97AD"))
            textSize = 12f
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        airLine = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            text = "🛰️ Повітряна обстановка: завантаження…"
            setOnClickListener { openAirMap() }
        }
        val airButton = Button(this).apply {
            text = "🛡️ Карта тривог і БпЛА"
            setOnClickListener { openAirMap() }
        }
        val tripsButton = Button(this).apply {
            text = "📈 Поїздки"
            setOnClickListener { startActivity(Intent(this@MainActivity, TripsActivity::class.java)) }
        }
        val settingsButton = Button(this).apply {
            text = "⚙"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        // Air panel: a colour-coded pollution chip + "clean now" + ionizer.
        airChip = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "🌫 PM2.5 —"
            setBackgroundColor(0xFF2B6B4F.toInt())
        }
        cleanAirButton = Button(this).apply {
            text = "🌬 Очистити"
            setOnClickListener { cleanAir() }
        }
        val ionizerButton = Button(this).apply {
            text = "✨ Іонізатор"
            setOnClickListener { ionizer() }
        }
        val airRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(airChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))
            addView(cleanAirButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            addView(ionizerButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        }
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(airButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
            addView(tripsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            addView(settingsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(airLine)
            addView(airRow)
            addView(actionsRow)
            addView(statusLine)
        })

        // The remote service runs independently of this screen; it also owns the air-raid watch.
        CarRemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        pollJob = ui.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { CarSnapshot.read(car) }
                dashboard.snapshot = snap
                updateAirChip(snap?.pm25In)
                statusLine.text = "Car Remote v${BuildConfig.VERSION_NAME}   ·   " +
                    "${CarRemoteService.adbStatus}   ·   ${CarRemoteService.telegramStatus}   ·   Varan7777"
                renderAir(CarRemoteService.airState)
                delay(3_000)
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        ui.cancel()
        adb.close()
        super.onDestroy()
    }

    /** Reflect the air situation (computed by the service) on the dashboard line. */
    private fun renderAir(s: AirState?) {
        if (s == null) return
        val radius = prefs.getInt(CarRemoteService.KEY_RADIUS, CarRemoteService.DEFAULT_RADIUS).toDouble()
        airLine.text = s.oneLine(radius)
        airLine.setBackgroundColor(
            when {
                s.isAlarm -> 0xE6B3261E.toInt() // red
                s.error != null -> 0x33704000   // faint amber
                else -> 0x3312351F              // faint green
            }
        )
    }

    /** Colour the PM2.5 chip: green (clean) → amber → red (bad). */
    private fun updateAirChip(pm: Int?) {
        if (pm == null) { airChip.text = "🌫 PM2.5 —"; airChip.setBackgroundColor(0xFF3A4252.toInt()); return }
        val (color, label) = when {
            pm <= 12 -> 0xFF2E7D4F.toInt() to "чисто"
            pm <= 35 -> 0xFFB8860B.toInt() to "помірно"
            pm <= 55 -> 0xFFCC6B1F.toInt() to "погано"
            else -> 0xFFB3261E.toInt() to "дуже погано"
        }
        airChip.text = "🌫 PM2.5 $pm\n$label"
        airChip.setBackgroundColor(color)
    }

    /** One-shot cabin air purification (verified on this car). */
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
            toast(when (r) { null -> "Авто не на зв'язку"; true -> "🌬 Очищення запущено"; else -> "Надіслано (без підтвердження)" })
        }
    }

    /** Negative-ion generator — best-effort (likely absent on this car; write is accepted only). */
    private fun ionizer() {
        ui.launch {
            val sent = withContext(Dispatchers.IO) {
                if (!car.isReachable()) null
                else car.write(Fids.DEV_PM25, Fids.PM25_ANION_STATE_SET, 1) ||
                    car.write(Fids.DEV_CLIMATE, Fids.PM25_ANION_STATE_SET, 1)
            }
            toast(if (sent == null) "Авто не на зв'язку" else "✨ Іонізатор: команду надіслано")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openAirMap() = startActivity(Intent(this, AirAlertActivity::class.java))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
