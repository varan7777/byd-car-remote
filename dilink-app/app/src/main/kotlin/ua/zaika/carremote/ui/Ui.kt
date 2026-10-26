package ua.zaika.carremote.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/**
 * Shared design tokens in the head unit's own DiLink 5 style (as in the factory «My car» and
 * Settings apps): a near-black navy background, translucent blue-grey tiles with a hairline
 * border, a light "selected" tile with dark text, and a calm blue accent.
 */
object Ui {
    const val BG = 0xFF0D121A.toInt()
    const val CARD = 0xFF1A2230.toInt()
    const val CARD_HI = 0xFF2A3442.toInt()
    const val SEP = 0x26FFFFFF
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFF9AA3B2.toInt()

    // Tiles as drawn by the factory «My car» screen.
    const val TILE = 0x8C2E3A4D.toInt()      // translucent blue-grey
    const val TILE_BORDER = 0x26FFFFFF
    const val TILE_ON = 0xFFE3E8F0.toInt()    // the light "active" tile
    const val TILE_ON_TEXT = 0xFF1B2333.toInt()

    // Accents, tuned to sit on the navy background.
    const val BLUE = 0xFF3D8BFF.toInt()
    const val GREEN = 0xFF34C77B.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val RED = 0xFFFF5A4E.toInt()
    const val TEAL = 0xFF5AC8FA.toInt()
    const val PURPLE = 0xFFB48CFF.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val INDIGO = 0xFF7C83FF.toInt()
    const val PINK = 0xFFFF6B8A.toInt()
    const val MINT = 0xFF66D4CF.toInt()

    // --- Theme-aware surfaces: DiLink night (default) and a light day variant ---
    fun isLight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO

    fun bg(c: Context) = if (isLight(c)) 0xFFEEF1F5.toInt() else BG
    fun card(c: Context) = if (isLight(c)) 0xFFFFFFFF.toInt() else CARD
    fun cardHi(c: Context) = if (isLight(c)) 0xFFE3E8F0.toInt() else CARD_HI
    fun textc(c: Context) = if (isLight(c)) 0xFF111827.toInt() else TEXT
    fun text2c(c: Context) = if (isLight(c)) 0xFF6B7380.toInt() else TEXT2
    fun sepc(c: Context) = if (isLight(c)) 0x1F000000 else SEP
}

val sf: Typeface get() = Typeface.create("sans-serif", Typeface.NORMAL)
val sfSemibold: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val sfBold: Typeface get() = Typeface.create("sans-serif", Typeface.BOLD)
val sfLight: Typeface get() = Typeface.create("sans-serif-light", Typeface.NORMAL)

fun Context.dpv(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** A rounded solid-colour background (a card or a filled pill). */
fun Context.rounded(color: Int, radius: Int = 16): GradientDrawable =
    GradientDrawable().apply { cornerRadius = dpf(radius.toFloat()); setColor(color) }

/** A DiLink tile background: translucent fill with a hairline border. */
fun Context.tileBg(color: Int = Ui.TILE, radius: Int = 16): GradientDrawable =
    rounded(color, radius).apply { setStroke(dpv(1), Ui.TILE_BORDER) }

/** A filled, fully rounded button with white text, like DiLink's blue «Confirm». */
fun Context.filledPill(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; setTextColor(Ui.TEXT); textSize = 15f
    typeface = sfSemibold; stateListAnimator = null; minHeight = 0; minimumHeight = 0
    background = rounded(color, 24)
    setPadding(dpv(18), dpv(10), dpv(18), dpv(10))
    setOnClickListener { onClick() }
}

/** A quiet DiLink tile-style button: translucent tile with accent-coloured text. */
fun Context.tintedPill(label: String, accent: Int, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; setTextColor(accent); textSize = 15f
    typeface = sfSemibold; stateListAnimator = null; minHeight = 0; minimumHeight = 0
    background = if (Ui.isLight(this@tintedPill)) rounded(Ui.cardHi(this@tintedPill), 24) else tileBg(radius = 24)
    setPadding(dpv(18), dpv(10), dpv(18), dpv(10))
    setOnClickListener { onClick() }
}
