package com.ashusnapx.news

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.time.DayOfWeek
import java.time.LocalDate

/** Daily-goal ring: a track, an arc that fills to [progress], and a number in the middle. */
class Ring(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var label = ""
        set(v) { field = v; invalidate() }
    private var progress = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = context.resources.getFont(R.font.jakarta_bold)
    }
    private val box = RectF()

    fun set(value: Float, animate: Boolean = true) {
        val to = value.coerceIn(0f, 1f)
        if (!animate) return run { progress = to; invalidate() }
        ValueAnimator.ofFloat(progress, to).apply {
            duration = 600
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width / 9f
        val done = progress >= 1f
        track.strokeWidth = stroke
        track.color = context.getColor(R.color.line)
        arc.strokeWidth = stroke
        arc.color = context.getColor(if (done) R.color.good else R.color.accent)
        box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
        canvas.drawOval(box, track)
        canvas.drawArc(box, -90f, 360f * progress, false, arc)
        text.textSize = height * 0.34f
        text.color = context.getColor(R.color.ink)
        canvas.drawText(label, width / 2f, height / 2f - (text.descent() + text.ascent()) / 2, text)
    }
}

/** Twelve weeks of reading, one square a day, Monday at the top: the GitHub contribution graph, for news. */
class Heatmap(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val weeks = 12
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = RectF()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        setMeasuredDimension(w, w * 7 / weeks) // square cells
    }

    override fun onDraw(canvas: Canvas) {
        val size = width / weeks.toFloat()
        val gap = size * 0.18f
        val today = LocalDate.now()
        val firstMonday = today.minusDays((today.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()).minusWeeks((weeks - 1).toLong())
        val accent = context.getColor(R.color.accent)
        for (w in 0 until weeks) for (d in 0 until 7) {
            val day = firstMonday.plusDays((w * 7 + d).toLong())
            if (day.isAfter(today)) continue
            val n = Store.readOn(context, day)
            paint.color = if (n == 0) context.getColor(R.color.line) else accent and 0xFFFFFF or (when {
                n >= 10 -> 0xFF
                n >= 6 -> 0xCC
                n >= 3 -> 0x8C
                else -> 0x55
            } shl 24)
            cell.set(w * size + gap / 2, d * size + gap / 2, (w + 1) * size - gap / 2, (d + 1) * size - gap / 2)
            canvas.drawRoundRect(cell, size * 0.22f, size * 0.22f, paint)
        }
    }
}
