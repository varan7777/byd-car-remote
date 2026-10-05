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
import android.widget.ScrollView
import android.widget.TextView
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.car.EcTrip
import ua.zaika.carremote.car.EnergyDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The car's own trip journal, as cards: distance, real kWh, efficiency (colour-coded) and cost. */
class TripsActivity : Activity() {

    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val tariff = prefs.getString(CarRemoteService.KEY_TARIFF, "")?.toDoubleOrNull()
        val cur = prefs.getString(CarRemoteService.KEY_CURRENCY, "грн")?.ifBlank { "грн" } ?: "грн"
        val trips = EnergyDb.trips(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "📈 Поїздки"
            setTextColor(WHITE); textSize = 24f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, 0, dp(12))
        })

        if (trips.isEmpty()) {
            root.addView(card().apply {
                addView(label(if (!EnergyDb.available())
                    "Не вдалось прочитати журнал авто. Дай дозвіл «Пам'ять» застосунку в ⚙ → Налаштування."
                else "Поки порожньо."))
            })
        } else {
            root.addView(summaryCard(trips, tariff, cur))
            root.addView(space(dp(10)))
            for (t in trips) { root.addView(tripCard(t, tariff, cur)); root.addView(space(dp(8))) }
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BG); addView(root)
        })
    }

    private fun summaryCard(trips: List<EcTrip>, tariff: Double?, cur: String): View {
        val km = trips.sumOf { it.km }
        val kwh = trips.sumOf { it.kwh }
        val per100 = if (km >= 1) kwh / km * 100 else null
        val cost = tariff?.let { kwh * it }
        val c = card(ACCENT_CARD).apply {
            addView(label("УСЬОГО · ${trips.size} поїздок").apply { setTextColor(ACCENT) })
            addView(space(dp(8)))
            val row = LinearLayout(this@TripsActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(stat("%,.0f".format(km).replace(",", " "), "км"), eq())
            row.addView(stat("%,.0f".format(kwh).replace(",", " "), "кВт·год"), eq())
            row.addView(stat(per100?.let { "%.1f".format(it) } ?: "—", "кВт·год/100"), eq())
            if (cost != null) row.addView(stat("%,.0f".format(cost).replace(",", " "), cur), eq())
            addView(row)
        }
        return c
    }

    private fun tripCard(t: EcTrip, tariff: Double?, cur: String): View {
        val fmt = SimpleDateFormat("dd.MM  ·  HH:mm", Locale("uk"))
        val card = card()

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label(fmt.format(Date(t.startMs))), LinearLayout.LayoutParams(0, WRAP, 1f))
        if (tariff != null) top.addView(TextView(this).apply {
            text = "%.1f %s".format(t.kwh * tariff, cur)
            setTextColor(ACCENT); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        })
        card.addView(top)
        card.addView(space(dp(4)))

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        mid.addView(TextView(this).apply {
            text = "%.1f км".format(t.km)
            setTextColor(WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        mid.addView(TextView(this).apply {
            text = "%.1f кВт·год".format(t.kwh)
            setTextColor(MUTED); textSize = 15f
        })
        card.addView(mid)

        t.per100?.let { p ->
            card.addView(space(dp(6)))
            card.addView(effChip(p))
        }
        return card
    }

    /** A rounded efficiency badge coloured by kWh/100. */
    private fun effChip(per100: Double): View {
        val color = when {
            per100 < 17 -> GOOD
            per100 < 22 -> MID
            else -> HIGH
        }
        return TextView(this).apply {
            text = "%.1f кВт·год/100".format(per100)
            setTextColor(color); textSize = 13f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor((color and 0x00FFFFFF) or 0x22000000) // same hue, very faint fill
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
    }

    private fun stat(value: String, unit: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@TripsActivity).apply {
            text = value; setTextColor(WHITE); textSize = 19f; typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@TripsActivity).apply {
            text = unit; setTextColor(MUTED); textSize = 12f
        })
    }

    private fun card(bg: Int = CARD) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun label(s: String) = TextView(this).apply { text = s; setTextColor(MUTED); textSize = 13f }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, h) }
    private fun eq() = LinearLayout.LayoutParams(0, WRAP, 1f)
    private fun dp(v: Int) = (v * d).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val BG = 0xFF0E1420.toInt()
        const val CARD = 0xFF19202E.toInt()
        const val ACCENT_CARD = 0xFF13261C.toInt()
        const val WHITE = 0xFFE6EDF5.toInt()
        const val MUTED = 0xFF8A97AD.toInt()
        const val ACCENT = 0xFF39D98A.toInt()
        const val GOOD = 0xFF39D98A.toInt()
        const val MID = 0xFFFFC14D.toInt()
        const val HIGH = 0xFFFF6B6B.toInt()
    }
}
