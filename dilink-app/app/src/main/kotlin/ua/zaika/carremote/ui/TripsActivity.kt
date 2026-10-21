package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
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

/** Theme-aware palette for the trips table (Apple light / dark). */
private class Pal(
    val bg: Int, val header: Int, val zebra: Int, val rowAlt: Int, val divider: Int,
    val text: Int, val muted: Int, val accent: Int, val good: Int, val mid: Int, val high: Int,
) {
    companion object {
        fun of(ctx: Context): Pal = if (Ui.isLight(ctx)) Pal(
            bg = 0xFFF2F2F7.toInt(), header = 0xFFFFFFFF.toInt(), zebra = 0xFFFFFFFF.toInt(),
            rowAlt = 0xFFF6F6FA.toInt(), divider = 0x14000000, text = 0xFF000000.toInt(),
            muted = 0xFF8E8E93.toInt(), accent = 0xFF34C759.toInt(),
            good = 0xFF34C759.toInt(), mid = 0xFFFF9500.toInt(), high = 0xFFFF3B30.toInt(),
        ) else Pal(
            bg = 0xFF000000.toInt(), header = 0xFF1C1C1E.toInt(), zebra = 0xFF121214.toInt(),
            rowAlt = 0xFF000000.toInt(), divider = 0x1FFFFFFF, text = 0xFFFFFFFF.toInt(),
            muted = 0xFF98989F.toInt(), accent = 0xFF30D158.toInt(),
            good = 0xFF30D158.toInt(), mid = 0xFFFF9F0A.toInt(), high = 0xFFFF453A.toInt(),
        )
    }
}

private const val W_DATE = 1.5f
private const val W_TIME = 1.0f
private const val W_DUR = 1.0f
private const val W_KM = 1.0f
private const val W_KWH = 1.3f
private const val W_EFF = 1.0f
private const val W_COST = 1.0f

private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The car's full trip journal as a dense table. */
class TripsActivity : Activity() {

    private val dens get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private val pal by lazy { Pal.of(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val tariff = prefs.getString(CarRemoteService.KEY_TARIFF, "")?.toDoubleOrNull()
        val cur = prefs.getString(CarRemoteService.KEY_CURRENCY, "грн")?.ifBlank { "грн" } ?: "грн"
        val trips = EnergyDb.trips(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        root.addView(TextView(this).apply {
            text = "Поїздки"
            setTextColor(pal.text); textSize = 26f; typeface = sfBold
            setPadding(dp(4), dp(2), 0, dp(8))
        })

        if (trips.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (!EnergyDb.available())
                    "Не вдалось прочитати журнал авто. Дай дозвіл «Пам'ять» у ⚙ Налаштування."
                else "Поки порожньо."
                setTextColor(pal.muted); textSize = 14f
            })
            setContentView(root); return
        }

        lifetimeLine(CarRemoteService.lastSnapshot, tariff, cur)?.let { root.addView(it) }
        root.addView(loggedLine(trips, tariff, cur))
        root.addView(headerRow(cur))
        root.addView(divider())
        root.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TripsActivity)
            adapter = TripAdapter(trips, tariff, dens, pal)
            clipToPadding = false
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun n(x: Double) = "%,.0f".format(x).replace(",", " ")

    private fun lifetimeLine(snap: CarSnapshot?, tariff: Double?, cur: String): View? {
        val kwh = snap?.lifetimeKwh?.toDouble()?.takeIf { it > 0 } ?: return null
        val km = snap.mileageKm?.toDouble()?.takeIf { it > 0 } ?: return null
        val cost = tariff?.let { " · ${n(kwh * it)} $cur" } ?: ""
        return sumLine("Весь час", pal.accent, "${n(km)} км · ${n(kwh)} кВт·год · ${"%.1f".format(kwh / km * 100)}/100$cost")
    }

    private fun loggedLine(trips: List<EcTrip>, tariff: Double?, cur: String): View {
        val km = trips.sumOf { it.km }
        val kwh = trips.sumOf { it.kwh }
        val per = if (km >= 1) "%.1f".format(kwh / km * 100) else "—"
        val cost = tariff?.let { " · ${n(kwh * it)} $cur" } ?: ""
        val since = trips.minOfOrNull { it.startMs }?.let {
            " з " + SimpleDateFormat("dd.MM.yy", Locale("uk")).format(Date(it))
        } ?: ""
        return sumLine("Журнал$since", pal.muted, "${trips.size} · ${n(km)} км · ${n(kwh)} кВт·год · $per/100$cost")
    }

    private fun sumLine(label: String, labelColor: Int, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
        addView(TextView(this@TripsActivity).apply {
            text = "$label:"; setTextColor(labelColor); textSize = 13f; typeface = sfSemibold
            setPadding(0, 0, dp(8), 0)
        })
        addView(TextView(this@TripsActivity).apply { text = value; setTextColor(pal.text); textSize = 13f; typeface = sf })
    }

    private fun headerRow(cur: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(pal.header)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        fun h(t: String, w: Float, end: Boolean) = TextView(this).apply {
            text = t; setTextColor(pal.muted); textSize = 11f; typeface = sfSemibold
            if (end) gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, w)
        }
        r.addView(h("Дата", W_DATE, false))
        r.addView(h("Час", W_TIME, false))
        r.addView(h("Трив", W_DUR, true))
        r.addView(h("км", W_KM, true))
        r.addView(h("кВт·год", W_KWH, true))
        r.addView(h("/100", W_EFF, true))
        r.addView(h(cur, W_COST, true))
        return r
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(pal.divider); layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    private class TripAdapter(val items: List<EcTrip>, val tariff: Double?, val d: Float, val pal: Pal) :
        RecyclerView.Adapter<TripAdapter.VH>() {

        private fun dp(v: Int) = (v * d).toInt()
        private val fDate = SimpleDateFormat("dd.MM", Locale("uk"))
        private val fTime = SimpleDateFormat("HH:mm", Locale("uk"))

        class VH(
            val root: LinearLayout, val date: TextView, val time: TextView, val dur: TextView,
            val km: TextView, val kwh: TextView, val eff: TextView, val cost: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            fun cell(color: Int, w: Float, end: Boolean = false, bold: Boolean = false) = TextView(ctx).apply {
                textSize = 13f; setTextColor(color); maxLines = 1
                typeface = if (bold) sfSemibold else sf
                if (end) gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP, w)
            }
            val date = cell(pal.text, W_DATE)
            val time = cell(pal.muted, W_TIME)
            val dur = cell(pal.muted, W_DUR, end = true)
            val km = cell(pal.text, W_KM, end = true, bold = true)
            val kwh = cell(pal.muted, W_KWH, end = true)
            val eff = cell(pal.muted, W_EFF, end = true)
            val cost = cell(pal.accent, W_COST, end = true)
            val cells = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                addView(date); addView(time); addView(dur); addView(km); addView(kwh); addView(eff); addView(cost)
            }
            val line = View(ctx).apply {
                setBackgroundColor(pal.divider); layoutParams = LinearLayout.LayoutParams(MATCH, 1)
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                addView(cells); addView(line)
            }
            return VH(root, date, time, dur, km, kwh, eff, cost)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items[position]
            h.root.setBackgroundColor(if (position % 2 == 0) pal.zebra else pal.rowAlt)
            h.date.text = fDate.format(Date(t.startMs))
            h.time.text = fTime.format(Date(t.startMs))
            h.dur.text = dur(t.durationS)
            h.km.text = "%.1f".format(t.km)
            h.kwh.text = "%.1f".format(t.kwh)
            val p = t.per100
            h.eff.text = p?.let { "%.1f".format(it) } ?: ""
            h.eff.setTextColor(when { p == null -> pal.muted; p < 17 -> pal.good; p < 22 -> pal.mid; else -> pal.high })
            h.cost.text = if (tariff != null) "%.0f".format(t.kwh * tariff) else ""
        }

        private fun dur(s: Long): String {
            if (s <= 0) return ""
            val m = s / 60
            return if (m >= 60) "%d:%02d".format(m / 60, m % 60) else "${m}хв"
        }

        override fun getItemCount() = items.size
    }
}
