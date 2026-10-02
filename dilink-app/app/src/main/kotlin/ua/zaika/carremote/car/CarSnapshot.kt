package ua.zaika.carremote.car

/** A structured one-shot reading for the on-screen dashboard. Null fields = not available. */
data class CarSnapshot(
    val soc: Float?,
    val soh: Int?,
    val battTempC: Int?,
    val v12: Float?,
    val insideC: Int?,
    val outsideC: Int?,
    val powerKw: Int?,
    val mileageKm: Float?,
    val tiresBar: List<Float?>,
    val locked: Boolean?,
    val gear: Int?,
    val gun: Int?,
    val bms: Int?,
) {
    val gearName: String get() = when (gear) { 1 -> "P"; 2 -> "R"; 3 -> "N"; 4 -> "D"; else -> "—" }

    val chargingText: String
        get() {
            val plug = when (gun) {
                1, null -> return if (gun == null) "—" else "не підключено"
                2 -> "AC"; 3 -> "DC"; 4 -> "AC+DC"; 5 -> "V2L"; else -> "код $gun"
            }
            val state = when (bms) { 1 -> "триває"; 2 -> "завершено"; 13 -> "пауза"; else -> null }
            return if (state == null) plug else "$plug · $state"
        }

    companion object {
        fun read(car: Autoservice): CarSnapshot? {
            if (!car.isReachable()) return null
            fun i(dev: Int, fid: Int) = car.getInt(dev, fid)
            fun f(dev: Int, fid: Int) = car.getFloat(dev, fid)
            return CarSnapshot(
                soc = f(Fids.DEV_STATISTIC, Fids.SOC),
                soh = i(Fids.DEV_STATISTIC, Fids.SOH),
                battTempC = i(Fids.DEV_STATISTIC, Fids.BATTERY_TEMP_MAX)?.minus(40),
                v12 = f(Fids.DEV_BODYWORK, Fids.BATTERY_12V),
                insideC = i(Fids.DEV_CLIMATE, Fids.INSIDE_TEMP),
                outsideC = i(Fids.DEV_CLIMATE, Fids.OUTSIDE_TEMP),
                powerKw = i(Fids.DEV_ENGINE, Fids.ENGINE_POWER),
                mileageKm = i(Fids.DEV_STATISTIC, Fids.LIFETIME_MILEAGE)?.let { it / 10f },
                tiresBar = Fids.TIRE_PRESSURE.map { i(Fids.DEV_TIRES, it)?.let { kpa -> kpa / 100f } },
                locked = i(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER)?.let { it == 2 },
                gear = i(Fids.DEV_GEAR, Fids.GEAR),
                gun = i(Fids.DEV_CHARGING, Fids.GUN_CONNECT_STATE),
                bms = i(Fids.DEV_CHARGING, Fids.CHARGING_BMS_STATE),
            )
        }
    }
}
