package ua.zaika.carremote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import ua.zaika.carremote.MainActivity
import ua.zaika.carremote.car.CarSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A small always-on overlay (SOC / temps / 12V) drawn over any app while the car is on, like
 * BYDMate's floating widget. Draggable; its position is remembered; a tap opens the dashboard.
 * All methods must be called on the main thread (WindowManager requires a Looper).
 */
class WidgetOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("carremote", Context.MODE_PRIVATE)
    private var view: TextView? = null
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt(KEY_X, dp(24))
        y = prefs.getInt(KEY_Y, dp(120))
    }

    fun isShown() = view != null

    fun show() {
        if (view != null) return
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xE60E1420.toInt())
                setStroke(dp(1), 0xFF2A3345.toInt())
            }
            text = "Car Remote…"
        }
        attachDrag(tv)
        runCatching { wm.addView(tv, lp) }
        view = tv
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    fun update(snap: CarSnapshot?) {
        val tv = view ?: return
        if (snap == null) { tv.text = "🚗 офлайн"; return }
        val parts = ArrayList<String>()
        snap.soc?.let { parts.add("⚡${it.roundToInt()}%") }
        snap.battTempC?.let { parts.add("🔋$it°") }
        snap.insideC?.let { parts.add("🌡$it°") }
        snap.v12?.let { parts.add("%.1fV".format(it)) }
        snap.powerKw?.let { if (abs(it) >= 0.5f) parts.add("%.0f кВт".format(it)) }
        tv.text = if (parts.isEmpty()) "—" else parts.joinToString("  ·  ")
    }

    private fun attachDrag(tv: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { wm.updateViewLayout(tv, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    if (!moved) runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object { const val KEY_X = "widget_x"; const val KEY_Y = "widget_y" }
}
