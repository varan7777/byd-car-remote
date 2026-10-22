package ua.zaika.carremote.telegram

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One owner, one authenticated car. No Telegram bot token is distributed in this transport.
 * varan.team runs on PHP hosting without long-lived sockets, so the car asks the hub for
 * commands (the hub holds each request up to ~12 s) and posts replies back over HTTPS.
 */
class HubBot(
    private val prefs: SharedPreferences,
    private val handle: (Long, String) -> Reply?,
) : RemoteBot {
    @Volatile private var ready = false
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()
    private val json = "application/json; charset=utf-8".toMediaType()

    private class Unauthorized : IOException("Unauthorized")

    private fun post(path: String, body: JSONObject, token: String? = null): JSONObject {
        val req = Request.Builder().url("$BASE$path").post(body.toString().toRequestBody(json))
        if (token != null) req.header("Authorization", "Bearer $token")
        client.newCall(req.build()).execute().use { r ->
            if (r.code == 401) throw Unauthorized()
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful || text.length > 65536) throw IOException("Hub HTTP ${r.code}")
            return JSONObject(text)
        }
    }

    private fun saveConfig(config: JSONObject?) {
        // Cached for the foreground banner only; payment rights are authoritative on the server.
        if (config != null) prefs.edit().putString(CONFIG, config.toString()).apply()
    }

    override suspend fun run(onStatus: (String) -> Unit, onConnected: () -> Unit) {
        var backoff = 2_000L
        var announced = false
        try {
            while (currentCoroutineContext().isActive) {
                val token = prefs.getString(TOKEN, "").orEmpty()
                val code = prefs.getString(CODE, "").orEmpty()
                try {
                    if (code.matches(Regex("[0-9]{6}"))) {
                        // A new code (first pairing or re-pairing): the old token stays until the hub accepts it.
                        onStatus("Hub: прив’язка…")
                        val msg = post("/api/car/pair", JSONObject().put("code", code))
                        if (msg.optString("type") != "paired") {
                            prefs.edit().putString(PAIR_RESULT, "failed").remove(CODE).apply()
                            onStatus(if (token.isBlank()) "Hub: код неправильний або прострочений" else "Hub: новий код не підійшов, лишаю попередню прив’язку")
                            continue
                        }
                        val owner = msg.optLong("chatId")
                        if (owner <= 0) throw IOException("Invalid owner")
                        prefs.edit().putString(PAIR_RESULT, "ok").putLong(OWNER, owner)
                            .putString(TOKEN, msg.getString("carToken")).remove(CODE).apply()
                        saveConfig(msg.optJSONObject("config"))
                        announced = false
                        continue
                    }
                    if (token.isBlank()) {
                        onStatus("Hub: введіть код прив’язки у налаштуваннях")
                        delay(3_000); continue
                    }
                    val msg = post("/api/car/poll", JSONObject().put("wait", 12), token)
                    val owner = msg.optLong("chatId")
                    if (owner <= 0) throw IOException("Invalid owner")
                    if (prefs.getLong(OWNER, 0) != owner) prefs.edit().putLong(OWNER, owner).apply()
                    saveConfig(msg.optJSONObject("config"))
                    ready = true; backoff = 2_000
                    if (!announced) { announced = true; onStatus("Hub: онлайн"); onConnected() }
                    val cmds = msg.optJSONArray("cmds") ?: JSONArray()
                    for (i in 0 until cmds.length()) {
                        val cmd = cmds.optJSONObject(i) ?: continue
                        val input = cmd.optString("text")
                        if (input.isEmpty() || input.length > 1000) continue
                        val reply = runCatching { handle(owner, input) }
                            .getOrElse { Reply("Помилка виконання команди. Спробуйте ще раз.") }
                        if (reply != null) runCatching { sendReply(reply, cmd.optString("requestId")) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Unauthorized) {
                    ready = false; announced = false
                    prefs.edit().remove(CONFIG).apply()
                    onStatus("Hub: доступ відкликано або тимчасово заблоковано")
                    delay(60_000)
                } catch (_: Exception) {
                    if (ready || announced) onStatus("Hub: зв’язок перервано")
                    ready = false; announced = false
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000)
                }
            }
        } finally {
            ready = false; client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    override fun send(chatId: Long, reply: Reply) {
        if (chatId != prefs.getLong(OWNER, 0)) throw IOException("Owner mismatch")
        sendReply(reply)
    }
    private fun sendReply(reply: Reply, requestId: String? = null) {
        val token = prefs.getString(TOKEN, "").orEmpty()
        if (!ready || token.isBlank()) throw IOException("Hub offline")
        val msg = JSONObject().put("text", reply.text.take(4000))
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
        post("/api/car/reply", msg, token)
    }
    companion object {
        private const val BASE = "https://varan.team"
        const val TOKEN = "hub_car_token"
        const val OWNER = "hub_owner"
        const val CODE = "hub_pair_code"
        const val CONFIG = "hub_config"
        const val ENABLED = "hub_enabled"
        const val PAIR_RESULT = "hub_pair_result"
    }
}
