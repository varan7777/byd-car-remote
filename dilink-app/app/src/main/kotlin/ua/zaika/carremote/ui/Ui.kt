package ua.zaika.carremote.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * Shared Apple-flavoured design tokens and tiny view builders, so every screen reads as one
 * system: near-black background, dark rounded cards, SF-style type, secondary-grey labels, and
 * restrained accent colour used as a tint rather than loud fills.
 */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val GROUPED_BG = 0xFF0B0B0D.toInt()
    const val CARD = 0xFF1C1C1E.toInt()
    const val CARD_HI = 0xFF2C2C2E.toInt()
    const val SEP = 0x3D545458
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFF98989F.toInt() // secondary label grey

    // Apple system palette (dark).
    const val BLUE = 0xFF0A84FF.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val RED = 0xFFFF453A.toInt()
    const val TEAL = 0xFF64D2FF.toInt()
    const val PURPLE = 0xFFBF5AF2.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val INDIGO = 0xFF5E5CE6.toInt()
    const val PINK = 0xFFFF375F.toInt()
    const val MINT = 0xFF66D4CF.toInt()

    // --- Theme-aware surfaces (Apple light / dark) ---
    fun isLight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO

    fun bg(c: Context) = if (isLight(c)) 0xFFF2F2F7.toInt() else 0xFF000000.toInt()
    fun card(c: Context) = if (isLight(c)) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
    fun cardHi(c: Context) = if (isLight(c)) 0xFFE7E7EC.toInt() else 0xFF2C2C2E.toInt()
    fun textc(c: Context) = if (isLight(c)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    fun text2c(c: Context) = if (isLight(c)) 0xFF8E8E93.toInt() else 0xFF98989F.toInt()
    fun sepc(c: Context) = if (isLight(c)) 0x33787880 else 0x3D545458
}

val sf: Typeface get() = Typeface.create("sans-serif", Typeface.NORMAL)
val sfSemibold: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val sfBold: Typeface get() = Typeface.create("sans-serif", Typeface.BOLD)

fun Context.dpv(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density

/** A rounded solid-colour background (a card or a filled pill). */
fun Context.rounded(color: Int, radius: Int = 18): GradientDrawable =
    GradientDrawable().apply { cornerRadius = dpf(radius.toFloat()); setColor(color) }

/** A filled, rounded, SF-semibold button with white text. Compact. */
fun Context.filledPill(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; setTextColor(Ui.TEXT); textSize = 13f
    typeface = sfSemibold; stateListAnimator = null; minHeight = 0; minimumHeight = 0
    background = rounded(color, 12)
    setPadding(dpv(10), dpv(7), dpv(10), dpv(7))
    setOnClickListener { onClick() }
}

/** A tinted (dark card + accent-coloured text) rounded button — Apple's quieter action style. */
fun Context.tintedPill(label: String, accent: Int, onClick: () -> Unit) = Button(this).apply {
    text = label; isAllCaps = false; setTextColor(accent); textSize = 13f
    typeface = sfSemibold; stateListAnimator = null; minHeight = 0; minimumHeight = 0
    background = rounded(Ui.cardHi(this@tintedPill), 12)
    setPadding(dpv(10), dpv(7), dpv(10), dpv(7))
    setOnClickListener { onClick() }
}
