package ua.zaika.carremote

import android.content.SharedPreferences
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.CarSnapshot
import ua.zaika.carremote.car.Control
import ua.zaika.carremote.car.Controls
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.car.ShutdownReport
import ua.zaika.carremote.car.StatusReport
import ua.zaika.carremote.telegram.Button
import ua.zaika.carremote.telegram.InlineKeyboard
import ua.zaika.carremote.telegram.Reply
import ua.zaika.carremote.telegram.ReplyKeyboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a Telegram message or button tap into a reply. Everything here serves only the
 * owner's chat; anyone else is ignored. The main controls live on a persistent reply
 * keyboard (sent once, toggled by the grid icon), so a tap arrives as its label text —
 * [BUTTON_ID] maps that back to an action. Control writes come from the [Controls] whitelist
 * and are confirmed by reading the car's state back.
 */
class CommandRouter(
    private val car: Autoservice,
    private val ownerChatId: Long,
    private val prefs: SharedPreferences,
) {
    /** Greeting that also (re)shows the persistent keyboard. */
    fun menu(header: String) = Reply(header, ReplyKeyboard(MAIN_LABELS))

    fun handle(chatId: Long, input: String): Reply? {
        if (ownerChatId == 0L) return Reply(
            "Бот ще не прив'язаний до власника.\nТвій chat id: $chatId\n" +
                "Впиши його в secrets.properties як OWNER_CHAT_ID і перевстанови застосунок.",
        )
        // Strangers get silence, not a hint that the bot exists.
        if (chatId != ownerChatId) return null

        val text = input.trim()
        val parts = text.split(Regex("\\s+"))
        // A reply-keyboard tap arrives as the label; everything else is a typed/slash command.
        val token = (BUTTON_ID[text] ?: parts[0]).substringBefore('@').lowercase()
        return when (token) {
            "/start", "/help", "menu" -> menu(HELP)
            "/status", "status" -> Reply(status())
            "/ping", "ping" -> Reply("pong — авто на зв'язку")
            "/parked", "parked" -> parkedPreview()
            "/probe" -> probe(parts.drop(1))
            "/setlow" -> Reply(setLow(parts.getOrNull(1)?.toIntOrNull()))
            "setlow_menu" -> Reply(
                "Поріг низького заряду: ${lowSoc()}%. Обери новий:",
                InlineKeyboard(listOf(listOf(15, 20, 25, 30).map { Button("$it%", "setlow_$it") })),
            )

            "temp_up" -> changeTemp(+1)
            "temp_down" -> changeTemp(-1)

            "findcar" -> findCar()
            "windows_open" -> windows(open = true)
            "windows_close" -> windows(open = false)

            // Unlock asks first, with a one-off inline confirm button.
            "unlock" -> Reply(
                "Відімкнути авто?",
                InlineKeyboard(
                    listOf(
                        listOf(Button("✅ Так, відімкнути", "unlock_do")),
                        listOf(Button("↩️ Скасувати", "menu")),
                    ),
                ),
            )
            "unlock_do" -> run(Controls.UNLOCK)

            else -> when {
                token.startsWith("setlow_") -> Reply(setLow(token.removePrefix("setlow_").toIntOrNull()))
                else -> Controls.byId[token]?.let { run(it) } ?: Reply("Невідома команда. /help — список.")
            }
        }
    }

    private fun status(): String = if (car.isReachable()) StatusReport.build(car) else noAdb()

    /** Preview of the message that is sent automatically when the car is switched off. */
    private fun parkedPreview(): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        val snap = CarSnapshot.read(car) ?: return Reply("Немає даних з авто.")
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return Reply(ShutdownReport.build(snap, time))
    }

    private fun run(c: Control): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        if (c.needsPark && !isParked()) return Reply("⚠️ ${c.label}: лише коли авто стоїть (передача P).")
        if (!car.write(c.dev, c.fid, c.value)) return rejected(c.label)
        val verify = c.verify ?: return Reply("✅ Надіслано: ${c.label}")
        Thread.sleep(SETTLE_MS) // let the command reach the bus before reading it back
        val got = car.getInt(verify.dev, verify.fid)
        return Reply(
            if (got == verify.expected) "✅ ${c.label} — виконано"
            else "⚠️ ${c.label}: надіслано, підтвердження немає (стан=$got). " +
                "Можливо, ця команда не підтримується на цьому авто.",
        )
    }

    private fun rejected(label: String) = Reply("⚠️ $label: авто відхилило команду або немає відповіді.")

    private fun isParked(): Boolean = car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1

    /** Opens or closes all four windows (gated to Park), verified via the window %. */
    private fun windows(open: Boolean): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        if (!isParked()) return Reply("⚠️ Вікна: лише коли авто стоїть (передача P).")
        val target = if (open) 100 else 0
        var accepted = true
        Fids.WINDOW_SET.forEachIndexed { index, fid ->
            if (index > 0) Thread.sleep(250)
            if (!car.write(Fids.DEV_BODYWORK, fid, target)) accepted = false
        }
        Thread.sleep(SETTLE_MS)
        val now = Fids.WINDOWS.joinToString(" ") { car.getInt(Fids.DEV_BODYWORK, it)?.let { p -> "$p%" } ?: "?" }
        return Reply("${if (accepted) "➡️" else "⚠️"} Вікна: команда ${if (open) "відкрити" else "закрити"} ${if (accepted) "надіслана" else "частково відхилена"}. Стан: $now")
    }

    /** "Find car": flash the hazards for a few seconds. */
    private fun findCar(): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        if (!isParked()) return Reply("⚠️ Пошук авто: лише на передачі P.")
        if (!car.write(Fids.DEV_LIGHTS, Fids.HAZARD, 1)) return rejected("Аварійка")
        Thread.sleep(3000)
        if (!car.write(Fids.DEV_LIGHTS, Fids.HAZARD, 0))
            return Reply("⚠️ Не вдалося вимкнути аварійку після пошуку авто. Перевір її вручну.")
        return Reply("📍 Команди блимання надіслані")
    }

    private fun changeTemp(delta: Int): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        val current = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
            ?: return Reply("Не вдалося прочитати температуру клімату.")
        val target = (current + delta).coerceIn(Controls.TEMP_MIN, Controls.TEMP_MAX)
        if (target == current) return Reply("🌡 Вже $current °C (межа ${Controls.TEMP_MIN}–${Controls.TEMP_MAX}).")
        if (!car.write(Fids.DEV_CLIMATE, Controls.TEMP_FID, target)) return rejected("Температура клімату")
        Thread.sleep(SETTLE_MS)
        val got = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
        return Reply(if (got == target) "🌡 Клімат: $target °C" else "🌡 Надіслано $target °C (стан $got)")
    }

    private fun setLow(n: Int?): String {
        if (n == null || n !in 5..90) return "Формат: /setlow <5–90>. Зараз: ${lowSoc()}%."
        prefs.edit().putInt(KEY_LOW_SOC, n).apply()
        return "Поріг низького заряду: $n%."
    }

    fun lowSoc(): Int = prefs.getInt(KEY_LOW_SOC, DEFAULT_LOW_SOC)

    private fun probe(args: List<String>): Reply {
        val dev = args.getOrNull(0)?.toIntOrNull()
        val fid = args.getOrNull(1)?.toIntOrNull()
        if (dev == null || fid == null) return Reply("Формат: /probe <device> <fid>")
        if (!car.isReachable()) return Reply(noAdb())
        val asInt = car.readRaw(Autoservice.TX_GET_INT, dev, fid)
        val asFloat = car.readRaw(Autoservice.TX_GET_FLOAT, dev, fid)?.let { java.lang.Float.intBitsToFloat(it) }
        return Reply(
            "dev=$dev fid=$fid\n" +
                "int:   ${asInt?.let { "$it (0x%08x)".format(it) } ?: "—"}\n" +
                "float: ${asFloat ?: "—"}",
        )
    }

    private fun noAdb() =
        "Немає зв'язку з ADB на екрані (${car.lastError ?: "невідомо"}). " +
            "Перевір бездротове налагодження і дозвіл ключа «Car Remote»."

    private companion object {
        const val SETTLE_MS = 1500L
        const val KEY_LOW_SOC = "low_soc"
        const val DEFAULT_LOW_SOC = 20

        // Single source for the persistent keyboard and the label → action lookup.
        val BUTTONS: List<List<Pair<String, String>>> = listOf(
            listOf("📊 Статус" to "status", "🏓 Пінг" to "ping"),
            listOf("❄️ Клімат ON" to "climate_on", "⭕ Клімат OFF" to "climate_off"),
            listOf("🌡 −" to "temp_down", "🌡 +" to "temp_up"),
            listOf("🔒 Замкнути" to "lock", "🔓 Відімкнути" to "unlock"),
            listOf("🌬 Обігрів скла" to "defrost_on", "❄️ Обігрів вимк." to "defrost_off"),
            listOf("🪟 Вікна відкр." to "windows_open", "🪟 Вікна закр." to "windows_close"),
            listOf("☀️ Люк відкр." to "sunroof_open", "☀️ Люк закр." to "sunroof_close"),
            listOf("🧳 Багажник відкр." to "trunk_open", "🧳 Багажник закр." to "trunk_close"),
            listOf("🌬 Очистити повітря" to "clean_air"),
            listOf("📍 Знайти авто" to "findcar"),
            listOf("🅿️ Стан на стоянці" to "parked", "🪫 Поріг заряду" to "setlow_menu"),
        )
        val MAIN_LABELS: List<List<String>> = BUTTONS.map { row -> row.map { it.first } }
        val BUTTON_ID: Map<String, String> = BUTTONS.flatten().associate { it.first to it.second }

        const val HELP = "Car Remote — кнопки відкриваються квадратиком біля поля вводу.\n\n" +
            "Команди: /status, /ping, /parked, /setlow <%>, /probe <dev> <fid>"
    }
}
