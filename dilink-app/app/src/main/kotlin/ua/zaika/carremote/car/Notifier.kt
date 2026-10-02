package ua.zaika.carremote.car

/**
 * Watches a few values each poll and returns a message whenever one crosses a line worth
 * telling the owner about: charging started/finished, cable in/out, low charge, low 12V.
 * Stateful on purpose — it only speaks on a change, never every poll.
 */
class Notifier(private val car: Autoservice, private val lowSocPct: () -> Int) {

    private var gun: Int? = null
    private var bms: Int? = null
    private var lowSocSpoken = false
    private var low12vSpoken = false

    fun poll(): List<String> {
        val msgs = mutableListOf<String>()

        val newGun = car.getInt(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE)
        if (newGun != null && gun != null && newGun != gun) {
            if (gun == 1 && newGun != 1) msgs += "🔌 Кабель підключено"
            if (gun != 1 && newGun == 1) msgs += "🔌 Кабель від'єднано"
        }
        if (newGun != null) gun = newGun

        val newBms = car.getInt(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE)
        if (newBms != null && newBms != bms) {
            val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)?.let { " (заряд ${it.toInt()}%)" } ?: ""
            when (newBms) {
                1 -> if (bms != 1) msgs += "⚡ Зарядка почалась$soc"
                2 -> if (bms == 1 || bms == 13) msgs += "✅ Зарядка завершена$soc"
                13 -> if (bms == 1) msgs += "⏸ Зарядка на паузі$soc"
            }
            bms = newBms
        }

        car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)?.let { soc ->
            val limit = lowSocPct()
            if (soc < limit && !lowSocSpoken) { msgs += "🪫 Заряд нижче $limit% (${soc.toInt()}%)"; lowSocSpoken = true }
            if (soc >= limit + 2) lowSocSpoken = false
        }

        car.getFloat(Fids.DEV_BODYWORK, Fids.BATTERY_12V)?.let { v ->
            if (v < 11.8f && !low12vSpoken) { msgs += "⚠️ Напруга 12V низька: %.1f В".format(v); low12vSpoken = true }
            if (v >= 12.2f) low12vSpoken = false
        }

        return msgs
    }
}
