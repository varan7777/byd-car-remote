package ua.zaika.carremote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import ua.zaika.carremote.car.CarSnapshot

/**
 * Single-screen live dashboard, drawn on a Canvas in an Apple-flavoured style: a SOC ring that
 * takes the system accent colour by charge level, over a soft vertical-gradient background, with
 * a grid of rounded cards each carrying its own accent dot and a big value.
 */
class DashboardView(context: Context) : View(context) {

    var snapshot: CarSnapshot? = null
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val light = Ui.isLight(context)
    private val cMain = if (light) 0xFF000000.toInt() else 0xFFF2F5FA.toInt()
    private val cMuted = if (light) 0xFF8E8E93.toInt() else 0xFF8A97AD.toInt()
    private val cTile = if (light) 0xFFFFFFFF.toInt() else 0xFF1A2230.toInt()
    private val cBorder = if (light) 0x0F000000 else 0x14FFFFFF
    private val cArcBg = if (light) 0xFFE3E3E8.toInt() else 0xFF2A3345.toInt()

    // Apple system palette (dark).
    private val blue = 0xFF0A84FF.toInt()
    private val green = 0xFF30D158.toInt()
    private val indigo = 0xFF5E5CE6.toInt()
    private val orange = 0xFFFF9F0A.toInt()
    private val red = 0xFFFF453A.toInt()
    private val teal = 0xFF64D2FF.toInt()
    private val purple = 0xFFBF5AF2.toInt()
    private val yellow = 0xFFFFD60A.toInt()
    private val pink = 0xFFFF375F.toInt()
    private val mint = 0xFF63E6E2.toInt()

    private val arcBg = stroke(cArcBg, dp(16f))
    private val arcFg = stroke(green, dp(16f)).apply { strokeCap = Paint.Cap.ROUND }
    private val tileBg = fill(cTile)
    private val tileBorder = stroke(cBorder, dp(1f))
    private val dot = fill(green)
    private val textMain = text(cMain).apply { typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val textValue = text(cMain).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val textMuted = text(cMuted)
    private var bgPaint: Paint? = null

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val top = if (light) 0xFFF2F2F7.toInt() else 0xFF0E1420.toInt()
        val bot = if (light) 0xFFFFFFFF.toInt() else 0xFF151D2C.toInt()
        bgPaint = fill(0).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bot, Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = dp(14f)
        val leftW = w * 0.36f

        bgPaint?.let { canvas.drawRect(0f, 0f, w, h, it) }

        val snap = snapshot
        if (snap == null) {
            textMuted.textSize = dp(17f); textMuted.textAlign = Paint.Align.CENTER
            canvas.drawText("Очікування даних з авто…", w / 2, h / 2, textMuted)
            return
        }

        // --- SOC ring (left), coloured by level ---
        val cx = leftW / 2
        val cy = h * 0.40f
        val r = minOf(leftW, h) * 0.34f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val soc = snap.soc ?: 0f
        val socColor = when { soc >= 50 -> green; soc >= 20 -> orange; else -> red }
        arcFg.color = socColor
        canvas.drawArc(oval, 135f, 270f, false, arcBg)
        canvas.drawArc(oval, 135f, 270f * (soc / 100f).coerceIn(0f, 1f), false, arcFg)

        textMain.textAlign = Paint.Align.CENTER
        textMain.textSize = dp(52f)
        canvas.drawText(snap.soc?.let { "%.0f".format(it) } ?: "—", cx, cy + dp(16f), textMain)
        textMuted.textAlign = Paint.Align.CENTER; textMuted.textSize = dp(15f)
        canvas.drawText("SOC %", cx, cy + dp(42f), textMuted)

        textValue.textAlign = Paint.Align.CENTER; textValue.textSize = dp(21f)
        canvas.drawText(snap.mileageKm?.let { "%.0f км".format(it) } ?: "—", cx, cy + r + dp(46f), textValue)
        textMuted.textSize = dp(15f)
        val temps = listOfNotNull(
            snap.insideC?.let { "салон $it°" },
            snap.outsideC?.let { "надворі $it°" },
        ).joinToString("   ")
        if (temps.isNotEmpty()) canvas.drawText(temps, cx, cy + r + dp(74f), textMuted)

        // --- Cards (right) ---
        val battHot = (snap.battTempC ?: 0) >= 45
        val unlocked = snap.locked == false
        val tiles = listOf(
            Triple("Здоров'я батареї", snap.soh?.let { "$it%" } ?: "—", green),
            Triple("Темп. батареї", snap.battTempC?.let { "$it°C" } ?: "—", if (battHot) red else orange),
            Triple("Бортова 12V", snap.v12?.let { "%.1f В".format(it) } ?: "—", blue),
            Triple("Потужність", snap.powerKw?.let { "%.1f кВт".format(it) } ?: "—", purple),
            Triple("Замок", snap.locked?.let { if (it) "зачинено" else "відчинено" } ?: "—", if (unlocked) red else teal),
            Triple("Передача", snap.gearName, indigo),
            Triple("Зарядка", snap.chargingText, mint),
            Triple("Вікна", windowsText(snap.windowsOpenPct), yellow),
            Triple("Люк", snap.sunroofPct?.let { if (it <= 0) "закритий" else "$it%" } ?: "—", pink),
            Triple("Шини, бар", tiresText(snap.tiresBar), teal),
            Triple("Повітря PM2.5", snap.pm25In?.let { "$it" } ?: "н/д", mint),
        )
        val cols = 2
        val rows = (tiles.size + cols - 1) / cols
        val gridX = leftW
        val gridW = w - leftW - pad
        val tileW = (gridW - pad * (cols - 1)) / cols
        val tileH = (h - pad * (rows + 1)) / rows
        val radius = dp(16f)
        tiles.forEachIndexed { idx, (label, value, accent) ->
            val c = idx % cols
            val row = idx / cols
            val x = gridX + c * (tileW + pad)
            val y = pad + row * (tileH + pad)
            val rect = RectF(x, y, x + tileW, y + tileH)
            canvas.drawRoundRect(rect, radius, radius, tileBg)
            canvas.drawRoundRect(rect, radius, radius, tileBorder)
            // Accent dot.
            dot.color = accent
            canvas.drawCircle(x + dp(16f), y + dp(16f), dp(4.5f), dot)
            // Label (SF subhead, secondary grey).
            textMuted.textAlign = Paint.Align.LEFT
            textMuted.textSize = minOf(dp(13f), tileH * 0.22f)
            canvas.drawText(label, x + dp(30f), y + dp(22f), textMuted)
            // Value — big, in the tile's accent colour.
            val maxVal = when { value.length > 22 -> dp(19f); value.length > 10 -> dp(26f); else -> dp(34f) }
            val labelBottom = y + dp(22f)
            val room = (y + tileH - dp(12f)) - (labelBottom + dp(2f))
            textValue.textAlign = Paint.Align.LEFT
            textValue.color = accent
            textValue.textSize = maxVal.coerceAtMost(room).coerceAtLeast(dp(10f))
            canvas.drawText(value, x + dp(15f), y + tileH - dp(13f), textValue)
        }
        textValue.color = cMain // restore for the mileage/temps text next frame
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
