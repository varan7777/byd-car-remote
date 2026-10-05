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

    fun poll(snap: CarSnapshot): List<String> {
        val msgs = ArrayList<String>()

        snap.battTempC?.let { t ->
            if (t >= OVERHEAT_C && !overheatSpoken) {
                msgs += "🔥 Температура батареї висока: $t°C"
                overheatSpoken = true
            }
            if (t <= OVERHEAT_C - 4) overheatSpoken = false
        }

        val isParked = snap.gear == 1 // P
        if (isParked && !wasParked) parkedOpenSpoken = false // re-check on each fresh park
        wasParked = isParked

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

    private companion object { const val OVERHEAT_C = 48 }
}
