package ua.zaika.carremote.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Telegram Bot API client using long polling. The car only makes outgoing
 * HTTPS requests, so it works behind mobile-carrier NAT with no server of our own.
 */
class TelegramBot(
    private val token: String,
    private val onMessage: (chatId: Long, text: String) -> String?,
) {
    private var offset = 0L

    /**
     * Polls until cancelled, reconnecting after any network failure.
     * [onConnected] fires once, after the first successful poll.
     */
    suspend fun run(onStatus: (String) -> Unit, onConnected: () -> Unit) {
        var connected = false
        while (currentCoroutineContext().isActive) {
            try {
                if (!connected) {
                    // Drop whatever queued up while the car was offline: an old command
                    // must not fire hours after it was sent.
                    skipBacklog()
                    connected = true
                    onStatus("Telegram: онлайн")
                    onConnected()
                }
                poll()
                onStatus("Telegram: онлайн")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatus("Telegram: немає зв'язку (${e.message}), повтор за ${RETRY_MS / 1000} с")
                delay(RETRY_MS)
            }
        }
    }

    fun send(chatId: Long, text: String) {
        call("sendMessage", "chat_id" to chatId, "text" to text)
    }

    private fun skipBacklog() {
        val pending = call("getUpdates", "offset" to -1, "timeout" to 0).getJSONArray("result")
        if (pending.length() > 0) {
            offset = pending.getJSONObject(pending.length() - 1).getLong("update_id") + 1
        }
    }

    private fun poll() {
        val updates = call("getUpdates", "offset" to offset, "timeout" to POLL_SECONDS).getJSONArray("result")
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            offset = update.getLong("update_id") + 1
            val message = update.optJSONObject("message") ?: continue
            val chatId = message.optJSONObject("chat")?.optLong("id") ?: continue
            val text = message.optString("text")
            if (text.isBlank()) continue
            onMessage(chatId, text)?.let { send(chatId, it) }
        }
    }

    private fun call(method: String, vararg params: Pair<String, Any>): JSONObject {
        val body = params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value.toString(), "UTF-8")}" }
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = (POLL_SECONDS + 15) * 1000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            // Read errorStream on failure: inputStream would throw with the URL, which contains the token.
            val stream = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                ?: throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(stream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("ok")) throw IOException("$method: ${json.optString("description")}")
            return json
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val POLL_SECONDS = 50
        const val RETRY_MS = 10_000L
    }
}
