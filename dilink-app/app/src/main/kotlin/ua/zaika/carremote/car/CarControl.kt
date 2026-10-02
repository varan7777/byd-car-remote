package ua.zaika.carremote.car

/**
 * A whitelisted control command. The app only ever writes these exact (dev, fid, value)
 * tuples — never a value taken straight from a Telegram message. [verify], when present,
 * is a status address read back after the write to confirm the car actually did it.
 */
data class Control(
    val id: String,
    val label: String,
    val dev: Int,
    val fid: Int,
    val value: Int,
    val confirm: Boolean = false,
    val verify: Verify? = null,
) {
    data class Verify(val dev: Int, val fid: Int, val expected: Int)
}

/**
 * The control set exposed in the bot. Climate on/off is validated on this exact car
 * (Song Plus 2025). Lock/unlock, temperature and defrost come from BYDMate's Leopard 3
 * (DiLink 5) research; each is verified by readback on first use, so an unsupported one
 * reports a mismatch rather than a silent no-op.
 */
object Controls {
    val CLIMATE_ON = Control(
        "climate_on", "❄️ Клімат увімкнути", Fids.DEV_CLIMATE, 501219364, 1,
        verify = Control.Verify(Fids.DEV_CLIMATE, Fids.AC_STATUS, 1),
    )
    val CLIMATE_OFF = Control(
        "climate_off", "⭕ Клімат вимкнути", Fids.DEV_CLIMATE, 501219364, 0,
        verify = Control.Verify(Fids.DEV_CLIMATE, Fids.AC_STATUS, 0),
    )
    val LOCK = Control(
        "lock", "🔒 Замкнути", Fids.DEV_BODYWORK, 1276141590, 2,
        verify = Control.Verify(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER, 2),
    )
    val UNLOCK = Control(
        "unlock", "🔓 Відімкнути", Fids.DEV_BODYWORK, 1276141590, 1, confirm = true,
        verify = Control.Verify(Fids.DEV_DOOR_LOCKS, Fids.LOCK_DRIVER, 1),
    )
    val DEFROST_ON = Control("defrost_on", "🌬 Обігрів скла", Fids.DEV_CLIMATE, 501219357, 1)
    val DEFROST_OFF = Control("defrost_off", "⭕ Обігрів скла вимк.", Fids.DEV_CLIMATE, 501219357, 0)

    // Temperature set point is handled on its own — the written value is the temperature.
    const val TEMP_FID = 501219368
    const val TEMP_MIN = 16
    const val TEMP_MAX = 30

    val byId: Map<String, Control> =
        listOf(CLIMATE_ON, CLIMATE_OFF, LOCK, UNLOCK, DEFROST_ON, DEFROST_OFF).associateBy { it.id }
}
