package ua.zaika.carremote.car

/**
 * Safe, read-only "automations": watches each snapshot and returns reminder messages on state
 * changes — the battery overheating, or the car left open while parked. Stateful (fires on
 * transitions, not every poll) and issues no vehicle commands, so it is safe to run unattended.
 *
 * These fire while the app is alive (driving, charging, or parked-awake). A car switched fully
 * off kills the app within seconds, so the parting watchdog message remains the backstop there.
 */
class Automations {

    private var overheatSpoken = false
    private var parkedOpenSpoken = false
    private var wasParked = false
    private var parkedAt = 0L
    private var tyreBase: List<Float?> = emptyList() // pressures once the tyres cooled after parking, bar
    private val tyreWarned = HashSet<Int>()

    fun poll(snap: CarSnapshot): List<String> {
        val msgs = ArrayList<String>()

        snap.battTempC?.let { t ->
            if (t >= OVERHEAT_C && !overheatSpoken) {
                msgs += "🔥 Температура батареї висока: $t°C"
                overheatSpoken = true
            }
            if (t <= OVERHEAT_C - 4) overheatSpoken = false
        }

        // An unread gear (car dozing) keeps the last state rather than ending the park.
        val isParked = when (snap.gear) { null -> wasParked; 1 -> true; else -> false }
        val now = System.currentTimeMillis()
        if (isParked && !wasParked) {
            parkedOpenSpoken = false // re-check on each fresh park
            parkedAt = now; tyreBase = emptyList(); tyreWarned.clear()
        }
        wasParked = isParked

        // A tyre losing air while parked: say so before the owner drives off on it. Tyres are hot
        // right after a drive, so the baseline is taken once they've cooled (half an hour in), and
        // a drop counts only beyond what the other tyres lost too — a cold night lowers all four.
        val bars = snap.tiresBar.map { it?.takeIf { b -> b > 0.5f } }
        if (isParked && bars.count { it != null } >= 3) {
            // Low whatever it was: checked from the first parked poll — a tyre that went flat while
            // the car slept must be reported before driving off, not half an hour later.
            bars.forEachIndexed { i, bar ->
                if (bar != null && bar < TYRE_LOW_BAR && i !in tyreWarned) {
                    tyreWarned += i
                    msgs += "🛞 Низький тиск: ${TYRES.getOrElse(i) { "колесо" }} — %.1f бар. Підкачайте колесо.".format(bar)
                }
            }
            if (now - parkedAt < TYRE_SETTLE_MS || tyreBase.size != bars.size) tyreBase = bars
            else {
                val drops = bars.mapIndexed { i, b -> val base = tyreBase.getOrNull(i); if (b == null || base == null) null else base - b }
                val usual = drops.filterNotNull().sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
                bars.forEachIndexed { i, bar ->
                    val drop = drops[i]
                    if (bar == null || drop == null || i in tyreWarned || drop - usual < TYRE_DROP_BAR) return@forEachIndexed
                    tyreWarned += i
                    msgs += "🛞 Тиск падає: ${TYRES.getOrElse(i) { "колесо" }} — %.1f бар (було %.1f). Перевірте колесо.".format(bar, tyreBase[i])
                }
            }
        }

        if (isParked && !parkedOpenSpoken) {
            val open = ArrayList<String>()
            if (snap.locked == false) open += "незамкнене"
            if (snap.windowsOpenPct.any { it != null && it > 0 }) open += "вікно відчинене"
            if ((snap.sunroofPct ?: 0) > 0) open += "люк відкритий"
            if (snap.trunkOpen == true) open += "багажник відкритий"
            if (snap.hoodOpen == true) open += "капот відкритий"
            if (open.isNotEmpty()) {
                msgs += "🅿️ Авто на стоянці: ${open.joinToString(", ")}"
                parkedOpenSpoken = true
            }
        }

        return msgs
    }

    private companion object {
        const val OVERHEAT_C = 48
        const val TYRE_DROP_BAR = 0.3f // lost since parking, beyond what the other tyres lost
        const val TYRE_SETTLE_MS = 30 * 60_000L
        const val TYRE_LOW_BAR = 1.9f  // low whatever it was
        val TYRES = listOf("переднє ліве", "переднє праве", "заднє ліве", "заднє праве")
    }
}
