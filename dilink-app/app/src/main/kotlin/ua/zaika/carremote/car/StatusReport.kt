package ua.zaika.carremote.car

import kotlin.math.abs

/** Builds the multi-line /status text. Lines for data this car does not expose are left out. */
object StatusReport {

    fun build(car: Autoservice): String {
        val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)
        val soh = car.getInt(Fids.DEV_STATISTIC, Fids.SOH)
        val power = Power.kw(car.getInt(Fids.DEV_CHARGING, Fids.HV_VOLTAGE), car.getFloat(Fids.DEV_CHARGING, Fids.HV_CURRENT))
            ?: car.getInt(Fids.DEV_ENGINE, Fids.ENGINE_POWER)?.toFloat()
        val v12 = car.getFloat(Fids.DEV_BODYWORK, Fids.BATTERY_12V)
        val mileage = car.getInt(Fids.DEV_STATISTIC, Fids.LIFETIME_MILEAGE)?.let { it / 10f }
        val gun = car.getInt(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE)
        val bms = car.getInt(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE)
        val acOn = car.getInt(Fids.DEV_CLIMATE, Fids.AC_STATUS)
        val acTemp = car.getInt(Fids.DEV_CLIMATE, Fids.AC_TEMP)
        val inside = car.getInt(Fids.DEV_CLIMATE, Fids.INSIDE_TEMP)
        val outside = car.getInt(Fids.DEV_CLIMATE, Fids.OUTSIDE_TEMP)
        val pm25 = car.getInt(Fids.DEV_PM25, Fids.PM25_VALUE_IN)
        val locked = car.getInt(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER)
        val gear = car.getInt(Fids.DEV_GEAR, Fids.GEAR)
        val doorsOpen = Fids.DOORS.count { car.getInt(Fids.DEV_BODYWORK, it) == 1 }
        val tires = Fids.TIRE_PRESSURE.map { car.getInt(Fids.DEV_TIRES, it) }

        return buildString {
            appendLine("🔋 Заряд: ${soc.fmt("%.1f%%")} · батарея ${soh.fmt("%d%%")}")
            appendLine("🔌 Зарядка: ${charging(gun, bms, power)}")
            power?.let { appendLine("⚡ Потужність: ${"%.1f".format(it)} кВт") }
            appendLine("🪫 12V: ${v12.fmt("%.1f В")}")
            tempsLine(inside, outside)?.let { appendLine("🌡 $it") }
            acOn?.let { appendLine("❄️ Клімат: ${if (it == 1) "увімкнено" else "вимкнено"}${acTemp?.let { t -> ", $t °C" } ?: ""}") }
            pm25?.let { appendLine("🌬 Повітря (PM2.5): $it µg/m³ ${when { it <= 12 -> "— чисто"; it <= 35 -> "— помірно"; it <= 55 -> "— погано"; else -> "— дуже погано" }}") }
            locked?.let { appendLine(if (it == 2) "🔒 Зачинено" else "🔓 Відчинено") }
            gear?.let { appendLine("⚙️ Передача: ${gearName(it)}") }
            if (doorsOpen > 0) appendLine("🚪 Відчинених дверей: $doorsOpen")
            tiresLine(tires)?.let { appendLine("🛞 Шини: $it бар") }
            append("🛣 Пробіг: ${mileage.fmt("%.0f км")}")
        }
    }

    private fun charging(gun: Int?, bms: Int?, powerKw: Float?): String {
        val plug = when (gun) {
            null -> return "—"
            1 -> return "кабель не підключено"
            2 -> "AC"; 3 -> "DC"; 4 -> "AC+DC"; 5 -> "V2L"
            else -> "код $gun"
        }
        val state = when (bms) { 1 -> "триває"; 2 -> "завершено"; 13 -> "пауза"; else -> null }
        val base = if (state == null) plug else "$plug, $state"
        // Show live power while actually charging.
        return if (bms == 1 && powerKw != null) "$base · %.1f кВт".format(abs(powerKw)) else base
    }

    private fun tempsLine(inside: Int?, outside: Int?): String? = listOfNotNull(
        inside?.let { "салон $it °C" },
        outside?.let { "надворі $it °C" },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun tiresLine(tires: List<Int?>): String? {
        if (tires.all { it == null }) return null
        return tires.joinToString(" · ") { kpa -> kpa?.let { "%.1f".format(it / 100f) } ?: "—" }
    }

    private fun gearName(g: Int) = when (g) { 1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; else -> "?" }

    private fun Number?.fmt(pattern: String) = this?.let { pattern.format(it) } ?: "—"
}
