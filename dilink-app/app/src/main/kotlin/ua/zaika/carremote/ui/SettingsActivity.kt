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
import android.widget.SeekBar
import android.widget.TextView
import ua.zaika.carremote.CarRemoteService
import ua.zaika.carremote.adb.AdbShell
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.telegram.HubBot

/** App settings in the factory DiLink layout: sections on the left, the chosen section on the right. */
class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("carremote", Context.MODE_PRIVATE) }

    private lateinit var content: LinearLayout
    private val navItems = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Factory DiLink Settings layout: a navy list of sections on the left, the chosen one on the right.
        val sections: List<Pair<String, () -> List<View>>> = listOf(
            "Система" to { listOf(
                header("Система"), card(
                    switchRow("Доступність на стоянці", "keep_awake", false),
                    switchRow("Нагадування: відчинене авто / перегрів", CarRemoteService.KEY_AUTOMATIONS, true),
                    switchRow("Плаваючий віджет (поверх усього)", CarRemoteService.KEY_WIDGET, false) {
                        if (it && !Settings.canDrawOverlays(this)) runCatching {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    },
                ),
                header("Камери"), card(
                    switchRow("Камера при повороті (сліпа зона)", CarRemoteService.KEY_CAM_TURN, false) { if (it) cameraPermissions() },
                    switchRow("Камера при відкритті дверей", CarRemoteService.KEY_CAM_DOOR, false) { if (it) cameraPermissions() },
                    inputRow("Поворот — від швидкості, км/год", CarRemoteService.KEY_CAM_MIN_SPEED, "15", numeric = true),
                ), note("Камери показуються на планшеті. Потрібен дозвіл «Поверх інших застосунків»."),
                header("Повітря"), card(
                    switchRow("Іонізатор повітря", "ionizer", false) { sendIonizer(it) },
                ), note("Іонізатор працює лише на версіях DiLink, де він підтримується апаратно. " +
                    "На цьому авто стан не зчитується — команда надсилається без підтвердження."),
            ) },
            "Оновлення" to { listOf(header("Оновлення застосунку"), updateCard()) },
            "Тарифи" to { listOf(header("Тарифи зарядки"), card(
                inputRow("Дім, за кВт·год", CarRemoteService.KEY_TARIFF, "напр. 4.32", decimal = true),
                inputRow("Швидка 1, за кВт·год", CarRemoteService.KEY_TARIFF_DC1, "напр. 18", decimal = true),
                inputRow("Швидка 2, за кВт·год", CarRemoteService.KEY_TARIFF_DC2, "напр. 25", decimal = true),
                inputRow("Валюта", CarRemoteService.KEY_CURRENCY, "грн", numeric = false, defaultText = "грн"),
            ), note("Витрати на екрані «Заряджання» = кВт·год × тариф.")) },
            "Голос тривоги" to { listOf(
                header("Голос і гучність"), card(switchRow("Говорити голосом", CarRemoteService.KEY_VOICE, true), volumeRow()),
                note("Український голос вбудований у застосунок і працює без інтернету. " +
                    "Гучність тривоги окрема від гучності авто: чутно, але не лякає."),
            ) },
            "Зв'язок" to { listOf(
                header("Telegram / Varan Hub"), pairCard(),
                note("Реклама вимикається назавжди після разової оплати за посиланням у банері або командою /noads у боті."),
            ) },
        )

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(28), dpv(18), dpv(36), dpv(28))
        }
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(18), dpv(18), dpv(18), dpv(18))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "‹"; setTextColor(Ui.textc(context)); textSize = 34f; typeface = sf; gravity = Gravity.CENTER
                    background = tileBg(radius = 22); contentDescription = "Назад"
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(dpv(48), dpv(48)).apply { setMargins(0, 0, dpv(14), 0) })
                addView(TextView(context).apply {
                    text = "Налаштування"; setTextColor(Ui.textc(context)); textSize = 22f; typeface = sfSemibold
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dpv(18)) })
            sections.forEachIndexed { i, (title, build) ->
                val item = TextView(context).apply {
                    text = title; textSize = 19f; typeface = sf; setTextColor(Ui.textc(context))
                    setPadding(dpv(20), dpv(14), dpv(20), dpv(14))
                    setOnClickListener { select(i, build) }
                }
                navItems += item
                addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dpv(2), 0, dpv(2)) })
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.bg(this@SettingsActivity))
            addView(ScrollView(context).apply { addView(nav) }, LinearLayout.LayoutParams(dpv(330), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        })
        // Not paired yet: open straight on the pairing section, which now sits at the bottom of the list.
        val first = if (prefs.getString(HubBot.TOKEN, "").isNullOrBlank()) sections.lastIndex else 0
        select(first, sections[first].second)
    }

    private fun select(index: Int, build: () -> List<View>) {
        navItems.forEachIndexed { i, v ->
            v.background = if (i == index) rounded(if (Ui.isLight(this)) 0xFFDDE3EC.toInt() else 0xFF2C3646.toInt(), 26) else null
        }
        content.removeAllViews()
        build().forEach { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    // --- Spoken alerts: built-in Ukrainian voice ---

    /** Alarm loudness (beep and voice), with a button to hear it. */
    private fun volumeRow(): View {
        val current = prefs.getInt(CarRemoteService.KEY_ALARM_VOLUME, CarRemoteService.DEFAULT_ALARM_VOLUME)
        val label = TextView(this).apply {
            setTextColor(Ui.textc(context)); textSize = 17f; typeface = sf
            text = "Гучність тривоги: $current%"
        }
        val slider = SeekBar(this).apply {
            max = 90 // 10..100 %
            progress = current - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { label.text = "Гучність тривоги: ${p + 10}%" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    prefs.edit().putInt(CarRemoteService.KEY_ALARM_VOLUME, sb.progress + 10).apply()
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpv(4), dpv(10), dpv(4), dpv(10))
            addView(label)
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpv(48)))
            addView(tintedPill("Перевірити голос", Ui.textc(context)) { CarRemoteService.testAlarm(this@SettingsActivity) })
        }
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
        val action = TextView(this).apply { setTextColor(Ui.BLUE); textSize = 18f; typeface = sfSemibold; setPadding(dpv(16), dpv(14), dpv(16), dpv(16)) }
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
            action.text = when { pending -> "Прив’язуємо…"; form -> "Прив’язати авто →"; else -> "Прив’язати до іншого Telegram" }
            action.alpha = if (pending) 0.5f else 1f
            // Already paired: re-pairing is a rare need, so it is a quiet grey line, not a call to action.
            action.setTextColor(if (form || pending) Ui.BLUE else Ui.text2c(this@SettingsActivity))
            action.textSize = if (form || pending) 18f else 15f
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

    // --- App updates (released from the varan.team admin) ---

    private fun updateCard(): View {
        val status = TextView(this).apply { textSize = 16f; typeface = sfSemibold; setPadding(dpv(16), dpv(16), dpv(16), dpv(4)) }
        val notes = TextView(this).apply { setTextColor(Ui.text2c(context)); textSize = 13f; typeface = sf; setPadding(dpv(16), dpv(2), dpv(16), dpv(6)) }
        val action = TextView(this).apply { setTextColor(Ui.BLUE); textSize = 18f; typeface = sfSemibold; setPadding(dpv(16), dpv(12), dpv(16), dpv(16)) }
        var busy = false
        fun render(msg: String? = null, color: Int = Ui.textc(this)) {
            val rel = ua.zaika.carremote.update.Updater.available(prefs)
            if (msg != null) { status.text = msg; status.setTextColor(color) }
            else if (rel != null) { status.text = "Доступна версія ${rel.name}"; status.setTextColor(Ui.ORANGE) }
            else { status.text = "Версія ${ua.zaika.carremote.BuildConfig.VERSION_NAME} · встановлено останню"; status.setTextColor(Ui.GREEN) }
            notes.text = rel?.notes.orEmpty(); notes.visibility = if (rel != null && rel.notes.isNotBlank()) View.VISIBLE else View.GONE
            action.text = when { busy -> "Зачекайте…"; rel != null -> "Оновити до ${rel.name} →"; else -> "Перевірити оновлення" }
            action.alpha = if (busy) 0.5f else 1f
        }
        fun work(job: () -> Unit) {
            if (busy) return
            busy = true; render(status.text.toString(), status.currentTextColor)
            Thread {
                try { job() }
                catch (e: Exception) { runOnUiThread { busy = false; render(e.message ?: "Не вдалося — перевірте інтернет", Ui.RED) } }
            }.start()
        }
        action.setOnClickListener {
            val rel = ua.zaika.carremote.update.Updater.available(prefs)
            if (rel == null) work {
                val found = ua.zaika.carremote.update.Updater.check(prefs)
                runOnUiThread { busy = false; if (found == null) render("Версія ${ua.zaika.carremote.BuildConfig.VERSION_NAME} · встановлено останню", Ui.GREEN) else render() }
            } else work {
                // Ask the hub again first: the remembered release may have been replaced (and its file removed) since.
                val newest = runCatching { ua.zaika.carremote.update.Updater.check(prefs) }.getOrElse { rel }
                if (newest == null) { runOnUiThread { busy = false; render("Версія ${ua.zaika.carremote.BuildConfig.VERSION_NAME} · встановлено останню", Ui.GREEN) }; return@work }
                ua.zaika.carremote.update.Updater.install(applicationContext, newest) { p -> runOnUiThread { render(p, Ui.ORANGE) } }
            }
        }
        render()
        // Opening the screen refreshes what's available, so it never offers a release that's gone.
        Thread { if (runCatching { ua.zaika.carremote.update.Updater.check(prefs) }.isSuccess) runOnUiThread { if (!busy) render() } }.start()
        return card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(status, notes, action).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        })
    }

    // --- grouped-list builders ---

    private fun header(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Ui.text2c(context)); textSize = 17f; typeface = sf
        setPadding(dpv(4), dpv(14), dpv(4), dpv(8))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Ui.text2c(context)); textSize = 14f; typeface = sf
        setPadding(dpv(4), dpv(10), dpv(4), 0)
    }

    private fun card(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, row ->
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (i < rows.size - 1) addView(View(this@SettingsActivity).apply {
                setBackgroundColor(Ui.sepc(this@SettingsActivity))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun switchRow(label: String, key: String, default: Boolean, onChange: ((Boolean) -> Unit)? = null) =
        Switch(this).apply {
            text = label; setTextColor(Ui.textc(context)); textSize = 19f; typeface = sf
            isChecked = prefs.getBoolean(key, default)
            setPadding(dpv(4), dpv(20), dpv(4), dpv(20))
            val off = if (Ui.isLight(context)) 0xFFCDD3DC.toInt() else 0xFF3A4556.toInt()
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList.valueOf(if (isChecked) Ui.BLUE else off)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(key, on).apply()
                trackTintList = ColorStateList.valueOf(if (on) Ui.BLUE else off)
                onChange?.invoke(on)
            }
        }

    private fun inputRow(
        label: String, key: String, hint: String,
        numeric: Boolean = false, decimal: Boolean = false, defaultText: String = "",
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpv(4), dpv(10), dpv(4), dpv(10))
        addView(TextView(this@SettingsActivity).apply {
            text = label; setTextColor(Ui.textc(context)); textSize = 19f; typeface = sf
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(EditText(this@SettingsActivity).apply {
            this.hint = hint
            setText(prefs.getString(key, defaultText))
            gravity = Gravity.END
            setTextColor(Ui.textc(context)); setHintTextColor(Ui.text2c(context)); textSize = 19f
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
