package ua.zaika.carremote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.air.AirAlertMonitor
import ua.zaika.carremote.air.AirState
import ua.zaika.carremote.air.Speaker
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.ui.AirAlertActivity
import ua.zaika.carremote.ui.DashboardView

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private var airJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView
    private lateinit var airLine: TextView
    private val airMonitor by lazy { AirAlertMonitor(applicationContext) }
    private var tone: ToneGenerator? = null
    private var speaker: Speaker? = null
    private var airAlarmActive = false

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
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull()
        speaker = Speaker(this)

        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val keepAwake = Switch(this).apply {
            text = "🅿️  Доступність на стоянці (тримати на зв'язку)"
            setTextColor(Color.WHITE)
            textSize = 16f
            isChecked = prefs.getBoolean("keep_awake", false)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("keep_awake", checked).apply() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(airLine)
            addView(airButton)
            addView(keepAwake)
            addView(statusLine)
        })

        // The remote service runs independently of this screen.
        CarRemoteService.start(this)
    }

    override fun onResume() {
        super.onResume()
        pollJob = ui.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { CarSnapshot.read(car) }
                dashboard.snapshot = snap
                statusLine.text = "Car Remote v${BuildConfig.VERSION_NAME}   ·   " +
                    "${CarRemoteService.adbStatus}   ·   ${CarRemoteService.telegramStatus}"
                delay(3_000)
            }
        }
        airJob = ui.launch { airMonitor.run(::renderAir) }
    }

    override fun onPause() {
        pollJob?.cancel()
        airJob?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        ui.cancel()
        tone?.release(); tone = null
        speaker?.shutdown(); speaker = null
        adb.close()
        super.onDestroy()
    }

    /** Reflect the air situation on the dashboard and beep once when an alarm first appears. */
    private fun renderAir(s: AirState) {
        airLine.text = s.oneLine(airMonitor.radiusKm)
        airLine.setBackgroundColor(
            when {
                s.isAlarm -> 0xE6B3261E.toInt()      // red
                s.error != null -> 0x33704000          // faint amber
                else -> 0x3312351F                     // faint green
            }
        )
        if (s.isAlarm && !airAlarmActive) {
            tone?.let { t -> ui.launch { repeat(3) { t.startTone(ToneGenerator.TONE_PROP_BEEP2, 250); delay(350) } } }
            speaker?.speak(s.voiceAlert())
        }
        airAlarmActive = s.isAlarm
    }

    private fun openAirMap() = startActivity(Intent(this, AirAlertActivity::class.java))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
