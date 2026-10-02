package ua.zaika.carremote.car

import ua.zaika.carremote.adb.AdbShell

/**
 * Read-only client for BYD's `autoservice` system Binder, reached with `service call`.
 *
 * Every value lives at a (device, feature id) address. Transaction 5 reads an int,
 * 7 reads a float. Write transactions are deliberately not exposed: control commands
 * come later, behind an explicit whitelist, once reads are confirmed on this car.
 */
class Autoservice(private val adb: AdbShell) {

    /** True when adbd answers at all — separates "no ADB" from "feature absent". */
    fun isReachable(): Boolean = adb.exec("echo ok")?.trim() == "ok"

    val lastError: String? get() = adb.lastError

    fun getInt(dev: Int, fid: Int): Int? = readRaw(TX_GET_INT, dev, fid)?.let(::decodeInt)

    fun getFloat(dev: Int, fid: Int): Float? =
        readRaw(TX_GET_FLOAT, dev, fid)?.let { decodeFloat(java.lang.Float.intBitsToFloat(it)) }

    /** Raw 32-bit return word, or null if the call failed or the reply did not parse. */
    fun readRaw(tx: Int, dev: Int, fid: Int): Int? {
        require(tx == TX_GET_INT || tx == TX_GET_FLOAT) { "read-only client: tx $tx refused" }
        val out = adb.exec("service call autoservice $tx i32 $dev i32 $fid") ?: return null
        val word = PARCEL.find(out)?.groupValues?.get(1) ?: return null
        return word.toLong(16).toInt()
    }

    companion object {
        const val TX_GET_INT = 5
        const val TX_GET_FLOAT = 7

        // Return words that mean "no data" rather than a reading.
        private const val FEATURE_LINK_ERROR = 0x0000FFFF // feature not present on this car
        private const val NOT_INITIALIZED = 0x000FFFFF
        private const val WRONG_TRANSACT = -10013
        private const val WRONG_DIRECTION = -10011

        // `service call` prints e.g. "Result: Parcel(00000000 0000005b   '....[...')":
        // a zero status word (no exception), then the 32-bit value.
        private val PARCEL = Regex("""Parcel\(00000000\s+([0-9a-fA-F]{8})""")

        fun decodeInt(raw: Int): Int? = when (raw) {
            FEATURE_LINK_ERROR, NOT_INITIALIZED, WRONG_TRANSACT, WRONG_DIRECTION -> null
            else -> raw
        }

        fun decodeFloat(value: Float): Float? =
            if (value.isNaN() || value.isInfinite() || value == -1.0f) null else value
    }
}
