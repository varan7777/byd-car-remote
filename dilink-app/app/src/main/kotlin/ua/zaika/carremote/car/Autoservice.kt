package ua.zaika.carremote.car

import ua.zaika.carremote.adb.AdbShell

/**
 * Client for BYD's `autoservice` system Binder, reached with `service call`.
 *
 * Every value lives at a (device, feature id) address. Transaction 5 reads an int,
 * 7 reads a float, 6 writes an int. [write] is only ever called with a tuple from the
 * [Controls] whitelist or the comfort/camera modules, never an arbitrary chat address.
 * Protocol acceptance and physical readback are reported separately by callers.
 */
class Autoservice(private val adb: AdbShell) {

    /** True when adbd answers at all — separates "no ADB" from "feature absent". */
    fun isReachable(): Boolean = adb.exec("echo ok")?.trim() == "ok"

    val lastError: String? get() = adb.lastError

    data class WriteResult(val accepted: Boolean, val changed: Boolean, val code: Int?)

    /** A shell reply alone does not mean the vehicle accepted or actuated the command. */
    fun writeResult(dev: Int, fid: Int, value: Int): WriteResult {
        val output = adb.exec("service call autoservice $TX_SET_INT i32 $dev i32 $fid i32 $value")
        val reply = output?.let(BinderReply::parse)
            ?: return WriteResult(false, false, null)
        val code = if (reply.status != 0) reply.status else reply.value
        return WriteResult(reply.writeAccepted, reply.writeAccepted && code == 1, code)
    }

    fun write(dev: Int, fid: Int, value: Int): Boolean = writeResult(dev, fid, value).accepted

    fun getInt(dev: Int, fid: Int): Int? = readRaw(TX_GET_INT, dev, fid)?.let(::decodeInt)

    fun getFloat(dev: Int, fid: Int): Float? =
        readRaw(TX_GET_FLOAT, dev, fid)?.let { decodeFloat(java.lang.Float.intBitsToFloat(it)) }

    /** Raw 32-bit return word, or null if the call failed or the reply did not parse. */
    fun readRaw(tx: Int, dev: Int, fid: Int): Int? {
        require(tx == TX_GET_INT || tx == TX_GET_FLOAT) { "read-only client: tx $tx refused" }
        val out = adb.exec("service call autoservice $tx i32 $dev i32 $fid") ?: return null
        val reply = BinderReply.parse(out) ?: return null
        return reply.value.takeIf { reply.status == 0 }
    }

    companion object {
        const val TX_GET_INT = 5
        const val TX_SET_INT = 6
        const val TX_GET_FLOAT = 7

        // Return words that mean "no data" rather than a reading.
        private const val FEATURE_LINK_ERROR = 0x0000FFFF // feature not present on this car
        private const val NOT_INITIALIZED = 0x000FFFFF
        private const val WRONG_TRANSACT = -10013
        private const val WRONG_DIRECTION = -10011


        fun decodeInt(raw: Int): Int? = when (raw) {
            FEATURE_LINK_ERROR, NOT_INITIALIZED, WRONG_TRANSACT, WRONG_DIRECTION -> null
            else -> raw
        }

        fun decodeFloat(value: Float): Float? =
            if (value.isNaN() || value.isInfinite() || value == -1.0f) null else value
    }
}
