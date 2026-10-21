package ua.zaika.carremote.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
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
import android.widget.Button
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
            if (it) cameraPermissions()
            prefs.edit().putBoolean(CarRemoteService.KEY_CAM_TURN, it).apply()
        }
        val camDoor = sw("📹  Бокова камера при відкритті дверей на P", prefs.getBoolean(CarRemoteService.KEY_CAM_DOOR, false)) {
            if (it) cameraPermissions()
            prefs.edit().putBoolean(CarRemoteService.KEY_CAM_DOOR, it).apply()
        }

        val cameraSpeed = EditText(this).apply {
            hint = "Камера при повороті: від 5 до 80 км/год"
            setText(prefs.getInt(CarRemoteService.KEY_CAM_MIN_SPEED, 15).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            onChange { text -> text.toIntOrNull()?.takeIf { it in 5..80 }?.let {
                prefs.edit().putInt(CarRemoteService.KEY_CAM_MIN_SPEED, it).apply()
            } }
        }
        val speedLabel = TextView(this).apply {
            text = "Камера при повороті — мінімальна швидкість, км/год"
            setTextColor(Color.WHITE); setPadding(dp(16), dp(12), dp(16), 0)
        }
        val cameraTests = LinearLayout(this).apply {
            for ((name, side) in listOf("◀ Ліва: тест на P" to "left", "Права: тест на P ▶" to "right")) {
                addView(Button(this@SettingsActivity).apply {
                    text = name
                    setOnClickListener {
                        cameraPermissions()
                        startService(Intent(this@SettingsActivity, CarRemoteService::class.java)
                            .setAction(CarRemoteService.ACTION_CAMERA_PREVIEW).putExtra("side", side))
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
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
                "авто (потрібен дозвіл на сховище). Витрати = кВт·год × тариф. Камерам потрібні " +
                "дозволи «Камера» й «Поверх інших застосунків». Тест показує камеру 10 секунд, " +
                "лише на P без руху. На R камери застосунку закриваються. Для поворотів стандартний поріг — 15 км/год."
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(keepAwake); addView(autos); addView(widget)
                addView(camTurn); addView(camDoor)
                addView(speedLabel); addView(cameraSpeed); addView(cameraTests)
                addView(tariffLabel); addView(tariff); addView(currency); addView(hint)
                isFocusableInTouchMode = true
                requestFocus()
            })
        })
    }

    private fun cameraPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 41)
        }
        if (!Settings.canDrawOverlays(this)) runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
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
