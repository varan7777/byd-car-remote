package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ua.zaika.carremote.car.ChargeLog

/**
 * Pop-up shown when fast charging starts and more than one fast-charge tariff is set:
 * the owner picks which one this session is billed at. Ignoring it keeps the dearer one.
 * Also opened from the charging table to re-bill a past session (extra [EXTRA_PAST]).
 */
class ChargeTariffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)
        val start = intent.getLongExtra(EXTRA_START, 0L)
        val past = intent.getBooleanExtra(EXTRA_PAST, false)
        val cur = prefs.getString(ua.zaika.carremote.CarRemoteService.KEY_CURRENCY, "грн")?.ifBlank { "грн" } ?: "грн"
        val options = buildList {
            ChargeLog.tariff(prefs, "home")?.let { add(Triple("home", "Дім", it)) }
            ChargeLog.DC_KEYS.forEach { k -> ChargeLog.tariff(prefs, k)?.let { add(Triple(k, ChargeLog.tariffName(k), it)) } }
        }.let { all -> if (past) all else all.filter { it.first != "home" } }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(28), dpv(24), dpv(28), dpv(24))
            background = rounded(Ui.CARD, 22)
            addView(TextView(context).apply {
                text = if (past) "Тариф для цієї зарядки" else "⚡ Швидка зарядка — за яким тарифом?"
                setTextColor(Ui.TEXT); textSize = 22f; typeface = sfSemibold
            })
            addView(TextView(context).apply {
                text = if (past) "Вартість зарядки перерахується." else "Якщо не обрати, рахується дорожчий."
                setTextColor(Ui.TEXT2); textSize = 15f; typeface = sf; setPadding(0, dpv(6), 0, dpv(16))
            })
            options.forEach { (key, name, price) ->
                addView(filledPill("$name · ${"%.2f".format(price)} $cur/кВт·год", Ui.BLUE) {
                    if (past) ChargeLog.setTariff(applicationContext, start, key)
                    else prefs.edit().putString(ChargeLog.PREF_CHOSEN + start, key).apply()
                    setResult(RESULT_OK); finish()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpv(5), 0, dpv(5))
                })
            }
            addView(filledPill("Закрити", 0xFF3A4556.toInt()) { finish() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpv(12), 0, 0)
                })
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(box, LinearLayout.LayoutParams(dpv(520), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_PAST = "past"
    }
}
