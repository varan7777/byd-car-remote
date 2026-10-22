package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.telegram.HubBot

/** App settings, grouped Apple-style: keep-awake, reminders, widget, cameras, air, charging tariffs. */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(16), dpv(18), dpv(16), dpv(28))
        }

        root.addView(TextView(this).apply {
            text = "Налаштування"; setTextColor(Ui.textc(context)); textSize = 30f; typeface = sfBold
            setPadding(dpv(4), 0, 0, dpv(14))
        })

        root.addView(header("TELEGRAM / VARAN HUB"))
        root.addView(card(
            inputRow("Код прив’язки з @Varanbyd_bot", HubBot.CODE, "6 цифр", numeric = true),
            TextView(this).apply {
                text = if (prefs.getString(HubBot.TOKEN, "").isNullOrBlank()) "Прив’язати авто →" else "Авто прив’язане · ввести новий код →"
                setTextColor(Ui.ORANGE); textSize = 16f; typeface = sfSemibold
                setPadding(dpv(16), dpv(16), dpv(16), dpv(16))
                setOnClickListener {
                    val code = prefs.getString(HubBot.CODE, "").orEmpty()
                    if (!code.matches(Regex("[0-9]{6}"))) {
                        android.widget.Toast.makeText(this@SettingsActivity, "Введіть 6 цифр із бота", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        prefs.edit().putBoolean(HubBot.ENABLED, true).remove(HubBot.TOKEN).remove(HubBot.OWNER).remove(HubBot.CONFIG).apply()
                        stopService(Intent(this@SettingsActivity, CarRemoteService::class.java))
                        CarRemoteService.start(this@SettingsActivity)
                        android.widget.Toast.makeText(this@SettingsActivity, "Підключаємо авто… Статус — на головному екрані", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
        ))
        root.addView(note("Відкрийте @Varanbyd_bot у Telegram → поділіться своїм номером → введіть отриманий код. " +
            "Реклама вимикається назавжди після разової оплати за посиланням у банері або командою /noads у боті."))

        root.addView(header("СИСТЕМА"))
        root.addView(card(
            switchRow("🅿️  Доступність на стоянці", "keep_awake", false),
            switchRow("🔔  Нагадування: відчинене авто / перегрів", CarRemoteService.KEY_AUTOMATIONS, true),
            switchRow("📟  Плаваючий віджет (поверх усього)", CarRemoteService.KEY_WIDGET, false) {
                if (it && !Settings.canDrawOverlays(this)) runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            },
        ))

        root.addView(header("КАМЕРИ"))
        root.addView(card(
            switchRow("📹  Камера при повороті (сліпа зона)", CarRemoteService.KEY_CAM_TURN, false) { if (it) cameraPermissions() },
            switchRow("📹  Камера при відкритті дверей", CarRemoteService.KEY_CAM_DOOR, false) { if (it) cameraPermissions() },
            inputRow("Поворот — від швидкості, км/год", CarRemoteService.KEY_CAM_MIN_SPEED, "15", numeric = true),
        ))

        root.addView(header("ПОВІТРЯ"))
        root.addView(card(
            switchRow("✨  Іонізатор повітря", "ionizer", false) { sendIonizer(it) },
        ))
        root.addView(note("Іонізатор працює лише на версіях DiLink, де він підтримується апаратно. " +
            "На цьому авто стан не зчитується — команда надсилається без підтвердження."))

        root.addView(header("ТАРИФИ ЗАРЯДКИ"))
        root.addView(card(
            inputRow("🏠  Дім, за кВт·год", CarRemoteService.KEY_TARIFF, "напр. 4.32", decimal = true),
            inputRow("⚡  Швидка 1, за кВт·год", CarRemoteService.KEY_TARIFF_DC1, "напр. 18", decimal = true),
            inputRow("⚡  Швидка 2, за кВт·год", CarRemoteService.KEY_TARIFF_DC2, "напр. 25", decimal = true),
            inputRow("Валюта", CarRemoteService.KEY_CURRENCY, "грн", numeric = false, defaultText = "грн"),
        ))

        root.addView(note("Витрати в Поїздках = кВт·год × тариф. Камери показуються на планшеті. " +
            "Віджет потребує дозволу «Поверх інших застосунків», Поїздки — дозволу на сховище."))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.bg(this@SettingsActivity))
            addView(root)
        })
    }

    // --- grouped-list builders ---

    private fun header(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Ui.text2c(context)); textSize = 12f; typeface = sfSemibold
        setPadding(dpv(10), dpv(16), dpv(10), dpv(7))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Ui.text2c(context)); textSize = 12f; typeface = sf
        setPadding(dpv(10), dpv(7), dpv(10), 0)
    }

    private fun card(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Ui.card(context), 16)
        rows.forEachIndexed { i, row ->
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (i < rows.size - 1) addView(View(this@SettingsActivity).apply {
                setBackgroundColor(Ui.sepc(this@SettingsActivity))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { setMargins(dpv(16), 0, 0, 0) }
            })
        }
    }

    private fun switchRow(label: String, key: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) =
        Switch(this).apply {
            text = label; setTextColor(Ui.textc(context)); textSize = 16f; typeface = sf
            isChecked = prefs.getBoolean(key, default)
            setPadding(dpv(16), dpv(16), dpv(16), dpv(16))
            val off = if (Ui.isLight(context)) 0xFFE4E4E6.toInt() else 0xFF3A3A3C.toInt()
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList.valueOf(if (isChecked) Ui.GREEN else off)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                trackTintList = ColorStateList.valueOf(if (on) Ui.GREEN else off)
                onChange?.invoke(on)
            }
        }

    private fun inputRow(
        label: String, key: String, hint: String,
        numeric: Boolean = false, decimal: Boolean = false, defaultText: String = "",
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpv(16), dpv(6), dpv(16), dpv(6))
        addView(TextView(this@SettingsActivity).apply {
            text = label; setTextColor(Ui.textc(context)); textSize = 16f; typeface = sf
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(EditText(this@SettingsActivity).apply {
            this.hint = hint
            setText(prefs.getString(key, defaultText))
            gravity = Gravity.END
            setTextColor(Ui.textc(context)); setHintTextColor(Ui.text2c(context)); textSize = 16f
            background = null
            inputType = when {
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            onChange { prefs.edit().putString(key, it).apply() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Best-effort ionizer command (anion generator); no readback on this car. */
    private fun sendIonizer(on: Boolean) {
        val app = applicationContext
        Thread {
            runCatching {
                val car = Autoservice(AdbShell(app))
                if (car.isReachable()) {
                    val v = if (on) 1 else 0
                    car.write(Fids.DEV_PM25, Fids.PM25_ANION_STATE_SET, v)
                    car.write(Fids.DEV_CLIMATE, Fids.PM25_ANION_STATE_SET, v)
                }
            }
        }.start()
    }

    private fun cameraPermissions() {
        val need = listOf(android.Manifest.permission.CAMERA)
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { requestPermissions(need.toTypedArray(), 1) }
        if (!Settings.canDrawOverlays(this)) runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun EditText.onChange(save: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = save(s?.toString()?.trim() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
}
