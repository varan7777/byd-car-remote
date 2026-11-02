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

    private var calmSince = 0L
    private var official = false // this episode had a yellow/red alert, not only a nearby threat
    private var told = false // a warning went out since the last calm, so the all-clear is worth sending

    fun poll(s: AirState?, car: Autoservice): String? {
        if (s == null || s.error != null) return null
        val rank = rank(s)
        val now = System.currentTimeMillis()
        if (s.officialRank() > 0) official = true
        if (rank < lastRank) {
            // Any step down counts after a few calm minutes, so a drone at the radius edge doesn't
            // send a new warning (or an all-clear) on every re-entry.
            if (calmSince == 0L) calmSince = now
            if (now - calmSince < ua.zaika.carremote.CarRemoteService.CALM_HOLD_MS) return null
            lastRank = rank; calmSince = 0
            if (rank > 0) return null
            val say = told && official
            told = false; official = false
            return if (say) "🟢 Відбій тривоги біля авто." else null
        }
        calmSince = 0
        val worse = rank > lastRank
        lastRank = rank
        if (!worse) return null
        val parked = car.getInt(Fids.DEV_GEAR, Fids.GEAR) == 1
        if (!parked) return null
        // The driver's seat has no sensor on this car, so seats and belt alone can't say the cabin
        // is empty. Someone sitting inside keeps the car unlocked; a car left behind is locked.
        val locked = car.getInt(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER) == 2
        if (!locked || ua.zaika.carremote.car.someoneInside(car)) return null
        told = true
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
        s.within.firstOrNull()?.let { (t, km) ->
            append("\n⚠️ ${t.label} за ${km.roundToInt()} км від авто")
            if (s.approaching) append(", курс на авто")
            // Both points on one map: where the car stands and where the threat is.
            s.fix?.let { (la, lo) -> append("\n🗺 https://www.google.com/maps/dir/$la,$lo/${t.lat},${t.lon}") }
        }
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
