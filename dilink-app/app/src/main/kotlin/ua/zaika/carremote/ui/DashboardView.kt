package ua.zaika.carremote.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import ua.zaika.carremote.air.AirState
import ua.zaika.carremote.car.CarSnapshot
import kotlin.math.roundToInt

/**
 * The live dashboard, laid out like the factory DiLink «My car» screen: a 3×3 grid of
 * translucent tiles on the left (icon, name, state; the light tile means "on / secured"),
 * a stats card under it, and a large battery read-out on the right over a dusk-navy backdrop.
 * Tiles are tappable; [onTile] receives which one.
 */
class DashboardView(context: Context) : View(context) {

    enum class Tile { CLIMATE, LOCKS, TRUNK, WINDOWS, SUNROOF, CLEAN_AIR, CHARGING, AIR_ALERT, SETTINGS }

    var snapshot: CarSnapshot? = null
        set(value) { field = value; invalidate() }
    var air: AirState? = null
        set(value) { field = value; invalidate() }
    var onTile: ((Tile) -> Unit)? = null
    /** Height kept free under the battery read-out for the ad banner; 0 when there is no banner. */
    var bannerSpace = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Left edge of the right-hand battery column, reported so the banner can sit in it. */
    var onHeroLeft: ((Float) -> Unit)? = null
    private var heroLeft = -1f

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val tileRects = LinkedHashMap<Tile, RectF>()
    private var pressed: Tile? = null

    private val bgPaint = fill(0)
    private val glowPaint = fill(0)
    private val tileFill = fill(Ui.TILE)
    private val tileBorder = stroke(Ui.TILE_BORDER, dp(1f))
    private val icon = stroke(Ui.TEXT, dp(2f)).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val title = text(Ui.TEXT).apply { typeface = sf }
    private val sub = text(Ui.TEXT2).apply { typeface = sf }
    private val big = text(Ui.TEXT).apply { typeface = sfLight }
    private val bar = fill(Ui.GREEN)
    private val barBg = fill(0x33FFFFFF)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Dusk sky → lake: lighter blue-grey at the horizon, deep navy at the bottom.
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF2B3647.toInt(), 0xFF3A475B.toInt(), 0xFF243042.toInt(), 0xFF151C28.toInt()),
            floatArrayOf(0f, 0.38f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        glowPaint.shader = RadialGradient(
            w * 0.68f, h * 0.48f, w * 0.38f, 0x335A7AA8, 0x005A7AA8, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val snap = snapshot
        val pad = dp(18f)
        val gap = dp(14f)

        // --- Left: hint + 3×3 tiles + stats card ---
        sub.textSize = dp(15f); sub.textAlign = Paint.Align.LEFT
        canvas.drawText(if (snap == null) "Очікування даних з авто…" else "Торкніться плитки, щоб керувати", pad, pad + dp(12f), sub)

        val gridTop = pad + dp(30f)
        val statsH = dp(84f)
        val avail = h - gridTop - statsH - gap - pad
        val tileSize = minOf(avail / 3f - gap * 2f / 3f, (w * 0.42f - pad - gap * 2) / 3f)
        tileRects.clear()
        tiles(snap).forEachIndexed { i, t ->
            val x = pad + (i % 3) * (tileSize + gap)
            val y = gridTop + (i / 3) * (tileSize + gap)
            val r = RectF(x, y, x + tileSize, y + tileSize)
            tileRects[t.id] = r
            drawTile(canvas, r, t)
        }
        val gridRight = pad + tileSize * 3 + gap * 2
        val statsTop = gridTop + tileSize * 3 + gap * 3
        drawStats(canvas, RectF(pad, statsTop, gridRight, statsTop + statsH), snap)

        // --- Right: big battery read-out, with the banner's place kept free underneath ---
        val left = gridRight + pad * 2
        if (left != heroLeft) { heroLeft = left; onHeroLeft?.invoke(left) }
        drawHero(canvas, left, w - pad, h - bannerSpace, snap)
    }

    private class TileData(val id: Tile, val name: String, val state: String, val on: Boolean, val accent: Int? = null)

    private fun tiles(s: CarSnapshot?): List<TileData> {
        val a = air
        // Red for a red alert in our raion/oblast or a threat within the radius; yellow for a yellow raion alert.
        val alertAccent = when {
            a == null -> null
            a.regionLevel == "red" || a.within.isNotEmpty() || (a.oblastAlert != null && a.regionLevel != "yellow") -> Ui.RED
            a.regionLevel == "yellow" -> Ui.YELLOW
            a.regionLevel != null -> Ui.PURPLE // any other official level (e.g. nuclear / chemical)
            else -> null
        }
        val km = a?.nearestKm?.let { " · ${it.roundToInt()} км" } ?: ""
        val alertState = when {
            a == null -> "—"
            a.error != null && a.nearest == null -> "Немає даних"
            // A threat inside the radius is the reason for red, so say that rather than the alert level.
            a.within.isNotEmpty() -> "Поруч$km"
            a.regionLevel == "red" || a.oblastAlert != null -> "Тривога$km"
            a.regionLevel == "yellow" -> "Жовта$km"
            a.regionLevel != null -> "Тривога: ${a.regionLevel}$km"
            a.nearest != null -> "Далеко$km"
            else -> "Спокійно"
        }
        val windowsOpen = s?.windowsOpenPct?.any { (it ?: 0) > 0 }
        val charging = s?.bms == 1
        return listOf(
            TileData(Tile.CLIMATE, "Клімат",
                when (s?.acOn) { true -> "Увімк. ${s.acTempC?.let { "$it°" } ?: ""}".trim(); false -> "Вимкнено"; null -> "—" },
                s?.acOn == true),
            TileData(Tile.LOCKS, "Замки",
                when (s?.locked) { true -> "Зачинено"; false -> "Відчинено"; null -> "—" }, s?.locked == true,
                if (s?.locked == false) Ui.RED else null),
            TileData(Tile.TRUNK, "Багажник",
                when (s?.trunkOpen) { true -> "Відкрито"; false -> "Закрито"; null -> "—" }, false,
                if (s?.trunkOpen == true) Ui.ORANGE else null),
            TileData(Tile.WINDOWS, "Вікна",
                when (windowsOpen) { true -> "Відкриті"; false -> "Закриті"; null -> "—" }, false,
                if (windowsOpen == true) Ui.ORANGE else null),
            TileData(Tile.SUNROOF, "Люк",
                s?.sunroofPct?.let { if (it <= 0) "Закритий" else "Відкрито $it%" } ?: "—", false,
                if ((s?.sunroofPct ?: 0) > 0) Ui.ORANGE else null),
            TileData(Tile.CLEAN_AIR, "Очищення", "Очистити повітря", false),
            TileData(Tile.CHARGING, "Заряджання", s?.chargingText ?: "—", charging),
            TileData(Tile.AIR_ALERT, "Тривоги", alertState, false, alertAccent),
            TileData(Tile.SETTINGS, "Налаштування", "Car Remote", false),
        )
    }

    private fun drawTile(c: Canvas, r: RectF, t: TileData) {
        val rad = dp(16f)
        val isPressed = pressed == t.id
        tileFill.color = when {
            t.on -> Ui.TILE_ON
            t.accent == Ui.RED -> 0xB34A1A1F.toInt()
            t.accent == Ui.YELLOW -> 0xB34A4012.toInt()
            isPressed -> 0xCC3E4C62.toInt()
            else -> Ui.TILE
        }
        c.drawRoundRect(r, rad, rad, tileFill)
        if (!t.on) c.drawRoundRect(r, rad, rad, tileBorder)

        val fg = if (t.on) Ui.TILE_ON_TEXT else Ui.TEXT
        val cx = r.centerX()
        val iconSize = r.height() * 0.24f
        icon.color = t.accent ?: fg
        drawIcon(c, t.id, cx, r.top + r.height() * 0.30f, iconSize)

        title.color = fg; title.textAlign = Paint.Align.CENTER
        title.textSize = minOf(dp(17f), r.height() * 0.13f)
        c.drawText(t.name, cx, r.top + r.height() * 0.64f, title)
        sub.color = when { t.on -> 0xFF4A5468.toInt(); t.accent != null -> t.accent; else -> Ui.TEXT2 }
        sub.textAlign = Paint.Align.CENTER
        sub.textSize = minOf(dp(14f), r.height() * 0.11f)
        c.drawText(ellipsize(t.state, sub, r.width() - dp(12f)), cx, r.top + r.height() * 0.82f, sub)
        sub.color = Ui.TEXT2
    }

    private fun drawStats(c: Canvas, r: RectF, s: CarSnapshot?) {
        val rad = dp(16f)
        tileFill.color = Ui.TILE
        c.drawRoundRect(r, rad, rad, tileFill)
        c.drawRoundRect(r, rad, rad, tileBorder)
        val cells = listOf(
            (s?.mileageKm?.let { "%,.0f км".format(it).replace(',', ' ') } ?: "—") to "Пробіг",
            (s?.v12?.let { "%.1f В".format(it) } ?: "—") to "Бортова 12V",
            (s?.soh?.let { "$it%" } ?: "—") to "Здоров'я АКБ",
        )
        val cw = r.width() / cells.size
        cells.forEachIndexed { i, (value, label) ->
            val cx = r.left + cw * i + cw / 2
            title.color = Ui.TEXT; title.textAlign = Paint.Align.CENTER; title.textSize = dp(19f)
            c.drawText(value, cx, r.top + r.height() * 0.46f, title)
            sub.textAlign = Paint.Align.CENTER; sub.textSize = dp(14f)
            c.drawText(label, cx, r.top + r.height() * 0.76f, sub)
            if (i > 0) {
                val x = r.left + cw * i
                c.drawLine(x, r.top + r.height() * 0.22f, x, r.bottom - r.height() * 0.22f, tileBorder)
            }
        }
    }

    private fun drawHero(c: Canvas, left: Float, right: Float, h: Float, s: CarSnapshot?) {
        val cx = (left + right) / 2
        // Top pill, like the factory «Interior» chip.
        val temps = listOfNotNull(s?.insideC?.let { "Салон $it°" }, s?.outsideC?.let { "Надворі $it°" })
            .joinToString("   ·   ").ifEmpty { "Car Remote" }
        sub.textSize = dp(17f); sub.textAlign = Paint.Align.CENTER
        val pillW = sub.measureText(temps) + dp(44f)
        val pill = RectF(cx - pillW / 2, dp(26f), cx + pillW / 2, dp(70f))
        tileFill.color = 0x664A5A72
        c.drawRoundRect(pill, dp(22f), dp(22f), tileFill)
        title.color = Ui.TEXT; title.textAlign = Paint.Align.CENTER; title.textSize = dp(17f)
        c.drawText(temps, cx, pill.centerY() + dp(6f), title)

        val soc = s?.soc
        big.textAlign = Paint.Align.CENTER; big.textSize = minOf(dp(150f), (right - left) * 0.28f)
        val socY = h * 0.50f
        c.drawText(soc?.let { "%.0f".format(it) } ?: "—", cx - dp(20f), socY, big)
        title.textSize = dp(34f); title.textAlign = Paint.Align.LEFT
        c.drawText("%", cx - dp(20f) + big.measureText(soc?.let { "%.0f".format(it) } ?: "—") / 2 + dp(6f), socY, title)
        sub.textSize = dp(18f); sub.textAlign = Paint.Align.CENTER
        c.drawText("Заряд батареї", cx, socY + dp(38f), sub)

        // Battery bar.
        val bw = (right - left) * 0.62f
        val by = socY + dp(66f)
        val track = RectF(cx - bw / 2, by, cx + bw / 2, by + dp(12f))
        c.drawRoundRect(track, dp(6f), dp(6f), barBg)
        val frac = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
        bar.color = when { soc == null -> 0x00000000; soc >= 50 -> Ui.GREEN; soc >= 20 -> Ui.ORANGE; else -> Ui.RED }
        if (frac > 0f) c.drawRoundRect(RectF(track.left, track.top, track.left + bw * frac, track.bottom), dp(6f), dp(6f), bar)

        val details = listOfNotNull(
            "Передача ${s?.gearName ?: "—"}",
            s?.battTempC?.let { "Батарея $it°C" },
            s?.powerKw?.let { "%.1f кВт".format(it) },
        ).joinToString("    ")
        sub.textSize = dp(16f)
        c.drawText(details, cx, by + dp(48f), sub)
        val tires = s?.tiresBar?.takeIf { l -> l.any { it != null } }
            ?.joinToString("  ") { v -> v?.let { "%.1f".format(it) } ?: "—" }
        if (tires != null) c.drawText("Шини, бар:  $tires", cx, by + dp(78f), sub)
        val windows = s?.windowsOpenPct?.takeIf { l -> l.any { it != null } }
            ?.joinToString("  ") { v -> v?.toString() ?: "—" }
        if (windows != null) c.drawText("Вікна, %:  $windows", cx, by + dp(if (tires != null) 108f else 78f), sub)
    }

    /** Thin line icons in the DiLink style, drawn in a box of [s] centred on (cx, cy). */
    private fun drawIcon(c: Canvas, t: Tile, cx: Float, cy: Float, s: Float) {
        val h = s / 2
        val p = Path()
        when (t) {
            Tile.CLIMATE -> { // fan: hub + three blades
                c.drawCircle(cx, cy, s * 0.08f, icon)
                for (k in 0 until 3) {
                    val a = Math.toRadians(k * 120.0 - 90)
                    val ex = cx + (Math.cos(a) * h).toFloat(); val ey = cy + (Math.sin(a) * h).toFloat()
                    val a2 = a + Math.toRadians(55.0)
                    p.moveTo(cx, cy)
                    p.quadTo(cx + (Math.cos(a2) * h * 0.9).toFloat(), cy + (Math.sin(a2) * h * 0.9).toFloat(), ex, ey)
                }
                c.drawPath(p, icon)
            }
            Tile.LOCKS -> {
                c.drawRoundRect(RectF(cx - h * 0.8f, cy - h * 0.1f, cx + h * 0.8f, cy + h), s * 0.1f, s * 0.1f, icon)
                c.drawArc(RectF(cx - h * 0.5f, cy - h, cx + h * 0.5f, cy + h * 0.1f), 180f, 180f, false, icon)
                c.drawLine(cx - h * 0.5f, cy - h * 0.45f, cx - h * 0.5f, cy - h * 0.1f, icon)
                c.drawLine(cx + h * 0.5f, cy - h * 0.45f, cx + h * 0.5f, cy - h * 0.1f, icon)
                c.drawLine(cx, cy + h * 0.3f, cx, cy + h * 0.6f, icon)
            }
            Tile.TRUNK -> { // car tail with lifted hatch
                p.moveTo(cx - h, cy + h * 0.5f); p.lineTo(cx - h, cy - h * 0.1f)
                p.lineTo(cx - h * 0.3f, cy - h * 0.6f); p.lineTo(cx + h * 0.4f, cy - h * 0.6f)
                p.lineTo(cx + h, cy - h * 0.1f); p.lineTo(cx + h, cy + h * 0.5f); p.close()
                c.drawPath(p, icon)
                c.drawLine(cx - h * 0.6f, cy + h * 0.15f, cx + h * 0.6f, cy + h * 0.15f, icon)
            }
            Tile.WINDOWS -> {
                p.moveTo(cx - h, cy + h * 0.6f); p.lineTo(cx - h, cy - h * 0.1f)
                p.lineTo(cx - h * 0.2f, cy - h * 0.7f); p.lineTo(cx + h, cy - h * 0.7f)
                p.lineTo(cx + h, cy + h * 0.6f); p.close()
                c.drawPath(p, icon)
                c.drawLine(cx - h * 0.2f, cy + h * 0.05f, cx + h * 0.3f, cy - h * 0.25f, icon)
            }
            Tile.SUNROOF -> {
                c.drawRoundRect(RectF(cx - h, cy - h * 0.7f, cx + h, cy + h * 0.7f), s * 0.12f, s * 0.12f, icon)
                c.drawRoundRect(RectF(cx - h * 0.55f, cy - h * 0.35f, cx + h * 0.55f, cy + h * 0.35f), s * 0.06f, s * 0.06f, icon)
            }
            Tile.CLEAN_AIR -> { // three breeze lines
                for (k in -1..1) {
                    val y = cy + k * h * 0.55f
                    p.moveTo(cx - h, y); p.quadTo(cx - h * 0.3f, y - h * 0.3f, cx + h * 0.2f, y)
                    p.quadTo(cx + h * 0.6f, y + h * 0.25f, cx + h, y - h * 0.1f)
                }
                c.drawPath(p, icon)
            }
            Tile.CHARGING -> { // bolt
                p.moveTo(cx + h * 0.15f, cy - h); p.lineTo(cx - h * 0.55f, cy + h * 0.1f)
                p.lineTo(cx, cy + h * 0.1f); p.lineTo(cx - h * 0.15f, cy + h)
                p.lineTo(cx + h * 0.55f, cy - h * 0.1f); p.lineTo(cx, cy - h * 0.1f); p.close()
                c.drawPath(p, icon)
            }
            Tile.AIR_ALERT -> { // shield
                p.moveTo(cx, cy - h); p.lineTo(cx + h * 0.8f, cy - h * 0.6f)
                p.quadTo(cx + h * 0.8f, cy + h * 0.5f, cx, cy + h)
                p.quadTo(cx - h * 0.8f, cy + h * 0.5f, cx - h * 0.8f, cy - h * 0.6f); p.close()
                c.drawPath(p, icon)
                c.drawLine(cx, cy - h * 0.35f, cx, cy + h * 0.15f, icon)
                c.drawPoint(cx, cy + h * 0.45f, icon)
            }
            Tile.SETTINGS -> { // gear: ring + teeth
                c.drawCircle(cx, cy, h * 0.3f, icon)
                c.drawCircle(cx, cy, h * 0.65f, icon)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    c.drawLine(cx + (Math.cos(a) * h * 0.65).toFloat(), cy + (Math.sin(a) * h * 0.65).toFloat(),
                        cx + (Math.cos(a) * h * 0.95).toFloat(), cy + (Math.sin(a) * h * 0.95).toFloat(), icon)
                }
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = tileRects.entries.firstOrNull { it.value.contains(e.x, e.y) }?.key
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate(); return hit != null }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null; invalidate()
                if (p != null && p == hit) { performClick(); onTile?.invoke(p) }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return super.onTouchEvent(e)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun ellipsize(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.length > 1 && p.measureText("$t…") > max) t = t.dropLast(1)
        return "$t…"
    }

    private fun stroke(c: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = width
    }
    private fun fill(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.FILL }
    private fun text(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
}
