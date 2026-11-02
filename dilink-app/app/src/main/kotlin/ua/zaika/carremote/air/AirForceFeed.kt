package ua.zaika.carremote.air

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Latest posts of the Air Force of Ukraine channel (t.me/s/kpszsu, its public web preview) — the
 * source NEPTUN itself aggregates, but first-hand and minutes earlier, and it says «реактивний»
 * where NEPTUN just says «БпЛА». [AirForceParser] turns the posts into positions.
 *
 * Fetched on its own thread at most every 45 s, so a slow Telegram never delays the alarm poll.
 */
class AirForceFeed(private val url: String = "https://t.me/s/kpszsu") {

    /** Posts from the last 40 minutes, oldest first. */
    @Volatile var posts: List<AirForceParser.Post> = emptyList()
        private set
    @Volatile private var fetchedMs = 0L
    private val busy = AtomicBoolean(false)

    /** Starts a background refresh when one is due; returns at once. */
    fun refreshAsync() {
        val now = System.currentTimeMillis()
        if (now - fetchedMs < 45_000 || !busy.compareAndSet(false, true)) return
        fetchedMs = now
        Thread {
            try { runCatching { get() }.getOrNull()?.let { posts = parse(it, System.currentTimeMillis()) } }
            finally { busy.set(false) }
        }.apply { isDaemon = true }.start()
    }

    private fun get(): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) CarRemote")
        }
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    companion object {
        // One message: its id, then its OWN text div (a reply's quoted parent uses
        // «js-message_reply_text» and is skipped), then its time.
        private val MESSAGE = Regex(
            """data-post="([^"]+)".*?class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>.*?<time datetime="([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** Plain-text posts from the preview page, newest last, within the last 40 minutes. */
        fun parse(html: String, now: Long): List<AirForceParser.Post> = MESSAGE.findAll(html).mapNotNull { m ->
            val at = AirThreat.parseIso(m.groupValues[3].replace(Regex("\\+00:00$"), "Z")) ?: return@mapNotNull null
            if (now - at > 40 * 60_000) return@mapNotNull null
            // Html.fromHtml turns <br> into line breaks itself (a literal \n would collapse to a space).
            val text = android.text.Html.fromHtml(m.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
            AirForceParser.Post(m.groupValues[1], at, text)
        }.toList()
    }
}
