package ua.zaika.carremote

import android.content.SharedPreferences
import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Control
import ua.zaika.carremote.car.Controls
import ua.zaika.carremote.car.Fids
import ua.zaika.carremote.car.StatusReport
import ua.zaika.carremote.telegram.Button
import ua.zaika.carremote.telegram.Reply

/**
 * Turns a Telegram message or button tap into a reply. Everything here serves only the
 * owner's chat; anyone else is ignored. Control commands are looked up in the [Controls]
 * whitelist and confirmed by reading the car's state back after the write.
 */
class CommandRouter(
    private val car: Autoservice,
    private val ownerChatId: Long,
    private val prefs: SharedPreferences,
) {
    /** The main button grid, also used for the "car online" greeting. */
    fun menu(header: String) = Reply(header, MAIN_KEYBOARD)

    fun handle(chatId: Long, input: String): Reply? {
        if (ownerChatId == 0L) return Reply(
            "Бот ще не прив'язаний до власника.\nТвій chat id: $chatId\n" +
                "Впиши його в secrets.properties як OWNER_CHAT_ID і перевстанови застосунок.",
        )
        // Strangers get silence, not a hint that the bot exists.
        if (chatId != ownerChatId) return null

        val parts = input.trim().split(Regex("\\s+"))
        return when (parts[0].substringBefore('@').lowercase()) {
            "/start", "/help", "menu" -> menu(HELP)
            "/status", "status" -> Reply(status(), MAIN_KEYBOARD)
            "/ping", "ping" -> Reply("pong — авто на зв'язку", MAIN_KEYBOARD)
            "/probe" -> probe(parts.drop(1))
            "/setlow" -> setLow(parts.getOrNull(1))

            "temp_up" -> changeTemp(+1)
            "temp_down" -> changeTemp(-1)

            // Unlock asks first; everything else runs straight away.
            "unlock" -> Reply(
                "Відімкнути авто?",
                listOf(
                    listOf(Button("✅ Так, відімкнути", "unlock_do")),
                    listOf(Button("↩️ Назад", "menu")),
                ),
            )
            "unlock_do" -> run(Controls.UNLOCK)

            else -> Controls.byId[parts[0].lowercase()]?.let { run(it) }
                ?: Reply("Невідома команда. /help — список.", MAIN_KEYBOARD)
        }
    }

    private fun status(): String = if (car.isReachable()) StatusReport.build(car) else noAdb()

    private fun run(c: Control): Reply {
        if (!car.isReachable()) return Reply(noAdb(), MAIN_KEYBOARD)
        car.write(c.dev, c.fid, c.value)
        val verify = c.verify ?: return Reply("✅ Надіслано: ${c.label}", MAIN_KEYBOARD)
        Thread.sleep(SETTLE_MS) // let the command reach the bus before reading it back
        val got = car.getInt(verify.dev, verify.fid)
        val text = if (got == verify.expected) "✅ ${c.label} — виконано"
        else "⚠️ ${c.label}: надіслано, підтвердження немає (стан=$got). " +
            "Можливо, ця команда не підтримується на цьому авто."
        return Reply(text, MAIN_KEYBOARD)
    }

    private fun changeTemp(delta: Int): Reply {
        if (!car.isReachable()) return Reply(noAdb(), MAIN_KEYBOARD)
        val current = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
            ?: return Reply("Не вдалося прочитати температуру клімату.", MAIN_KEYBOARD)
        val target = (current + delta).coerceIn(Controls.TEMP_MIN, Controls.TEMP_MAX)
        if (target == current) return Reply("🌡 Вже $current °C (межа ${Controls.TEMP_MIN}–${Controls.TEMP_MAX}).", MAIN_KEYBOARD)
        car.write(Fids.DEV_CLIMATE, Controls.TEMP_FID, target)
        Thread.sleep(SETTLE_MS)
        val got = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
        return Reply(if (got == target) "🌡 Клімат: $target °C" else "🌡 Надіслано $target °C (стан $got)", MAIN_KEYBOARD)
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
        const val KEY_LOW_SOC = "low_soc"
        const val DEFAULT_LOW_SOC = 20

        val MAIN_KEYBOARD = listOf(
            listOf(Button("📊 Статус", "status"), Button("🏓 Пінг", "ping")),
            listOf(Button("❄️ Клімат ON", "climate_on"), Button("⭕ Клімат OFF", "climate_off")),
            listOf(Button("🌡 −", "temp_down"), Button("🌡 +", "temp_up")),
            listOf(Button("🔒 Замкнути", "lock"), Button("🔓 Відімкнути", "unlock")),
            listOf(Button("🌬 Обігрів скла", "defrost_on"), Button("⭕ Вимк.", "defrost_off")),
        )

        const val HELP = "Car Remote — обери дію кнопкою нижче.\n\n" +
            "Команди: /status, /ping, /setlow <%>, /probe <dev> <fid>"
    }
}
