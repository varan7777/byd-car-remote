package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ua.zaika.carremote.CarRemoteService

/** App settings: keep-awake, smart reminders, and the floating widget. */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("carremote", Context.MODE_PRIVATE)

        val title = TextView(this).apply {
            text = "⚙ Налаштування"
            setTextColor(Color.WHITE); textSize = 22f
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val keepAwake = sw("🅿️  Доступність на стоянці (тримати на зв'язку)", prefs.getBoolean("keep_awake", false)) { on ->
            prefs.edit().putBoolean("keep_awake", on).apply()
        }
        val autos = sw("🔔  Нагадування: відчинене авто / перегрів (Telegram)", prefs.getBoolean(CarRemoteService.KEY_AUTOMATIONS, true)) { on ->
            prefs.edit().putBoolean(CarRemoteService.KEY_AUTOMATIONS, on).apply()
        }
        val widget = sw("📟  Плаваючий віджет (поверх усього)", prefs.getBoolean(CarRemoteService.KEY_WIDGET, false)) { on ->
            if (on && !Settings.canDrawOverlays(this)) runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            prefs.edit().putBoolean(CarRemoteService.KEY_WIDGET, on).apply()
        }

        val hint = TextView(this).apply {
            setTextColor(Color.parseColor("#8A97AD")); textSize = 13f
            setPadding(dp(16), dp(12), dp(16), dp(20))
            text = "Віджет потребує одноразового дозволу «Поверх інших застосунків». " +
                "Нагадування надсилаються в Telegram, поки авто ввімкнене."
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1420"))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(keepAwake); addView(autos); addView(widget); addView(hint)
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
