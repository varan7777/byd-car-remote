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
        // Each message is its own block; inside it, its OWN text div (a reply's quoted parent uses
        // «js-message_reply_text» and is skipped) and its time. A photo-only post has no text and is skipped.
        private val BLOCK = Regex("""data-post="([^"]+)"""")
        private val TEXT = Regex("""class="tgme_widget_message_text js-message_text"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        private val TIME = Regex("""<time datetime="([^"]+)"""")

        /** Plain-text posts from the preview page, oldest first, within the last three hours. */
        fun parse(html: String, now: Long): List<AirForceParser.Post> {
            val starts = BLOCK.findAll(html).toList()
            return starts.mapIndexedNotNull { i, m ->
                val block = html.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: html.length)
                val text = TEXT.find(block)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val at = TIME.find(block)?.groupValues?.get(1)?.replace(Regex("\\+00:00$"), "Z")
                    ?.let { AirThreat.parseIso(it) } ?: return@mapIndexedNotNull null
                // Generous on purpose: the head unit's clock may be off; the monitor ages posts by NEPTUN's clock.
                if (now - at > 3 * 3_600_000) return@mapIndexedNotNull null
                // Html.fromHtml turns <br> into line breaks itself (a literal \n would collapse to a space).
                val plain = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                AirForceParser.Post(m.groupValues[1], at, plain)
            }
        }
    }
}
