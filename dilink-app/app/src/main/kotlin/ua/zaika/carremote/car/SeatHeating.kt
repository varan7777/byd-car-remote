package ua.zaika.carremote.car

/** Seat protocol facts from BYDMate; no speculative writes outside these comfort channels. */
class SeatHeating(
    private val read: (Int, Int) -> Int?,
    private val write: (Int, Int, Int) -> Autoservice.WriteResult,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val useFallback: (Boolean) -> Boolean = { false },
    private val rememberFallback: (Boolean) -> Unit = {},
) {
    fun set(driver: Boolean, level: Int): String {
        require(level in 0..3)
        val who = if (driver) "водія" else "пасажира"
        val switch = if (driver) Fids.SEAT_HEAT_DRIVER_SWITCH else Fids.SEAT_HEAT_PASS_SWITCH
        val levelFid = if (driver) Fids.SEAT_HEAT_DRIVER_LEVEL else Fids.SEAT_HEAT_PASS_LEVEL
        val levelRead = if (driver) Fids.SEAT_HEAT_DRIVER_READ else Fids.SEAT_HEAT_PASS_READ
        val fallback = if (driver) Fids.SEAT_HEAT_DRIVER_FALLBACK else Fids.SEAT_HEAT_PASS_FALLBACK
        val target = if (level == 0) "вимкнення" else "рівень $level"
        fun alternative(): String {
            val result = write(Fids.DEV_BODYWORK, fallback, level + 1)
            if (!result.accepted) return "⚠️ Підігрів $who: команда відхилена (код ${result.code ?: "немає відповіді"})."
            pause(500)
            val seen = read(Fids.DEV_CLIMATE, levelRead)
            val status = if (driver) read(Fids.DEV_CLIMATE, Fids.SEAT_HEAT_DRIVER_STATUS) else null
            val confirmed = if (level == 0) status == 2 else seen == level && (!driver || status == 1)
            if (confirmed) rememberFallback(driver)
            return if (confirmed) "✅ Підігрів $who: $target — підтверджено авто."
                else "➡️ Підігрів $who: $target надіслано альтернативним каналом; перевір на сидінні."
        }
        if (useFallback(driver)) return alternative()
        val primary = write(Fids.DEV_CLIMATE, switch, if (level == 0) 2 else 1)
        if (primary.code == null) return "⚠️ Підігрів $who: немає відповіді від авто."
        if (primary.accepted && level > 0) {
            pause(200)
            val adjusted = write(Fids.DEV_CLIMATE, levelFid, level)
            if (!adjusted.accepted && adjusted.code == null)
                return "⚠️ Підігрів $who: зв'язок перервався під час налаштування рівня."
        }
        var contradictions = 0
        if (primary.accepted) {
            repeat(2) {
                pause(500)
                val seenLevel = read(Fids.DEV_CLIMATE, levelRead)
                val status = if (driver) read(Fids.DEV_CLIMATE, Fids.SEAT_HEAT_DRIVER_STATUS) else null
                if (level > 0 && seenLevel == level && (!driver || status == 1) || level == 0 &&
                    status == 2) {
                    return "✅ Підігрів $who: $target — підтверджено авто."
                }
                // A missing read is inconclusive; do not interpret a network outage as a
                // reason to send a second command on a different device channel.
                if (seenLevel != null && (level > 0 && (seenLevel != level || driver && status != null && status != 1) ||
                    level == 0 && (status == 1 || seenLevel > 0))) contradictions++
            }
            if (level > 0 && contradictions < 2 && primary.changed)
                return "➡️ Підігрів $who: $target надіслано; стан не підтверджено."
        }
        // Song-family fallback uses a single enum (off=1, stage N=N+1) on BODYWORK.
        return alternative()
    }
}
