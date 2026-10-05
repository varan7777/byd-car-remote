package ua.zaika.carremote.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ua.zaika.carremote.car.TripLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A simple journal of drives: distance, duration, real kWh/100 and SOC used, newest first. */
class TripsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = TripLog(applicationContext)

        val title = TextView(this).apply {
            text = "📈 Поїздки"
            setTextColor(Color.WHITE); textSize = 22f
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        val body = TextView(this).apply {
            setTextColor(Color.parseColor("#E6EDF5")); textSize = 15f
            setPadding(dp(16), dp(6), dp(16), dp(16))
            setLineSpacing(dp(6).toFloat(), 1f)
            text = render(log)
        }
        val clear = Button(this).apply {
            text = "🗑 Очистити журнал"
            setOnClickListener { log.clear(); body.text = render(log) }
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(LinearLayout(this@TripsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(body)
                addView(clear, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    gravity = Gravity.START; setMargins(dp(16), dp(8), dp(16), dp(24))
                })
            })
        })
    }

    private fun render(log: TripLog): CharSequence {
        val trips = log.recent(200)
        if (trips.isEmpty()) return "Поки порожньо. Поїздки запишуться автоматично під час руху."
        val totalKm = trips.sumOf { it.distanceKm }
        val totalKwh = trips.mapNotNull { it.kwh }.sum()
        val sb = StringBuilder()
        sb.append("Усього: %.0f км".format(totalKm))
        if (totalKwh > 0) {
            sb.append(" · %.1f кВт·год".format(totalKwh))
            if (totalKm >= 1) sb.append(" · сер. %.1f кВт·год/100".format(totalKwh / totalKm * 100))
        }
        sb.append("\n\n")
        val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale("uk"))
        for (t in trips) {
            sb.append("• ").append(fmt.format(Date(t.startMs)))
            sb.append("   %.1f км".format(t.distanceKm))
            if (t.durationMin > 0) sb.append(" · %d хв".format(t.durationMin))
            t.per100?.let { sb.append(" · %.1f кВт·год/100".format(it)) }
            if (t.startSoc != null && t.endSoc != null) {
                sb.append(" · SOC %.0f→%.0f%%".format(t.startSoc, t.endSoc))
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}
