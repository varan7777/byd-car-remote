package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.EcTrip
import ua.zaika.carremote.car.EnergyDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BG = 0xFF0E1420.toInt()
private const val WHITE = 0xFFE6EDF5.toInt()
private const val MUTED = 0xFF8A97AD.toInt()
private const val ACCENT = 0xFF39D98A.toInt()
private const val GOOD = 0xFF39D98A.toInt()
private const val MID = 0xFFFFC14D.toInt()
private const val HIGH = 0xFFFF6B6B.toInt()
// Shared column weights so the header lines up with the rows.
private const val W_DATE = 2.4f
private const val W_KM = 1.0f
private const val W_KWH = 1.3f
private const val W_EFF = 1.0f
private const val W_COST = 1.1f
private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The car's full trip journal — dense, one line per trip, with cost. */
class TripsActivity : Activity() {

    private val dens get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val tariff = prefs.getString(CarRemoteService.KEY_TARIFF, "")?.toDoubleOrNull()
        val cur = prefs.getString(CarRemoteService.KEY_CURRENCY, "грн")?.ifBlank { "грн" } ?: "грн"
        val trips = EnergyDb.trips(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        root.addView(TextView(this).apply {
            text = "📈 Поїздки"
            setTextColor(WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })

        if (trips.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (!EnergyDb.available())
                    "Не вдалось прочитати журнал авто. Дай дозвіл «Пам'ять» у ⚙ Налаштування."
                else "Поки порожньо."
                setTextColor(MUTED); textSize = 14f
            })
            setContentView(root); return
        }

        lifetimeLine(CarRemoteService.lastSnapshot, tariff, cur)?.let { root.addView(it) }
        root.addView(loggedLine(trips, tariff, cur))
        root.addView(headerRow(cur))
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TripsActivity)
            adapter = TripAdapter(trips, tariff, dens)
            clipToPadding = false
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun n(x: Double) = "%,.0f".format(x).replace(",", " ")

    private fun lifetimeLine(snap: CarSnapshot?, tariff: Double?, cur: String): View? {
        val kwh = snap?.lifetimeKwh?.toDouble()?.takeIf { it > 0 } ?: return null
        val km = snap.mileageKm?.toDouble()?.takeIf { it > 0 } ?: return null
        val cost = tariff?.let { " · ${n(kwh * it)} $cur" } ?: ""
        return sumLine("Весь час", ACCENT,
            "${n(km)} км · ${n(kwh)} кВт·год · ${"%.1f".format(kwh / km * 100)}/100$cost")
    }

    private fun loggedLine(trips: List<EcTrip>, tariff: Double?, cur: String): View {
        val km = trips.sumOf { it.km }
        val kwh = trips.sumOf { it.kwh }
        val per = if (km >= 1) "%.1f".format(kwh / km * 100) else "—"
        val cost = tariff?.let { " · ${n(kwh * it)} $cur" } ?: ""
        val since = trips.minOfOrNull { it.startMs }?.let {
            " з " + SimpleDateFormat("dd.MM.yy", Locale("uk")).format(Date(it))
        } ?: ""
        return sumLine("Журнал$since", MUTED, "${trips.size} · ${n(km)} км · ${n(kwh)} кВт·год · $per/100$cost")
    }

    private fun sumLine(label: String, labelColor: Int, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(2), dp(3), dp(2), dp(3))
        addView(TextView(this@TripsActivity).apply {
            text = "$label:"; setTextColor(labelColor); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8), 0)
        })
        addView(TextView(this@TripsActivity).apply { text = value; setTextColor(WHITE); textSize = 13f })
    }

    private fun headerRow(cur: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        fun h(t: String, w: Float, end: Boolean) = TextView(this).apply {
            text = t; setTextColor(0xFF6B7891.toInt()); textSize = 11f
            if (end) gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, w)
        }
        r.addView(h("Дата", W_DATE, false))
        r.addView(h("км", W_KM, true))
        r.addView(h("кВт·год", W_KWH, true))
        r.addView(h("/100", W_EFF, true))
        r.addView(h(cur, W_COST, true))
        return r
    }

    private class TripAdapter(val items: List<EcTrip>, val tariff: Double?, val d: Float) :
        RecyclerView.Adapter<TripAdapter.VH>() {

        private fun dp(v: Int) = (v * d).toInt()
        private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("uk"))

        class VH(row: LinearLayout, val date: TextView, val km: TextView, val kwh: TextView, val eff: TextView, val cost: TextView) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            fun cell(color: Int, w: Float, end: Boolean = false, bold: Boolean = false) = TextView(ctx).apply {
                textSize = 13f; setTextColor(color); maxLines = 1
                if (bold) typeface = Typeface.DEFAULT_BOLD
                if (end) gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP, w)
            }
            val date = cell(MUTED, W_DATE)
            val km = cell(WHITE, W_KM, end = true, bold = true)
            val kwh = cell(MUTED, W_KWH, end = true)
            val eff = cell(MUTED, W_EFF, end = true)
            val cost = cell(ACCENT, W_COST, end = true)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                addView(date); addView(km); addView(kwh); addView(eff); addView(cost)
            }
            return VH(row, date, km, kwh, eff, cost)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.date.text = fmt.format(Date(t.startMs))
            h.km.text = "%.1f".format(t.km)
            h.kwh.text = "%.1f".format(t.kwh)
            val p = t.per100
            h.eff.text = p?.let { "%.1f".format(it) } ?: ""
            h.eff.setTextColor(when { p == null -> MUTED; p < 17 -> GOOD; p < 22 -> MID; else -> HIGH })
            h.cost.text = if (tariff != null) "%.0f".format(t.kwh * tariff) else ""
        }

        override fun getItemCount() = items.size
    }
}
