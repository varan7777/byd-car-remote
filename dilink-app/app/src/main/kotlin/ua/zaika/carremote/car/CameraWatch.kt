package ua.zaika.carremote.car

import android.os.SystemClock

/** Reads signals only; the service owns camera presentation on the main thread. */
class CameraWatch(private val car: Autoservice) {
    fun preview(side: CameraSide): CameraSide? {
        val start = SystemClock.elapsedRealtime()
        val gear = car.getInt(Fids.DEV_GEAR, Fids.GEAR)
        val speed = car.getFloat(Fids.DEV_DRIVE, Fids.SPEED)
        return side.takeIf { gear == 1 && speed != null && speed.isFinite() && speed in 0f..0.5f &&
            SystemClock.elapsedRealtime() - start <= 750 }
    }
    fun poll(onTurn: Boolean, onDoor: Boolean, threshold: Int): CameraSide? {
        val start = SystemClock.elapsedRealtime()
        val gear = car.getInt(Fids.DEV_GEAR, Fids.GEAR)
        if (gear == null || gear == 2) return null
        val speed = car.getFloat(Fids.DEV_DRIVE, Fids.SPEED)
        val turn = if (onTurn && gear != 1) car.getInt(Fids.TURN_DEV, Fids.TURN_MASK) else null
        val doors = if (onDoor && gear == 1) Fids.DOORS.map { car.getInt(Fids.DEV_BODYWORK, it) }
            else emptyList()
        return CameraDecision.choose(onTurn, onDoor, threshold, gear, speed, turn, doors,
            SystemClock.elapsedRealtime() - start)
    }
}
