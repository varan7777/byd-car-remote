package ua.zaika.carremote.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.BitmapFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import ua.zaika.carremote.telegram.HubBot

/** Small in-app placement. Never overlays vehicle controls, navigation, cameras or alerts. */
class AdBanner(context: Context) : LinearLayout(context) {
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private var imageUrl: String? = null
    private var imageCall: Call? = null
    private val title = TextView(context).apply {
        setTextColor(Ui.textc(context)); textSize = 12f; typeface = sfSemibold
        maxLines = 2
    }
    private val remove = TextView(context).apply {
        text = "Без реклами назавжди ↗"; setTextColor(Ui.ORANGE); textSize = 11f; typeface = sfSemibold
        setPadding(context.dpv(12), context.dpv(8), 0, context.dpv(8))
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpv(13), context.dpv(6), context.dpv(13), context.dpv(6)); background = context.rounded(Ui.card(context), 10)
        addView(image, LayoutParams(context.dpv(42), context.dpv(42)).apply { marginEnd = context.dpv(10) })
        addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(remove)
        visibility = View.GONE
    }
    fun render(prefs: SharedPreferences) {
        val config = runCatching { JSONObject(prefs.getString(HubBot.CONFIG, "").orEmpty()) }.getOrNull()
        val banner = config?.optJSONObject("banner")
        val now = System.currentTimeMillis()
        if (config == null || config.optBoolean("adFree") || banner == null || !banner.optBoolean("enabled") ||
            (banner.optLong("startsAt") > now) || (banner.optLong("endsAt") != 0L && banner.optLong("endsAt") < now)) {
            imageCall?.cancel(); imageUrl = null; visibility = View.GONE; return
        }
        title.text = "Реклама · ${banner.optString("title")}\n${banner.optString("text")}"
        title.setOnClickListener { open(banner.optString("url")) }
        remove.setOnClickListener { open(config.optString("purchaseUrl")) }
        loadImage(banner.optString("imageUrl"))
        visibility = View.VISIBLE
    }
    private fun loadImage(path: String) {
        val url = if (path.startsWith("/") && !path.startsWith("//")) "https://varan.team$path" else path
        if (url == imageUrl) return
        imageCall?.cancel(); imageUrl = url; image.visibility = View.GONE
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) return
        imageCall = client.newCall(Request.Builder().url(url).build()).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) return
                        val body = it.body ?: return
                        if (body.contentLength() > 512 * 1024) return
                        val data = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                if (data.size() + n > 512 * 1024) return
                                data.write(buffer, 0, n)
                            }
                        }
                        val bytes = data.toByteArray()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return
                        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return
                        post { if (imageUrl == url) { image.setImageBitmap(bitmap); image.visibility = View.VISIBLE } }
                    }
                }
            })
        }
    }
    override fun onDetachedFromWindow() { imageCall?.cancel(); imageUrl = null; super.onDetachedFromWindow() }
    private fun open(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
