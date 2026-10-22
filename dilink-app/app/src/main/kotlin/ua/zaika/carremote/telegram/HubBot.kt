package ua.zaika.carremote.telegram

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One owner, one authenticated car. No Telegram bot token is distributed in this transport. */
class HubBot(
    private val prefs: SharedPreferences,
    private val handle: (Long, String) -> Reply?,
) : RemoteBot {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()

    override suspend fun run(onStatus: (String) -> Unit, onConnected: () -> Unit) {
        var backoff = 2_000L
        try {
            while (currentCoroutineContext().isActive) {
                val token = prefs.getString(TOKEN, "").orEmpty()
                val code = prefs.getString(CODE, "").orEmpty()
                if (token.isBlank() && !code.matches(Regex("[0-9]{6}"))) {
                    onStatus("Hub: введіть код прив’язки у налаштуваннях")
                    delay(3_000); continue
                }
                val events = Channel<Pair<String, Long>>(32)
                ready = false
                onStatus("Hub: підключення…")
                socket = client.newWebSocket(Request.Builder().url("wss://varan.team/car").build(), object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        val auth = if (token.isNotBlank()) JSONObject().put("type", "hello").put("carToken", token)
                            else JSONObject().put("type", "pair").put("code", code)
                        ws.send(auth.toString())
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        if (text.length > 32768 || !events.trySend(text to SystemClock.elapsedRealtime()).isSuccess) {
                            ws.close(1008, "Message limit"); events.close()
                        }
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) { events.close() }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { events.close() }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, null); events.close() }
                })
                try {
                    coroutineScope {
                        val heartbeat = launch { while (isActive) { delay(20_000); if (ready) socket?.send("{\"type\":\"ping\"}") } }
                        try {
                            for ((raw, receivedAt) in events) {
                                val msg = JSONObject(raw)
                                when (msg.optString("type")) {
                                    "paired", "ready" -> {
                                        val owner = msg.optLong("chatId")
                                        if (owner <= 0) throw IOException("Invalid owner")
                                        val edit = prefs.edit().putLong(OWNER, owner).remove(CODE)
                                        if (msg.has("carToken")) edit.putString(TOKEN, msg.getString("carToken"))
                                        edit.apply(); ready = true; backoff = 2_000
                                        onStatus("Hub: онлайн"); onConnected()
                                    }
                                    "config" -> {
                                        if (!ready) continue
                                        // Cached for the foreground banner only; payment rights are authoritative on the server.
                                        prefs.edit().putString(CONFIG, msg.toString()).apply()
                                    }
                                    "cmd" -> {
                                        if (!ready) continue
                                        val input = msg.optString("text")
                                        if (input.length > 1000) continue
                                        if (SystemClock.elapsedRealtime() - receivedAt > 15_000) {
                                            sendReply(Reply("Команда прострочена й не виконана. Надішліть її ще раз."), msg.optString("requestId"))
                                            continue
                                        }
                                        val reply = runCatching { handle(prefs.getLong(OWNER, 0), input) }
                                            .getOrElse { Reply("Помилка виконання команди. Спробуйте ще раз.") }
                                        if (reply != null) sendReply(reply, msg.optString("requestId"))
                                    }
                                    "pair_failed" -> {
                                        prefs.edit().remove(CODE).apply()
                                        onStatus("Hub: код неправильний або прострочений"); events.close()
                                    }
                                    "unauthorized" -> {
                                        prefs.edit().remove(CONFIG).apply()
                                        onStatus("Hub: доступ відкликано або тимчасово заблоковано"); events.close()
                                    }
                                }
                            }
                        } finally { heartbeat.cancel() }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { onStatus("Hub: зв’язок перервано") }
                finally { ready = false; socket?.cancel(); socket = null; events.cancel() }
                delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
            }
        } finally {
            ready = false; socket?.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    override fun send(chatId: Long, reply: Reply) {
        if (chatId != prefs.getLong(OWNER, 0)) throw IOException("Owner mismatch")
        sendReply(reply)
    }
    private fun sendReply(reply: Reply, requestId: String? = null) {
        if (!ready) throw IOException("Hub offline")
        val msg = JSONObject().put("type", "reply").put("text", reply.text.take(4000))
        if (!requestId.isNullOrBlank()) msg.put("requestId", requestId)
        when (val k = reply.keyboard) {
            is InlineKeyboard -> msg.put("keyboard", JSONObject().put("inline_keyboard", JSONArray().apply {
                k.rows.forEach { row -> put(JSONArray().apply { row.forEach { put(JSONObject().put("text", it.label).put("callback_data", it.data)) } }) }
            }))
            is ReplyKeyboard -> msg.put("keyboard", JSONObject().put("keyboard", JSONArray().apply {
                k.rows.forEach { put(JSONArray(it)) }
            }).put("resize_keyboard", true).put("is_persistent", true))
            null -> Unit
        }
        if (socket?.send(msg.toString()) != true) throw IOException("Hub offline")
    }
    companion object {
        const val TOKEN = "hub_car_token"
        const val OWNER = "hub_owner"
        const val CODE = "hub_pair_code"
        const val CONFIG = "hub_config"
        const val ENABLED = "hub_enabled"
    }
}
