package ua.zaika.carremote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import ua.zaika.carremote.car.CarSnapshot

/** A single-screen live dashboard drawn on a Canvas: a SOC arc plus a grid of value tiles. */
class DashboardView(context: Context) : View(context) {

    var snapshot: CarSnapshot? = null
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val arcBg = stroke(0xFF2A3345.toInt(), dp(14f))
    private val arcFg = stroke(0xFF39D98A.toInt(), dp(14f)).apply { strokeCap = Paint.Cap.ROUND }
    private val tileBg = fill(0xFF19202E.toInt())
    private val textMain = text(0xFFE6EDF5.toInt())
    private val textMuted = text(0xFF8A97AD.toInt())
    private val textAccent = text(0xFF39D98A.toInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(16f)
        val leftW = w * 0.36f

        val snap = snapshot
        if (snap == null) {
            textMuted.textSize = dp(18f)
            textMuted.textAlign = Paint.Align.CENTER
            canvas.drawText("Очікування даних з авто…", w / 2, h / 2, textMuted)
            return
        }

        // --- SOC arc (left) ---
        val cx = leftW / 2
        val cy = h * 0.40f
        val r = minOf(leftW, h) * 0.32f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(oval, 135f, 270f, false, arcBg)
        val soc = snap.soc ?: 0f
        canvas.drawArc(oval, 135f, 270f * (soc / 100f), false, arcFg)

        textMain.textAlign = Paint.Align.CENTER
        textMain.textSize = dp(48f)
        canvas.drawText(snap.soc?.let { "%.0f".format(it) } ?: "—", cx, cy + dp(16f), textMain)
        textMuted.textAlign = Paint.Align.CENTER
        textMuted.textSize = dp(16f)
        canvas.drawText("SOC %", cx, cy + dp(44f), textMuted)

        // Mileage + temperatures under the arc.
        textMain.textSize = dp(20f)
        canvas.drawText(snap.mileageKm?.let { "%.0f км".format(it) } ?: "—", cx, cy + r + dp(44f), textMain)
        textMuted.textSize = dp(16f)
        val temps = listOfNotNull(
            snap.insideC?.let { "салон $it°" },
            snap.outsideC?.let { "надворі $it°" },
        ).joinToString("   ")
        if (temps.isNotEmpty()) canvas.drawText(temps, cx, cy + r + dp(72f), textMuted)

        // --- Tiles (right) ---
        val tiles = listOf(
            "Здоров'я батареї" to (snap.soh?.let { "$it%" } ?: "—"),
            "Темп. батареї" to (snap.battTempC?.let { "$it°C" } ?: "—"),
            "Бортова 12V" to (snap.v12?.let { "%.1f В".format(it) } ?: "—"),
            "Потужність" to (snap.powerKw?.let { "%.1f кВт".format(it) } ?: "—"),
            "Замок" to (snap.locked?.let { if (it) "зачинено" else "відчинено" } ?: "—"),
            "Передача" to snap.gearName,
            "Зарядка" to snap.chargingText,
            "Вікна" to windowsText(snap.windowsOpenPct),
            "Люк" to (snap.sunroofPct?.let { if (it <= 0) "закритий" else "$it%" } ?: "—"),
            "Шини, бар" to tiresText(snap.tiresBar),
        )
        val cols = 2
        val rows = (tiles.size + cols - 1) / cols
        val gridX = leftW
        val gridW = w - leftW - pad
        val tileW = (gridW - pad * (cols - 1)) / cols
        val tileH = (h - pad * (rows + 1)) / rows
        tiles.forEachIndexed { idx, (label, value) ->
            val c = idx % cols
            val row = idx / cols
            val x = gridX + c * (tileW + pad)
            val y = pad + row * (tileH + pad)
            val rect = RectF(x, y, x + tileW, y + tileH)
            canvas.drawRoundRect(rect, dp(12f), dp(12f), tileBg)
            textMuted.textAlign = Paint.Align.LEFT
            textMuted.textSize = dp(14f)
            canvas.drawText(label, x + dp(14f), y + dp(24f), textMuted)
            val big = when { value.length > 22 -> dp(15f); value.length > 10 -> dp(19f); else -> dp(26f) }
            textAccent.textAlign = Paint.Align.LEFT
            textAccent.textSize = big
            canvas.drawText(value, x + dp(14f), y + tileH - dp(16f), textAccent)
        }
    }

    private fun tiresText(bar: List<Float?>): String {
        if (bar.all { it == null }) return "—"
        val labels = listOf("ПЛ", "ПП", "ЗЛ", "ЗП")
        return bar.mapIndexed { i, v -> "${labels[i]} ${v?.let { "%.1f".format(it) } ?: "—"}" }.joinToString(" · ")
    }

    private fun windowsText(w: List<Int?>): String {
        if (w.all { it == null }) return "—"
        val short = listOf("вод", "пас", "ЗЛ", "ЗП")
        val open = w.mapIndexedNotNull { i, p -> if (p != null && p > 0) "${short[i]} $p%" else null }
        return if (open.isEmpty()) "закриті" else open.joinToString(", ")
    }

    private fun stroke(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = width
    }
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
    private fun text(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
}
