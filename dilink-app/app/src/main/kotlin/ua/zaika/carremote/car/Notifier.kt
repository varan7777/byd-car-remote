package ua.zaika.carremote.car

import kotlin.math.abs

/**
 * Watches a few values each poll and returns a message whenever one crosses a line worth
 * telling the owner about: charging started/finished, cable in/out, charging progress with a
 * time-to-full, low charge, low 12V. Stateful on purpose — it speaks on changes and on a slow
 * charging heartbeat, never every poll.
 */
class Notifier(private val car: Autoservice, private val lowSocPct: () -> Int) {

    private var gun: Int? = null
    private var bms: Int? = null
    private var lowSocSpoken = false
    private var low12vSpoken = false

    // Charging session, for a rate-based (self-calibrating) time-to-full.
    private var chargeStartSoc: Float? = null
    private var chargeStartMs = 0L
    private var lastProgressMs = 0L

    fun poll(): List<String> {
        val msgs = mutableListOf<String>()
        val soc = car.getFloat(Fids.DEV_STATISTIC, Fids.SOC)

        val newGun = car.getInt(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE)
        if (newGun != null && gun != null && newGun != gun) {
            if (gun == 1 && newGun != 1) msgs += "🔌 Кабель підключено"
            if (gun != 1 && newGun == 1) msgs += "🔌 Кабель від'єднано"
        }
        if (newGun != null) gun = newGun

        val newBms = car.getInt(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE)
        if (newBms != null && newBms != bms) {
            val pct = soc?.let { " (заряд ${it.toInt()}%)" } ?: ""
            when (newBms) {
                1 -> if (bms != 1) msgs += "⚡ Зарядка почалась$pct"
                2 -> if (bms == 1 || bms == 13) msgs += "✅ Зарядка завершена$pct"
                13 -> if (bms == 1) msgs += "⏸ Зарядка на паузі$pct"
            }
            bms = newBms
        }

        // Charging progress heartbeat with a measured time-to-full.
        if (bms == 1) {
            val now = System.currentTimeMillis()
            if (chargeStartSoc == null) {
                chargeStartSoc = soc; chargeStartMs = now; lastProgressMs = now
            } else if (now - lastProgressMs >= PROGRESS_MS) {
                chargingProgress(soc)?.let { msgs += it }
                lastProgressMs = now
            }
        } else {
            chargeStartSoc = null
        }

        soc?.let {
            val limit = lowSocPct()
            if (it < limit && !lowSocSpoken) { msgs += "🪫 Заряд нижче $limit% (${it.toInt()}%)"; lowSocSpoken = true }
            if (it >= limit + 2) lowSocSpoken = false
        }

        car.getFloat(Fids.DEV_BODYWORK, Fids.BATTERY_12V)?.let { v ->
            if (v < 11.8f && !low12vSpoken) { msgs += "⚠️ Напруга 12V низька: %.1f В".format(v); low12vSpoken = true }
            if (v >= 12.2f) low12vSpoken = false
        }

        return msgs
    }

    private fun chargingProgress(soc: Float?): String? {
        if (soc == null) return null
        val power = Power.kw(
            car.getInt(Fids.DEV_CHARGING, Fids.HV_VOLTAGE),
            car.getFloat(Fids.DEV_CHARGING, Fids.HV_CURRENT),
        )?.let { abs(it) }
        return buildString {
            append("⚡ Заряджається: ${soc.toInt()}%")
            if (power != null) append(" · %.1f кВт".format(power))
            eta(soc, power)?.let { append("\n⏱ $it до повного") }
        }
    }

    /** Prefer the measured charging rate; fall back to the power/capacity estimate early on. */
    private fun eta(soc: Float, power: Float?): String? {
        val start = chargeStartSoc
        val elapsedH = (System.currentTimeMillis() - chargeStartMs) / 3_600_000f
        if (start != null && elapsedH > 0.1f && soc > start + 0.5f) {
            val rate = (soc - start) / elapsedH // % per hour
            if (rate > 0.3f) return Power.formatHours((100f - soc) / rate)
        }
        return Power.etaByPower(soc, power)
    }

    private companion object {
        const val PROGRESS_MS = 15 * 60 * 1000L // charging heartbeat every 15 min
    }
}
