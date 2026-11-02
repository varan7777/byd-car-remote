package ua.zaika.carremote.air

import ua.zaika.carremote.car.Autoservice
import ua.zaika.carremote.car.Fids
import kotlin.math.roundToInt

/**
 * Telegram air-alert messages for the car's owner. Sent only on a change for the worse (calm →
 * yellow → red, or a threat entering the radius) and only while the car stands in P, locked, with
 * nobody in the seats — when someone is inside, the head unit's beep, voice and banner do the job.
 */
class AlertNotifier {
    private var lastRank = 0

    fun poll(s: AirState?, car: Autoservice): String? {
        if (s == null || s.error != null) return null
        val rank = rank(s)
        val worse = rank > lastRank
        lastRank = rank
        if (!worse) return null
        val parked = car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1
        if (!parked) return null
        // The seat sensors don't see the driver on this car, so they alone can't say the cabin
        // is empty. Someone sitting inside keeps the car unlocked; a car left behind is locked.
        val seated = Fids.OCCUPANCY.any { car.getInt(Fids.DEV_SEATS, it) == 2 }
        val locked = car.getInt(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER) == 2
        if (seated || !locked) return null
        return text(s)
    }

    private fun rank(s: AirState): Int = when {
        s.within.isNotEmpty() -> 3
        s.regionLevel == "red" || (s.oblastAlert != null && s.regionLevel != "yellow") -> 2
        s.regionLevel != null -> 1 // yellow or any other official level
        else -> 0
    }

    private fun text(s: AirState): String = buildString {
        append(levelEmoji(s.regionLevel, s.oblastAlert != null)).append(' ')
        append(levelName(s.regionLevel, s.oblastAlert != null))
        s.oblastAlert?.let { append(": ").append(it.name) }
        s.within.firstOrNull()?.let { (t, km) -> append("\n⚠️ ${t.label} за ${km.roundToInt()} км від авто") }
            ?: s.nearest?.let { n -> s.nearestKm?.let { append("\nНайближче: ${n.label} ~${it.roundToInt()} км") } }
        append("\n🚗 Авто стоїть замкнене.")
    }

    companion object {
        fun levelName(level: String?, oblast: Boolean): String = when (level) {
            "red" -> "Повітряна тривога (червона)"
            "yellow" -> "Жовта тривога"
            null -> if (oblast) "Повітряна тривога" else "Загроза поруч"
            else -> "Тривога: $level"
        }
        fun levelEmoji(level: String?, oblast: Boolean): String = when (level) {
            "red" -> "🔴"; "yellow" -> "🟡"; null -> if (oblast) "🔴" else "⚠️"; else -> "🟣"
        }
    }
}
