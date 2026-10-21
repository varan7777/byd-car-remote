package ua.zaika.carremote.car

/**
 * Known autoservice read addresses, from BYDMate's on-device research on a Leopard 3 (DiLink 5).
 * Verified on Song Plus 2025 (DiLink 5.0): SOC, SOH, LIFETIME_MILEAGE, BATTERY_12V,
 * GUN_CONNECT_STATE, CHARGING_BMS_STATE, ENGINE_POWER. The rest are unverified on Song Plus —
 * a value this car does not expose simply reads as "no data".
 */
object Fids {
    const val DEV_CLIMATE = 1000
    const val DEV_BODYWORK = 1001
    const val DEV_CHARGING = 1009
    const val DEV_GEAR = 1011
    const val DEV_ENGINE = 1012
    const val DEV_DRIVE = 1013
    const val DEV_STATISTIC = 1014
    const val DEV_TIRES = 1016
    const val DEV_DOOR_LOCKS = 1032

    // Gear + drive. Verified on Song Plus 2025.
    const val GEAR = 555745336 // int: 1 P, 2 R, 3 N, 4 D
    const val SPEED = -1807745016 // float, km/h

    // Statistic, dev 1014
    const val SOC = 1246777400 // float, %
    const val SOH = 1145045032 // int, %
    const val LIFETIME_MILEAGE = 1246765072 // int, km × 10
    const val LIFETIME_KWH = 1032871984 // float, kWh total throughput (for trip energy)
    const val BATTERY_TEMP_MAX = 1148190752 // int, °C + 40
    const val BATTERY_TEMP_MIN = 1148190736 // int, °C + 40

    // Charging, dev 1009
    const val GUN_CONNECT_STATE = 876609586 // int: 1 none, 2 AC, 3 DC, 4 AC+DC, 5 V2L
    const val CHARGING_BMS_STATE = 876609560 // int: 1 charging, 2 finished, 13 paused
    const val HV_VOLTAGE = 1145045000 // int, V (high-voltage pack). Verified on Song Plus.
    const val HV_CURRENT = 1145045016 // float, A (− = into battery). Verified on Song Plus.

    // Engine, dev 1012
    const val ENGINE_POWER = 339738656 // int, kW: + drawing, − regen or charging

    // Seats, dev 1000: switch (1=on, 2=off), level (1..5), status read (0=off, 1..5).
    const val SEAT_HEAT_DRIVER_SWITCH = 1276248084
    const val SEAT_HEAT_DRIVER_LEVEL = 1276252180
    const val SEAT_HEAT_DRIVER_READ = 702545948
    const val SEAT_HEAT_PASS_SWITCH = 1276248092
    const val SEAT_HEAT_PASS_LEVEL = 1276252188
    const val SEAT_HEAT_PASS_READ = 711983132

    // Lights, dev 1004. Hazard flash for "find car": write 1=on, 0=off (no readback).
    const val DEV_LIGHTS = 1004
    const val HAZARD = 871366669

    // Climate, dev 1000. Verified on Song Plus 2025.
    const val AC_STATUS = 1077936144 // int: 1 on
    const val AC_TEMP = 1077936168 // int, °C set point
    const val INSIDE_TEMP = 1031798832 // int, °C
    const val OUTSIDE_TEMP = 1077936184 // int, °C

    // Bodywork, dev 1001
    const val BATTERY_12V = 1128267816 // float, V
    const val TRUNK = 1074790416 // int: 1 open
    const val HOOD = 692060188 // int: 1 open

    const val SUNROOF = 1101004808 // dev 1001, int %: 0 closed .. 100 open

    // Control channels (dev 1001), from BYDMate's Leopard 3 research — the app verifies by readback.
    val WINDOW_SET = listOf(1276219408, 1276219424, 1276219416, 1276219432) // 0..100% per window
    const val SUNROOF_CTRL = 1125122056 // 1 open, 2 close, 3 tilt, 4 stop
    const val REAR_TRUNK = 1125122080 // power tailgate: 1 open, 3 close (read state via TRUNK)

    // Front left, front right, rear left, rear right.
    val DOORS = listOf(692060168, 692060170, 692060172, 692060174) // dev 1001, int: 1 open
    val WINDOWS = listOf(947912728, 1267728400, 947912736, 947912752) // dev 1001, int, % open
    val TIRE_PRESSURE = listOf(-1728052956, -1728052952, -1728052948, -1728052944) // dev 1016, int, kPa

    // Door locks, dev 1032
    const val LOCK_DRIVER = 1081081864 // int: 1 unlocked, 2 locked

    // --- Camera automations (blind-spot on turn signal / rear on door open). ---
    // These three addresses are still UNKNOWN on Song Plus; 0 = "not set", which keeps the whole
    // camera feature a safe no-op (see CameraWatch). Capture them on an AWAKE car:
    //   • TURN_LEFT / TURN_RIGHT — toggle each blinker, watch which fid flips 0↔1 (likely dev 1004).
    //   • CAMERA_VIEW_CTRL + CAMERA_DEV — how DiLink shows the camera. Either an autoservice command
    //     (write 1 = show view, 0 = hide), or — if there is none — a camera package to launch
    //     (set it in Settings via KEY_CAM_PKG instead, no rebuild needed).
    const val TURN_DEV = DEV_LIGHTS   // best guess; adjust once probed
    const val TURN_LEFT = 0           // int: 1 = on
    const val TURN_RIGHT = 0          // int: 1 = on
    const val CAMERA_DEV = DEV_BODYWORK
    const val CAMERA_VIEW_CTRL = 0    // int: 1 = show blind-spot / around-view, 0 = hide

    /** A fid placeholder of 0 means "address not captured yet" — treat the feature as off. */
    fun isSet(fid: Int) = fid != 0
}
