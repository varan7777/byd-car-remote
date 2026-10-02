package ua.zaika.carremote

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
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
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.ui.DashboardView

/** Live dashboard on the head unit. The remote itself runs in [CarRemoteService] + Telegram. */
class MainActivity : Activity() {

    private val ui = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private lateinit var adb: AdbShell
    private lateinit var car: Autoservice
    private lateinit var dashboard: DashboardView
    private lateinit var statusLine: TextView

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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(dashboard, LinearLayout.LayoutParams(MATCH, 0, 1f))
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
                statusLine.text = "${CarRemoteService.adbStatus}   ·   ${CarRemoteService.telegramStatus}"
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
