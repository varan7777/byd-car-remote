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
import ua.zaika.carremote.car.CarSnapshot
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
            lifetimeCard(CarRemoteService.lastSnapshot, tariff, cur)?.let {
                root.addView(it); root.addView(gap())
            }
            root.addView(loggedCard(trips, tariff, cur))
            root.addView(RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@TripsActivity)
                adapter = TripAdapter(trips, tariff, cur, dens)
                setPadding(0, dp(6), 0, dp(8))
                clipToPadding = false
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        setContentView(root)
    }

    private fun n(x: Double) = "%,.0f".format(x).replace(",", " ")

    /** Lifetime (BMS) totals from the car's own counters — matches "Прокачано всього". */
    private fun lifetimeCard(snap: CarSnapshot?, tariff: Double?, cur: String): View? {
        val kwh = snap?.lifetimeKwh?.toDouble()?.takeIf { it > 0 } ?: return null
        val km = snap.mileageKm?.toDouble()?.takeIf { it > 0 } ?: return null
        val stats = ArrayList<Pair<String, String>>()
        stats += n(km) to "км"
        stats += n(kwh) to "кВт·год"
        stats += "%.1f".format(kwh / km * 100) to "кВт·год/100"
        tariff?.let { stats += n(kwh * it) to cur }
        return statsCard("ЗА ВЕСЬ ЧАС · BMS", ACCENT, ACCENT_CARD, stats)
    }

    /** Totals from the car's trip log (only since its earliest record). */
    private fun loggedCard(trips: List<EcTrip>, tariff: Double?, cur: String): View {
        val km = trips.sumOf { it.km }
        val kwh = trips.sumOf { it.kwh }
        val stats = ArrayList<Pair<String, String>>()
        stats += n(km) to "км"
        stats += n(kwh) to "кВт·год"
        stats += (if (km >= 1) "%.1f".format(kwh / km * 100) else "—") to "кВт·год/100"
        tariff?.let { stats += n(kwh * it) to cur }
        val since = trips.minOfOrNull { it.startMs }?.let {
            " · з " + SimpleDateFormat("dd.MM.yyyy", Locale("uk")).format(Date(it))
        } ?: ""
        return statsCard("ЖУРНАЛ · ${trips.size}$since", MUTED, CARD, stats)
    }

    private fun statsCard(title: String, titleColor: Int, bg: Int, stats: List<Pair<String, String>>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, unit) in stats) row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@TripsActivity).apply {
                text = value; setTextColor(WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@TripsActivity).apply { text = unit; setTextColor(MUTED); textSize = 11f })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
            addView(TextView(this@TripsActivity).apply {
                text = title; setTextColor(titleColor); textSize = 12f; setPadding(0, 0, 0, dp(6))
            })
            addView(row)
        }
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(8)) }

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
        const val CARD = 0xFF19202E.toInt()
        const val WHITE = 0xFFE6EDF5.toInt()
        const val MUTED = 0xFF8A97AD.toInt()
        const val ACCENT = 0xFF39D98A.toInt()
    }
}
