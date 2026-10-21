package ua.zaika.carremote.car

enum class CameraSide(val preview: Int) { LEFT(2), RIGHT(3) }

/** Shared policy for live polling and tests. Never compete with the factory reverse view. */
object CameraDecision {
    fun choose(
        turnEnabled: Boolean, doorEnabled: Boolean, threshold: Int,
        gear: Int?, speed: Float?, turn: Int?, doors: List<Int?>, ageMs: Long,
    ): CameraSide? {
        if (ageMs !in 0..750 || gear !in setOf(1, 3, 4) || speed == null ||
            !speed.isFinite() || speed < 0) return null
        if (gear == 1 && speed <= 0.5f && doorEnabled && doors.size == 4 &&
            doors.all { it != null && it in 0..2 }) {
            val left = doors[0] == 1 || doors[2] == 1
            val right = doors[1] == 1 || doors[3] == 1
            return when { left && !right -> CameraSide.LEFT; right && !left -> CameraSide.RIGHT; else -> null }
        }
        if (!turnEnabled || gear == 1 || speed < threshold.coerceIn(5, 80)) return null
        return when (turn) { 2 -> CameraSide.LEFT; 4 -> CameraSide.RIGHT; else -> null }
    }
}
