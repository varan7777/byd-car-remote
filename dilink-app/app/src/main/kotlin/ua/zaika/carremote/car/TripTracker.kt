package ua.zaika.carremote.car

/**
 * Tracks the current drive. The baseline is taken when the service starts (a wake), and the
 * trip is the delta since: distance from the odometer, energy from the BMS lifetime-kWh
 * counter. At ignition-off the last computed line goes into the parting message.
 */
class TripTracker {
    private var startMs = 0L
    private var startKm: Float? = null
    private var startKwh: Float? = null

    fun baselineIfNeeded(snap: CarSnapshot) {
        if (startKm == null && snap.mileageKm != null) {
            startKm = snap.mileageKm
            startKwh = snap.lifetimeKwh
            startMs = System.currentTimeMillis()
        }
    }

    /** One line summarising the drive so far, or null if the car has not moved yet. */
    fun line(snap: CarSnapshot): String? {
        val start = startKm ?: return null
        val km = snap.mileageKm ?: return null
        val dist = km - start
        if (dist < 0.1f) return null
        val mins = ((System.currentTimeMillis() - startMs) / 60000).toInt()
        val energy = if (startKwh != null && snap.lifetimeKwh != null) snap.lifetimeKwh!! - startKwh!! else null
        return buildString {
            append("🚗 Поїздка: %.1f км, %d хв".format(dist, mins))
            if (energy != null && energy > 0f) {
                append(", %.1f кВт·год".format(energy))
                if (dist > 0.5f) append(" (%.1f кВт·год/100)".format(energy / dist * 100))
            }
        }
    }
}
