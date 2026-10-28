package com.ashusnapx.news

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.time.ZoneId
import kotlin.math.roundToInt

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

val Int.dp get() = (this * Resources.getSystem().displayMetrics.density).roundToInt()

/** One hue per coaching; color resources so dark mode gets lighter tints. */
fun sourceColor(source: String) = when (source) {
    "Vajiram & Ravi" -> R.color.src_vajiram
    "Drishti IAS" -> R.color.src_drishti
    "Insights IAS" -> R.color.src_insights
    "ForumIAS" -> R.color.src_forum
    else -> R.color.ink2
}

/** Diagonal gradient in a coaching's hue: the stand-in for stories that come without a photo. */
fun Context.sourceGradient(source: String): GradientDrawable {
    val c = getColor(sourceColor(source))
    val deep = Color.rgb(Color.red(c) * 3 / 5, Color.green(c) * 3 / 5, Color.blue(c) * 3 / 5)
    return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c, deep))
}

/** Segmented-control pill: fills with [color] when on, morphing from the same hue at zero alpha (no grey midpoint). */
fun TextView.pill(on: Boolean, color: Int, animate: Boolean) {
    val bg = background as GradientDrawable
    val to = if (on) color else color and 0xFFFFFF
    setTextColor(context.getColor(if (on) R.color.on_src else R.color.ink2))
    if (!animate) return bg.setColor(to)
    ValueAnimator.ofArgb(bg.color?.defaultColor ?: to, to).apply {
        duration = 240
        addUpdateListener { bg.setColor(it.animatedValue as Int) }
        start()
    }
}

fun Activity.newPill(track: ViewGroup, label: CharSequence, onClick: () -> Unit) =
    (layoutInflater.inflate(R.layout.pill, track, false) as TextView).apply {
        text = label
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat() }
        setOnClickListener { haptic(); onClick() }
        track.addView(this)
    }

/** Web links only, and only to apps that declare themselves browsable: no intent://, javascript:, file:. */
fun Activity.browse(uri: Uri) {
    if (uri.scheme != "https" && uri.scheme != "http") return
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
}

fun View.haptic() = performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

fun View.bounce() {
    animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).setInterpolator(DecelerateInterpolator()).withEndAction {
        animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f))
    }
}

/** Draw behind status/nav bars and cutouts; [apply] pads content with the insets. Re-runs on rotate/fold. */
fun Activity.edgeToEdge(apply: (Insets) -> Unit) {
    window.setDecorFitsSystemWindows(false)
    findViewById<View>(android.R.id.content).setOnApplyWindowInsetsListener { _, i ->
        apply(i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()))
        i
    }
}

/** Current window width in px: real size in split-screen, folded or unfolded. */
val Activity.windowWidth get() = windowManager.currentWindowMetrics.bounds.width()

/** Side gutter that centers content at most [max] px wide. */
fun gutter(width: Int, max: Int) = maxOf(0, (width - max) / 2)
