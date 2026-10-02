package ua.zaika.carremote.telegram

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One inline button. [data] is sent back as a callback when it is pressed. */
data class Button(val label: String, val data: String)

sealed interface Keyboard

/** Buttons under a message; a tap sends [Button.data] as a callback. For transient prompts. */
data class InlineKeyboard(val rows: List<List<Button>>) : Keyboard

/** A persistent keyboard above the text box, toggled by the grid icon. A tap sends the
 *  button's label as a normal message, so it is shown once and not repeated per reply. */
data class ReplyKeyboard(val rows: List<List<String>>) : Keyboard

/** A bot reply: text plus an optional keyboard. */
data class Reply(val text: String, val keyboard: Keyboard? = null)

/**
 * Minimal Telegram Bot API client using long polling. The car only makes outgoing HTTPS
 * requests, so it works behind mobile-carrier NAT with no server of our own. Handles both
 * typed commands and inline-button taps through the same [handle] callback.
 */
class TelegramBot(
    private val token: String,
    private val handle: (chatId: Long, input: String) -> Reply?,
) {
    private var offset = 0L

    suspend fun run(onStatus: (String) -> Unit, onConnected: () -> Unit) {
        var connected = false
        while (currentCoroutineContext().isActive) {
            try {
                if (!connected) {
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

    fun send(chatId: Long, text: String) = send(chatId, Reply(text))

    fun send(chatId: Long, reply: Reply) {
        val params = mutableListOf<Pair<String, Any>>("chat_id" to chatId, "text" to reply.text)
        reply.keyboard?.let { params += "reply_markup" to keyboardJson(it) }
        call("sendMessage", *params.toTypedArray())
    }

    private fun skipBacklog() {
        val pending = call("getUpdates", "offset" to -1, "timeout" to 0, "allowed_updates" to ALLOWED)
            .getJSONArray("result")
        if (pending.length() > 0) offset = pending.getJSONObject(pending.length() - 1).getLong("update_id") + 1
    }

    private fun poll() {
        // allowed_updates is sticky on Telegram's side; a bot once polled without "callback_query"
        // keeps dropping button taps until a later call re-declares it — so we send it every time.
        val updates = call("getUpdates", "offset" to offset, "timeout" to POLL_SECONDS, "allowed_updates" to ALLOWED)
            .getJSONArray("result")
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            offset = update.getLong("update_id") + 1
            val callback = update.optJSONObject("callback_query")
            if (callback != null) {
                onCallback(callback)
                continue
            }
            val message = update.optJSONObject("message") ?: continue
            val chatId = message.optJSONObject("chat")?.optLong("id") ?: continue
            val text = message.optString("text")
            Log.i(TAG, "message from $chatId: $text")
            if (text.isNotBlank()) handle(chatId, text)?.let { send(chatId, it) }
        }
    }

    private fun onCallback(cb: JSONObject) {
        // Stop the button's spinner first, whatever happens next.
        runCatching { call("answerCallbackQuery", "callback_query_id" to cb.getString("id")) }
        val chatId = cb.optJSONObject("message")?.optJSONObject("chat")?.optLong("id") ?: return
        val data = cb.optString("data")
        Log.i(TAG, "callback from $chatId: $data")
        if (data.isNotBlank()) {
            val reply = runCatching { handle(chatId, data) }.getOrElse {
                Log.e(TAG, "handle failed for '$data'", it); Reply("Помилка: ${it.message}")
            }
            reply?.let { send(chatId, it) }
        }
    }

    private fun keyboardJson(keyboard: Keyboard): String = when (keyboard) {
        is InlineKeyboard -> {
            val body = keyboard.rows.joinToString(",") { row ->
                "[" + row.joinToString(",") { """{"text":${quote(it.label)},"callback_data":${quote(it.data)}}""" } + "]"
            }
            """{"inline_keyboard":[$body]}"""
        }
        is ReplyKeyboard -> {
            val body = keyboard.rows.joinToString(",") { row ->
                "[" + row.joinToString(",") { """{"text":${quote(it)}}""" } + "]"
            }
            """{"keyboard":[$body],"resize_keyboard":true,"is_persistent":true}"""
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun call(method: String, vararg params: Pair<String, Any>): JSONObject {
        val body = params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v.toString(), "UTF-8")}" }
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
        const val TAG = "TelegramBot"
        // Short enough that a full long-poll completes between DiLink's ~40 s quickboot kills
        // while parked, so the bot still answers (with up to ~poll-length latency) in keep-awake.
        const val POLL_SECONDS = 25
        const val RETRY_MS = 8_000L
        const val ALLOWED = """["message","callback_query"]"""
    }
}
