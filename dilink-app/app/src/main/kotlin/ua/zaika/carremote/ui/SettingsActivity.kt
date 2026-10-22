package ua.zaika.carremote.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
        root.addView(pairCard())
        root.addView(note("Реклама вимикається назавжди після разової оплати за посиланням у банері або командою /noads у боті."))

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

    // --- Telegram pairing ---

    private val ui = Handler(Looper.getMainLooper())
    private var pairWatch: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onDestroy() {
        pairWatch?.let { prefs.unregisterOnSharedPreferenceChangeListener(it) }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Paired: a plain "Авто прив’язано" with a «Перепривʼязати» button (lost the bot chat, new Telegram).
     * Not paired / re-pairing: a visible code field. The code is handed to the hub only on the button,
     * the old pairing stays until the hub accepts the new code, and the result is shown right here.
     */
    private fun pairCard(): View {
        val paired = { !prefs.getString(HubBot.TOKEN, "").isNullOrBlank() }
        val status = TextView(this).apply { textSize = 16f; typeface = sfSemibold; setPadding(dpv(16), dpv(16), dpv(16), dpv(6)) }
        val hint = TextView(this).apply {
            text = "Візьміть код у @Varanbyd_bot: /start → «Поділитися номером». Код діє 10 хвилин."
            setTextColor(Ui.text2c(context)); textSize = 13f; typeface = sf; setPadding(dpv(16), 0, dpv(16), dpv(10))
        }
        val frame = GradientDrawable().apply { cornerRadius = dpf(12f); setColor(Ui.cardHi(this@SettingsActivity)) }
        val field = EditText(this).apply {
            this.hint = "000000"; gravity = Gravity.CENTER
            setTextColor(Ui.textc(context)); setHintTextColor(Ui.text2c(context))
            textSize = 26f; typeface = sfBold; letterSpacing = 0.35f
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(6))
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isCursorVisible = true; isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply { setColor(Ui.ORANGE); setSize(dpv(2), dpv(30)) }
            background = frame; setPadding(dpv(12), dpv(12), dpv(12), dpv(12))
            frame.setStroke(dpv(2), Ui.sepc(context))
            setOnFocusChangeListener { _, focused -> frame.setStroke(dpv(2), if (focused) Ui.ORANGE else Ui.sepc(this@SettingsActivity)) }
        }
        val fieldWrap = LinearLayout(this).apply { setPadding(dpv(16), dpv(4), dpv(16), dpv(4)); addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        val action = TextView(this).apply { setTextColor(Ui.ORANGE); textSize = 16f; typeface = sfSemibold; setPadding(dpv(16), dpv(14), dpv(16), dpv(16)) }
        var editing = false
        var pending = false

        fun hideKeyboard() {
            field.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
        }
        fun show(text: String, color: Int) { status.text = text; status.setTextColor(color) }
        fun render() {
            val form = !paired() || editing
            hint.visibility = if (form) View.VISIBLE else View.GONE
            fieldWrap.visibility = if (form) View.VISIBLE else View.GONE
            action.text = when { pending -> "Прив’язуємо…"; form -> "Прив’язати авто →"; else -> "Перепривʼязати →" }
            action.alpha = if (pending) 0.5f else 1f
            if (!pending && status.text.isNullOrEmpty()) {
                if (paired()) show("✅ Авто прив’язано до Telegram", Ui.GREEN) else show("Авто ще не прив’язане", Ui.text2c(this))
            }
        }
        fun finish(text: String, color: Int, ok: Boolean) {
            if (!pending) return
            pending = false; ui.removeCallbacksAndMessages(null)
            if (ok) { editing = false; field.setText("") }
            show(text, color); render()
        }
        fun submit() {
            if (pending) return
            val code = field.text.toString().filter(Char::isDigit)
            if (code.length != 6) {
                show(if (code.isEmpty()) "Введіть код із бота" else "Потрібно 6 цифр — зараз ${code.length}", Ui.RED)
                field.requestFocus(); return
            }
            hideKeyboard()
            pending = true; show("Прив’язуємо авто…", Ui.ORANGE); render()
            // The current pairing keeps working until the hub accepts the new code.
            prefs.edit().putBoolean(HubBot.ENABLED, true).putString(HubBot.CODE, code).apply()
            CarRemoteService.start(this)
            ui.postDelayed({ finish("Сервер не відповів. Перевірте інтернет і спробуйте ще раз.", Ui.RED, false); prefs.edit().remove(HubBot.CODE).apply() }, 40_000)
        }
        pairWatch = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (!pending) return@OnSharedPreferenceChangeListener
            ui.post {
                when {
                    key == HubBot.TOKEN && !p.getString(HubBot.TOKEN, "").isNullOrBlank() ->
                        finish("✅ Авто прив’язано. Підтвердження прийде в Telegram.", Ui.GREEN, true)
                    key == HubBot.CODE && p.getString(HubBot.CODE, "").isNullOrBlank() && p.getString(HubBot.PAIR_RESULT, "") == "failed" ->
                        finish("Код неправильний або прострочений. Візьміть новий у боті.", Ui.RED, false)
                }
            }
        }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
        action.setOnClickListener {
            if (paired() && !editing && !pending) { editing = true; status.text = ""; show("Введіть новий код із бота", Ui.textc(this)); render(); field.requestFocus() }
            else submit()
        }
        render()
        return card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(status, hint, fieldWrap, action).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
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
