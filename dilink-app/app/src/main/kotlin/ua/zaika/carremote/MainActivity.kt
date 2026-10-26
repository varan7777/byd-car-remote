package ua.zaika.carremote

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.Controls
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.ui.AirAlertActivity
import ua.zaika.carremote.ui.DashboardView
import ua.zaika.carremote.ui.SettingsActivity
import ua.zaika.carremote.ui.TripsActivity
import ua.zaika.carremote.ui.Ui
import ua.zaika.carremote.ui.AdBanner
import ua.zaika.carremote.ui.dpv
import ua.zaika.carremote.ui.sf

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView
    private lateinit var adBanner: AdBanner
    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = AdbShell(this)
        car = Autoservice(adb)
        dashboard = DashboardView(this)
        adBanner = AdBanner(this)

        statusLine = TextView(this).apply {
            setTextColor(Ui.TEXT2); textSize = 11f; typeface = sf
            setPadding(dpv(18), dpv(2), dpv(18), dpv(8))
        }
        dashboard.onTile = { tile ->
            when (tile) {
                DashboardView.Tile.CLIMATE -> toggleClimate()
                DashboardView.Tile.CLEAN_AIR -> cleanAir()
                DashboardView.Tile.CHARGING -> startActivity(Intent(this, TripsActivity::class.java))
                DashboardView.Tile.AIR_ALERT -> openAirMap()
                DashboardView.Tile.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> toast("Керування з планшета — через бот @Varanbyd_bot")
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF151C28.toInt())
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(adBanner, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dpv(18), dpv(4), dpv(18), dpv(2)) })
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
                dashboard.air = CarRemoteService.airState
                adBanner.render(prefs)
                delay(3_000)
            }
        }
    }

    override fun onPause() { pollJob?.cancel(); super.onPause() }

    override fun onDestroy() { ui.cancel(); adb.close(); super.onDestroy() }

    /** Climate on/off — the one control verified on this car; confirmed by readback. */
    private fun toggleClimate() {
        val on = dashboard.snapshot?.acOn ?: return toast("Авто не на зв'язку")
        val c = if (on) Controls.CLIMATE_OFF else Controls.CLIMATE_ON
        ui.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!car.isReachable()) null
                else { car.write(c.dev, c.fid, c.value); delay(1_500); car.getInt(Fids.DEV_CLIMATE, Fids.AC_STATUS) == c.value }
            }
            toast(when (ok) { null -> "Авто не на зв'язку"; true -> if (on) "Клімат вимкнено" else "Клімат увімкнено"; else -> "Команду надіслано (без підтвердження)" })
        }
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
