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
            "/parked" -> parkedPreview()
            "/probe" -> probe(parts.drop(1))
            "/setlow" -> setLow(parts.getOrNull(1))

            "temp_up" -> changeTemp(+1)
            "temp_down" -> changeTemp(-1)

            "seat_driver" -> toggleSeat(
                "водія", Fids.SEAT_HEAT_DRIVER_SWITCH, Fids.SEAT_HEAT_DRIVER_LEVEL, Fids.SEAT_HEAT_DRIVER_READ,
            )
            "seat_pass" -> toggleSeat(
                "пасажира", Fids.SEAT_HEAT_PASS_SWITCH, Fids.SEAT_HEAT_PASS_LEVEL, Fids.SEAT_HEAT_PASS_READ,
            )
            "findcar" -> findCar()

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

            else -> Controls.byId[token]?.let { run(it) }
                ?: Reply("Невідома команда. /help — список.")
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
        car.write(c.dev, c.fid, c.value)
        val verify = c.verify ?: return Reply("✅ Надіслано: ${c.label}")
        Thread.sleep(SETTLE_MS) // let the command reach the bus before reading it back
        val got = car.getInt(verify.dev, verify.fid)
        return Reply(
            if (got == verify.expected) "✅ ${c.label} — виконано"
            else "⚠️ ${c.label}: надіслано, підтвердження немає (стан=$got). " +
                "Можливо, ця команда не підтримується на цьому авто.",
        )
    }

    /** Seat heating is two writes to turn on (switch + level); its status fid confirms it. */
    private fun toggleSeat(who: String, switchFid: Int, levelFid: Int, readFid: Int): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        val level = car.getInt(Fids.DEV_CLIMATE, readFid)
            ?: return Reply("Не вдалося прочитати стан сидіння $who.")
        if (level > 0) {
            car.write(Fids.DEV_CLIMATE, switchFid, 2) // off
            Thread.sleep(SETTLE_MS)
            return Reply("🪑 Підігрів $who: вимкнено")
        }
        car.write(Fids.DEV_CLIMATE, switchFid, 1) // on
        car.write(Fids.DEV_CLIMATE, levelFid, SEAT_ON_LEVEL)
        Thread.sleep(SETTLE_MS)
        val now = car.getInt(Fids.DEV_CLIMATE, readFid)
        return Reply(
            if (now != null && now > 0) "🔥 Підігрів $who: увімкнено (рівень $now)"
            else "⚠️ Підігрів $who: надіслано, підтвердження немає (стан=$now).",
        )
    }

    /** "Find car": flash the hazards for a few seconds. */
    private fun findCar(): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        car.write(Fids.DEV_LIGHTS, Fids.HAZARD, 1)
        Thread.sleep(3000)
        car.write(Fids.DEV_LIGHTS, Fids.HAZARD, 0)
        return Reply("📍 Поблимав аварійкою")
    }

    private fun changeTemp(delta: Int): Reply {
        if (!car.isReachable()) return Reply(noAdb())
        val current = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
            ?: return Reply("Не вдалося прочитати температуру клімату.")
        val target = (current + delta).coerceIn(Controls.TEMP_MIN, Controls.TEMP_MAX)
        if (target == current) return Reply("🌡 Вже $current °C (межа ${Controls.TEMP_MIN}–${Controls.TEMP_MAX}).")
        car.write(Fids.DEV_CLIMATE, Controls.TEMP_FID, target)
        Thread.sleep(SETTLE_MS)
        val got = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
        return Reply(if (got == target) "🌡 Клімат: $target °C" else "🌡 Надіслано $target °C (стан $got)")
    }

    private fun setLow(arg: String?): Reply {
        val n = arg?.toIntOrNull()
        if (n == null || n !in 5..90) return Reply("Формат: /setlow <5–90>. Зараз: ${lowSoc()}%.")
        prefs.edit().putInt(KEY_LOW_SOC, n).apply()
        return Reply("Поріг низького заряду: $n%.")
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
        const val SEAT_ON_LEVEL = 3 // mid heat when turning a seat on
        const val KEY_LOW_SOC = "low_soc"
        const val DEFAULT_LOW_SOC = 20

        // Single source for the persistent keyboard and the label → action lookup.
        val BUTTONS: List<List<Pair<String, String>>> = listOf(
            listOf("📊 Статус" to "status", "🏓 Пінг" to "ping"),
            listOf("❄️ Клімат ON" to "climate_on", "⭕ Клімат OFF" to "climate_off"),
            listOf("🌡 −" to "temp_down", "🌡 +" to "temp_up"),
            listOf("🔒 Замкнути" to "lock", "🔓 Відімкнути" to "unlock"),
            listOf("🌬 Обігрів скла" to "defrost_on", "❄️ Обігрів вимк." to "defrost_off"),
            listOf("🔥 Сид. водія" to "seat_driver", "🔥 Сид. пасажира" to "seat_pass"),
            listOf("📍 Знайти авто" to "findcar"),
        )
        val MAIN_LABELS: List<List<String>> = BUTTONS.map { row -> row.map { it.first } }
        val BUTTON_ID: Map<String, String> = BUTTONS.flatten().associate { it.first to it.second }

        const val HELP = "Car Remote — кнопки відкриваються квадратиком біля поля вводу.\n\n" +
            "Команди: /status, /ping, /parked, /setlow <%>, /probe <dev> <fid>"
    }
}
