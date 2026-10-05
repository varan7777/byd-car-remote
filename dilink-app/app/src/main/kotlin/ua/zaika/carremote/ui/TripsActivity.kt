package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.car.EcTrip
import ua.zaika.carremote.car.EnergyDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The car's full trip journal as a compact, colour-coded list with cost. */
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
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        root.addView(TextView(this).apply {
            text = "📈 Поїздки"
            setTextColor(WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(8))
        })

        if (trips.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (!EnergyDb.available())
                    "Не вдалось прочитати журнал авто. Дай дозвіл «Пам'ять» у ⚙ Налаштування."
                else "Поки порожньо."
                setTextColor(MUTED); textSize = 15f; setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        } else {
            root.addView(summary(trips, tariff, cur))
            root.addView(RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@TripsActivity)
                adapter = TripAdapter(trips, tariff, cur, dens)
                setPadding(0, dp(6), 0, dp(8))
                clipToPadding = false
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(root)
    }

    private fun summary(trips: List<EcTrip>, tariff: Double?, cur: String): View {
        val km = trips.sumOf { it.km }
        val kwh = trips.sumOf { it.kwh }
        val per100 = if (km >= 1) kwh / km * 100 else null
        val cost = tariff?.let { kwh * it }
        fun n(x: Double) = "%,.0f".format(x).replace(",", " ")
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(value: String, unit: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@TripsActivity).apply {
                text = value; setTextColor(WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@TripsActivity).apply { text = unit; setTextColor(MUTED); textSize = 11f })
        }
        stats.addView(stat(n(km), "км"), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(stat(n(kwh), "кВт·год"), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(stat(per100?.let { "%.1f".format(it) } ?: "—", "кВт·год/100"), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (cost != null) stats.addView(stat(n(cost), cur), LinearLayout.LayoutParams(0, WRAP, 1f))
        val since = trips.minOfOrNull { it.startMs }?.let {
            " · з " + SimpleDateFormat("dd.MM.yyyy", Locale("uk")).format(Date(it))
        } ?: ""
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(ACCENT_CARD) }
            addView(TextView(this@TripsActivity).apply {
                // The car's energy log is not lifetime — it starts from the earliest record here,
                // so this total is less than the odometer. Show the start date to make that clear.
                text = "УСЬОГО · ${trips.size} поїздок$since"
                setTextColor(ACCENT); textSize = 12f; setPadding(0, 0, 0, dp(6))
            })
            addView(stats)
        }
    }

    private class TripAdapter(
        val items: List<EcTrip>, val tariff: Double?, val cur: String, val d: Float,
    ) : RecyclerView.Adapter<TripAdapter.VH>() {

        private fun dp(v: Int) = (v * d).toInt()
        private val fmt = SimpleDateFormat("dd.MM  HH:mm", Locale("uk"))

        class VH(val container: LinearLayout, val date: TextView, val km: TextView, val kwh: TextView, val eff: TextView, val cost: TextView) :
            RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            fun cell(size: Float, color: Int, weight: Float, end: Boolean = false, bold: Boolean = false) =
                TextView(ctx).apply {
                    textSize = size; setTextColor(color); maxLines = 1
                    if (bold) typeface = Typeface.DEFAULT_BOLD
                    if (end) gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, weight)
                }
            val date = cell(13f, MUTED, 2.1f)
            val km = cell(14f, WHITE, 1.3f, bold = true)
            val kwh = cell(13f, MUTED, 1.6f)
            val eff = cell(13f, MUTED, 1.1f, end = true)
            val cost = cell(13f, ACCENT, 1.3f, end = true)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(12), dp(7))
                addView(date); addView(km); addView(kwh); addView(eff); addView(cost)
            }
            val divider = View(ctx).apply {
                setBackgroundColor(0xFF1A2230.toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                addView(row); addView(divider)
            }
            return VH(container, date, km, kwh, eff, cost)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.date.text = fmt.format(Date(t.startMs))
            h.km.text = "%.1f км".format(t.km)
            h.kwh.text = "%.1f кВт·год".format(t.kwh)
            val p = t.per100
            h.eff.text = p?.let { "%.1f".format(it) } ?: ""
            h.eff.setTextColor(when { p == null -> MUTED; p < 17 -> GOOD; p < 22 -> MID; else -> HIGH })
            h.cost.text = if (tariff != null) "%.0f %s".format(t.kwh * tariff, cur) else ""
        }

        override fun getItemCount() = items.size

        private companion object {
            const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
            const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
            const val WHITE = 0xFFE6EDF5.toInt(); const val MUTED = 0xFF8A97AD.toInt()
            const val ACCENT = 0xFF39D98A.toInt()
            const val GOOD = 0xFF39D98A.toInt(); const val MID = 0xFFFFC14D.toInt(); const val HIGH = 0xFFFF6B6B.toInt()
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val BG = 0xFF0E1420.toInt()
        const val ACCENT_CARD = 0xFF13261C.toInt()
        const val WHITE = 0xFFE6EDF5.toInt()
        const val MUTED = 0xFF8A97AD.toInt()
        const val ACCENT = 0xFF39D98A.toInt()
    }
}
