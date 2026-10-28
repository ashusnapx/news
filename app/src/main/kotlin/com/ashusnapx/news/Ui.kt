package com.ashusnapx.news

import android.app.Activity
import android.content.res.Resources
import android.graphics.Insets
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    "Rau's IAS" -> R.color.src_rau
    else -> R.color.ink2
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
