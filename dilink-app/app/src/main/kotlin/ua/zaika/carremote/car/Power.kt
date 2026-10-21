package ua.zaika.carremote.car

/** High-voltage power maths. Power comes from pack voltage × current, so it has real decimals
 *  (the engine-power fid is whole kW only). Negative = energy flowing into the battery. */
object Power {
    /** Battery capacity, kWh. Per owner: 87 kWh on this Song Plus (used only for the charge-time
     *  fallback estimate). */
    const val CAPACITY_KWH = 87f

    fun kw(hvVolts: Int?, hvAmps: Float?): Float? =
        if (hvVolts != null && hvAmps != null) hvVolts * hvAmps / 1000f else null

    /** "~1 год 45 хв" / "~40 хв" from an hours value, or null if out of a sane range. */
    fun formatHours(hours: Float): String? {
        if (hours <= 0f || hours > 48f) return null
        val h = hours.toInt()
        val m = ((hours - h) * 60).toInt()
        return if (h > 0) "~$h год $m хв" else "~$m хв"
    }

    /** Fallback time-to-full from power + assumed capacity — used only until a measured rate exists. */
    fun etaByPower(socPercent: Float?, chargeKw: Float?): String? {
        if (socPercent == null || chargeKw == null || chargeKw < 0.2f || socPercent >= 100f) return null
        return formatHours((100f - socPercent) / 100f * CAPACITY_KWH / chargeKw)
    }
}
