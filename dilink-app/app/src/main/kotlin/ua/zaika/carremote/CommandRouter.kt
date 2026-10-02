package ua.zaika.carremote

import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Fids

/** Turns a Telegram message into a reply. Car data only ever goes to the owner's chat. */
class CommandRouter(private val car: Autoservice, private val ownerChatId: Long) {

    fun handle(chatId: Long, text: String): String? {
        if (ownerChatId == 0L) {
            return "Бот ще не прив'язаний до власника.\n" +
                "Твій chat id: $chatId\n" +
                "Впиши його в secrets.properties як OWNER_CHAT_ID і перевстанови застосунок."
        }
        // Strangers get silence, not an error message that confirms the bot is alive.
        if (chatId != ownerChatId) return null

        val args = text.trim().split(Regex("\\s+"))
        return when (args[0].substringBefore('@').lowercase()) {
            "/start", "/help" -> HELP
            "/ping" -> "pong — авто на зв'язку"
            "/status" -> status()
            "/probe" -> probe(args.drop(1))
            else -> "Невідома команда. /help — список."
        }
    }

    private fun status(): String {
        if (!car.isReachable()) return noAdb()
        val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)
        val soh = car.getInt(Fids.DEV_STATISTIC, Fids.SOH)
        val battery12v = car.getFloat(Fids.DEV_BODYWORK, Fids.BATTERY_12V)
        val power = car.getInt(Fids.DEV_ENGINE, Fids.ENGINE_POWER)
        val mileage = car.getInt(Fids.DEV_STATISTIC, Fids.LIFETIME_MILEAGE)
        val gun = car.getInt(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE)
        val bms = car.getInt(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE)
        return buildString {
            appendLine("🔋 Заряд: ${soc.show("%.1f %%")}")
            appendLine("❤️ Здоров'я батареї: ${soh.show("%d %%")}")
            appendLine("🔌 Зарядка: ${charging(gun, bms)}")
            appendLine("🪫 12V: ${battery12v.show("%.2f В")}")
            appendLine("⚡ Потужність: ${power.show("%d кВт")}")
            append("🛣 Пробіг: ${mileage?.let { it / 10f }.show("%.1f км")}")
        }
    }

    /** Reads one address both ways, for finding which feature ids exist on this car. */
    private fun probe(args: List<String>): String {
        val dev = args.getOrNull(0)?.toIntOrNull()
        val fid = args.getOrNull(1)?.toIntOrNull()
        if (dev == null || fid == null) {
            return "Формат: /probe <device> <fid>\nНаприклад: /probe ${Fids.DEV_STATISTIC} ${Fids.SOC}"
        }
        if (!car.isReachable()) return noAdb()
        val asInt = car.readRaw(Autoservice.TX_GET_INT, dev, fid)
        val asFloat = car.readRaw(Autoservice.TX_GET_FLOAT, dev, fid)?.let { java.lang.Float.intBitsToFloat(it) }
        return buildString {
            appendLine("dev=$dev fid=$fid")
            appendLine("int:   " + (asInt?.let { "$it (0x%08x)".format(it) + noData(Autoservice.decodeInt(it)) } ?: "виклик не вдався"))
            append("float: " + (asFloat?.let { "$it" + noData(Autoservice.decodeFloat(it)) } ?: "виклик не вдався"))
        }
    }

    private fun charging(gun: Int?, bms: Int?): String {
        val plug = when (gun) {
            null -> return "—"
            1 -> return "кабель не підключено"
            2 -> "AC"
            3 -> "DC"
            4 -> "AC+DC"
            5 -> "V2L"
            else -> "код $gun"
        }
        val state = when (bms) {
            1 -> "триває"
            2 -> "завершено"
            13 -> "пауза"
            else -> null
        }
        return if (state == null) plug else "$plug, $state"
    }

    private fun noAdb() =
        "Немає зв'язку з ADB на екрані авто (${car.lastError ?: "невідома причина"}).\n" +
            "Перевір, що бездротове налагодження увімкнене і ключ «Car Remote» дозволено."

    private fun noData(decoded: Any?) = if (decoded == null) " — немає даних" else ""

    private fun Number?.show(pattern: String) = this?.let { pattern.format(it) } ?: "—"

    private companion object {
        const val HELP = "Car Remote — команди:\n" +
            "/status — заряд, 12V, зарядка, пробіг\n" +
            "/ping — чи авто на зв'язку\n" +
            "/probe <device> <fid> — прочитати будь-яку адресу\n" +
            "/help — цей список"
    }
}
