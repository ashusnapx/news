package com.ashusnapx.news

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast

/** Full-screen image. Turns the phone to match the picture before showing it, so wide infographics fill the screen. */
class ImageActivity : Activity() {
    companion object {
        const val URL = "url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(URL)?.takeIf { it.startsWith("https://") } ?: return finish()
        setContentView(R.layout.activity_image)
        val zoom = findViewById<ZoomView>(R.id.zoom)
        val close = findViewById<View>(R.id.close)
        val spinner = findViewById<View>(R.id.spinner)
        close.setOnClickListener { it.haptic(); finish() }
        zoom.setOnClickListener { close.animate().alpha(if (close.alpha > 0f) 0f else 1f).setDuration(180) }

        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        edgeToEdge { i ->
            (close.layoutParams as MarginLayoutParams).apply { topMargin = i.top + 12.dp; marginStart = i.left + 12.dp }
            close.requestLayout()
        }

        val metrics = windowManager.maximumWindowMetrics.bounds
        val side = maxOf(metrics.width(), metrics.height())
        Thread {
            val bitmap = runCatching { Img.decode(url, side, side) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                spinner.visibility = View.GONE
                if (bitmap == null) {
                    Toast.makeText(this, R.string.image_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread finish()
                }
                orient(bitmap.width, bitmap.height)
                zoom.setImageBitmap(bitmap)
                zoom.alpha = 0f
                zoom.animate().alpha(1f).setDuration(220)
            }
        }.start()
    }

    /**
     * Wide picture on a portrait phone -> landscape; tall picture on a landscape phone -> portrait; near-square
     * pictures leave the phone as it is. Already the right way round means no change. Large screens (unfolded
     * foldables, tablets) never rotate: the picture already fits, and Android 16+ ignores the request there anyway.
     */
    private fun orient(w: Int, h: Int) {
        if (resources.configuration.smallestScreenWidthDp >= 600) return
        requestedOrientation = when {
            w > h * 1.2f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            h > w * 1.2f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> return
        }
    }
}
