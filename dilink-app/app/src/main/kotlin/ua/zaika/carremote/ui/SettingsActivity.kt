package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ua.zaika.carremote.CarRemoteService

/** App settings: keep-awake, reminders, floating widget, and the charging tariff/currency. */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "⚙ Налаштування"
            setTextColor(Color.WHITE); textSize = 22f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val keepAwake = sw("🅿️  Доступність на стоянці (тримати на зв'язку)", prefs.getBoolean("keep_awake", false)) {
            prefs.edit().putBoolean("keep_awake", it).apply()
        }
        val autos = sw("🔔  Нагадування: відчинене авто / перегрів (Telegram)", prefs.getBoolean(CarRemoteService.KEY_AUTOMATIONS, true)) {
            prefs.edit().putBoolean(CarRemoteService.KEY_AUTOMATIONS, it).apply()
        }
        val widget = sw("📟  Плаваючий віджет (поверх усього)", prefs.getBoolean(CarRemoteService.KEY_WIDGET, false)) {
            if (it && !Settings.canDrawOverlays(this)) runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            prefs.edit().putBoolean(CarRemoteService.KEY_WIDGET, it).apply()
        }
        val camTurn = sw("📹  Камера при повороті (сліпа зона)", prefs.getBoolean(CarRemoteService.KEY_CAM_TURN, false)) {
            prefs.edit().putBoolean(CarRemoteService.KEY_CAM_TURN, it).apply()
        }
        val camDoor = sw("📹  Камера при відкритті дверей (що позаду)", prefs.getBoolean(CarRemoteService.KEY_CAM_DOOR, false)) {
            prefs.edit().putBoolean(CarRemoteService.KEY_CAM_DOOR, it).apply()
        }

        val tariffLabel = TextView(this).apply {
            text = "💰  Вартість зарядки (для розрахунку витрат у Поїздках)"
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }
        val tariff = EditText(this).apply {
            hint = "Тариф, напр. 4.32 за кВт·год"
            setText(prefs.getString(CarRemoteService.KEY_TARIFF, ""))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8A97AD"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            onChange { prefs.edit().putString(CarRemoteService.KEY_TARIFF, it).apply() }
        }
        val currency = EditText(this).apply {
            hint = "Валюта, напр. грн"
            setText(prefs.getString(CarRemoteService.KEY_CURRENCY, "грн"))
            setTextColor(Color.WHITE); setHintTextColor(Color.parseColor("#8A97AD"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            onChange { prefs.edit().putString(CarRemoteService.KEY_CURRENCY, it).apply() }
        }

        val hint = TextView(this).apply {
            setTextColor(Color.parseColor("#8A97AD")); textSize = 13f
            setPadding(dp(16), dp(14), dp(16), dp(24))
            text = "Віджет потребує дозволу «Поверх інших застосунків». Поїздки читаються з журналу " +
                "авто (потрібен дозвіл на сховище). Витрати = кВт·год × тариф. Камери запрацюють " +
                "після налаштування адрес на увімкненому авто."
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(keepAwake); addView(autos); addView(widget)
                addView(camTurn); addView(camDoor)
                addView(tariffLabel); addView(tariff); addView(currency); addView(hint)
            })
        })
    }

    private fun sw(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(Color.WHITE); textSize = 16f
        isChecked = checked
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun EditText.onChange(save: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = save(s?.toString()?.trim() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
