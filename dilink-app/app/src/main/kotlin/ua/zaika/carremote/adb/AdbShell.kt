package ua.zaika.carremote.adb

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Shell access to the head unit's own adbd on 127.0.0.1:5555.
 *
 * `service call autoservice ...` needs the shell UID, which a normal app does not have;
 * talking ADB to itself gives it that UID. On the very first connect DiLink shows
 * "Allow USB debugging?" for this app's key — tap "Always allow" once.
 */
class AdbShell(context: Context) : AutoCloseable {

    private val privateKey = File(context.filesDir, "adb/adbkey")
    private val publicKey = File(context.filesDir, "adb/adbkey.pub")
    private var dadb: Dadb? = null

    /** Why the last [exec] failed, for the status screen. */
    @Volatile var lastError: String? = null
        private set

    /** Runs a shell command and returns its stdout, or null if adbd is unreachable. */
    @Synchronized
    fun exec(cmd: String): String? = try {
        val output = (dadb ?: open().also { dadb = it }).shell(cmd).output
        lastError = null
        output
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        close()
        null
    }

    /** Copies a local file onto the head unit (e.g. an update APK into /data/local/tmp). */
    @Synchronized
    fun push(file: File, remotePath: String): Boolean = try {
        (dadb ?: open().also { dadb = it }).push(file, remotePath, 420 /* 0644 */, file.lastModified())
        lastError = null
        true
    } catch (e: Exception) {
        lastError = e.message ?: e.javaClass.simpleName
        close()
        false
    }

    private fun open(): Dadb {
        if (!privateKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        return Dadb.create(HOST, PORT, AdbKeyPair.read(privateKey, publicKey), CONNECT_TIMEOUT_MS, SOCKET_TIMEOUT_MS)
    }

    @Synchronized
    override fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val CONNECT_TIMEOUT_MS = 3_000
        // Long enough for someone in the car to tap "Allow" on the first-connect prompt.
        const val SOCKET_TIMEOUT_MS = 20_000
    }
}
