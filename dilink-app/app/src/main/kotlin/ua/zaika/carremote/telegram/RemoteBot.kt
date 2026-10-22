package ua.zaika.carremote.telegram

/** Telegram transport and the token-free hub share the existing car command logic. */
interface RemoteBot {
    suspend fun run(onStatus: (String) -> Unit, onConnected: () -> Unit)
    fun send(chatId: Long, reply: Reply)
    fun send(chatId: Long, text: String) = send(chatId, Reply(text))
}
