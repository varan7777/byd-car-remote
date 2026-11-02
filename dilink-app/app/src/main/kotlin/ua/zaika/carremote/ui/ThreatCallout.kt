package ua.zaika.carremote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A speech-bubble card pinned to a threat marker on the map: sits above the marker (or below it
 * near the top edge) with a small pointer aimed at it, and follows it as the map moves.
 * Lives in the same FrameLayout as the MapView, so marker pixels are its parent's pixels.
 */
class ThreatCallout(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {

    private val bubble = Bubble(Ui.card(context), context.dpf(18f), context.dpf(12f))
    private val title = TextView(context).apply { setTextColor(Ui.textc(context)); textSize = 19f; typeface = sfSemibold }
    private val note = TextView(context).apply { setTextColor(Ui.text2c(context)); textSize = 14f; typeface = sf }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }

    /** Id of the threat the bubble points at, or null when hidden. */
    var threatId: String? = null
        private set

    init {
        orientation = VERTICAL
        background = bubble
        elevation = context.dpf(8f)
        visibility = View.GONE
        isClickable = true // taps on the card must not fall through to the map
        setPadding(context.dpv(18), context.dpv(12), context.dpv(10), context.dpv(30))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = "✕"; setTextColor(Ui.text2c(context)); textSize = 18f; gravity = Gravity.CENTER
                contentDescription = "Закрити"
                setOnClickListener { onClose() }
            }, LayoutParams(context.dpv(40), context.dpv(40)))
        })
        addView(note)
        addView(rows)
    }

    fun show(id: String, label: String, explanation: String, lines: List<Pair<String, String>>) {
        threatId = id
        title.text = label
        note.text = explanation
        note.visibility = if (explanation.isBlank()) View.GONE else View.VISIBLE
        rows.removeAllViews()
        lines.forEach { (k, v) ->
            rows.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL; setPadding(0, context.dpv(3), 0, 0)
                addView(TextView(context).apply { text = k; setTextColor(Ui.text2c(context)); textSize = 14f; typeface = sf },
                    LayoutParams(context.dpv(120), WRAP))
                addView(TextView(context).apply { text = v; setTextColor(Ui.textc(context)); textSize = 14f; typeface = sf },
                    LayoutParams(0, WRAP, 1f))
            })
        }
        visibility = View.VISIBLE
        requestLayout()
    }

    fun hide() {
        threatId = null
        visibility = View.GONE
    }

    /** Aim the pointer at marker pixel ([x], [y]) of the parent; hide while it's off the map. */
    fun pointAt(x: Float, y: Float) {
        val parent = parent as? View ?: return
        val pw = parent.width; val ph = parent.height
        if (width == 0 || x < 0 || y < 0 || x > pw || y > ph) { alpha = 0f; return }
        alpha = 1f
        val margin = context.dpf(8f)
        val gap = context.dpf(16f) // clear of the marker glyph
        val above = y - gap - height >= margin
        val left = (x - width / 2f).coerceIn(margin, (pw - width - margin).coerceAtLeast(margin))
        val top = if (above) y - gap - height else y + gap
        translationX = left
        translationY = top
        bubble.aim(x - left, above)
    }

    /** Rounded card with a triangular pointer on its bottom (or top) edge. */
    private inner class Bubble(color: Int, private val radius: Float, private val arrow: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = Path()
        private var arrowX = 0f
        private var down = true

        fun aim(x: Float, pointDown: Boolean) {
            if (x == arrowX && pointDown == down) return
            arrowX = x; down = pointDown
            // Keep the content clear of the pointer, whichever edge it is on.
            val p = context.dpv(18); val a = arrow.toInt()
            setPadding(p, p - context.dpv(6) + if (down) 0 else a, context.dpv(10), p + if (down) a else 0)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val body = RectF(b.left.toFloat(), b.top + if (down) 0f else arrow, b.right.toFloat(), b.bottom - if (down) arrow else 0f)
            path.reset()
            path.addRoundRect(body, radius, radius, Path.Direction.CW)
            val ax = arrowX.coerceIn(body.left + radius + arrow, body.right - radius - arrow)
            if (down) {
                path.moveTo(ax - arrow, body.bottom); path.lineTo(ax, b.bottom.toFloat()); path.lineTo(ax + arrow, body.bottom)
            } else {
                path.moveTo(ax - arrow, body.top); path.lineTo(ax, b.top.toFloat()); path.lineTo(ax + arrow, body.top)
            }
            path.close()
            canvas.drawPath(path, paint)
        }

        override fun getOutline(outline: android.graphics.Outline) {
            @Suppress("DEPRECATION") outline.setConvexPath(Path().apply {
                addRoundRect(RectF(bounds.left.toFloat(), bounds.top + if (down) 0f else arrow,
                    bounds.right.toFloat(), bounds.bottom - if (down) arrow else 0f), radius, radius, Path.Direction.CW)
            })
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
