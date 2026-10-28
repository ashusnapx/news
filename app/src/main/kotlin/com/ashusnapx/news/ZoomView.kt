package com.ashusnapx.news

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** Image that fits the screen, pinch-zooms to 5x, pans within its edges, and double-tap toggles 2.5x. */
class ZoomView(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    private val m = Matrix()
    private var scale = 1f // relative to fit

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            clamp()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (scale > 1.05f) 1f else 2.5f
            ValueAnimator.ofFloat(scale, target).apply {
                duration = 260
                addUpdateListener { zoom(it.animatedValue as Float / scale, e.x, e.y) }
                start()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit() // rotation: refit to the new shape
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        fit()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    override fun performClick() = super.performClick()

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val s = minOf(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        m.setScale(s, s)
        m.postTranslate((width - d.intrinsicWidth * s) / 2, (height - d.intrinsicHeight * s) / 2)
        scale = 1f
        imageMatrix = m
    }

    private fun zoom(factor: Float, x: Float, y: Float) {
        val f = (scale * factor).coerceIn(1f, 5f) / scale
        scale *= f
        m.postScale(f, f, x, y)
        clamp()
    }

    /** Centre an axis that fits; otherwise never let an edge pull inside the screen. */
    private fun clamp() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()).also(m::mapRect)
        fun shift(start: Float, end: Float, size: Float) = when {
            end - start <= size -> (size - (end - start)) / 2 - start
            start > 0 -> -start
            end < size -> size - end
            else -> 0f
        }
        m.postTranslate(shift(r.left, r.right, width.toFloat()), shift(r.top, r.bottom, height.toFloat()))
        imageMatrix = m
    }
}
