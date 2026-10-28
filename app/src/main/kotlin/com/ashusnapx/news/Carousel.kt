package com.ashusnapx.news

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import kotlin.math.abs

/**
 * Top stories, story-style: a bar per slide fills over [SLIDE_MS] and moves on. Swipe to step, hold to pause,
 * tap to read. Doesn't auto-advance with animations off or TalkBack on; screen readers get next/previous actions.
 */
class Carousel(private val activity: Activity, val view: View, private val onOpen: (View, ImageView, Item) -> Unit) {
    private val frame: FrameLayout = view.findViewById(R.id.frame)
    private val flipper: ViewFlipper = view.findViewById(R.id.flipper)
    private val segments: LinearLayout = view.findViewById(R.id.segments)
    private var stories: List<Story> = emptyList()
    private var timer: Animator? = null
    private var held = false
    private val auto = ValueAnimator.areAnimatorsEnabled() &&
        !activity.getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled

    private val current get() = flipper.displayedChild

    init {
        frame.clipToOutline = true
        frame.layoutParams.height = minOf(440.dp, activity.windowManager.currentWindowMetrics.bounds.height() * 3 / 5)
        frame.setOnClickListener { v -> stories.getOrNull(current)?.let { onOpen(v, flipper.currentView.findViewById(R.id.image), it.lead) } }

        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent) = frame.performClick()

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (abs(dx) > abs(dy)) frame.parent.requestDisallowInterceptTouchEvent(true) // keep the swipe from the list
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || stories.size < 2) return false
                frame.haptic()
                if (vx < 0) next() else prev()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                held = true
                timer?.pause()
                frame.haptic()
            }
        })
        frame.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    if (held) {
                        held = false
                        timer?.resume()
                    }
                }
            }
            gestures.onTouchEvent(e)
        }

        frame.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (stories.size < 2) return
                info.addAction(AccessibilityAction(R.id.a11y_next, activity.getString(R.string.story_next)))
                info.addAction(AccessibilityAction(R.id.a11y_prev, activity.getString(R.string.story_prev)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?) = when (action) {
                R.id.a11y_next -> { next(); true }
                R.id.a11y_prev -> { prev(); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    fun bind(list: List<Story>) {
        if (list == stories) return
        stories = list
        flipper.removeAllViews()
        segments.removeAllViews()
        list.forEach { story ->
            flipper.addView(slide(story))
            segments.addView(FrameLayout(activity).apply {
                background = activity.getDrawable(R.drawable.seg_track)
                clipToOutline = true
                addView(View(activity).apply { setBackgroundColor(Color.WHITE); pivotX = 0f; scaleX = 0f }, MATCH_PARENT, MATCH_PARENT)
            }, LinearLayout.LayoutParams(0, 3.dp, 1f).apply { marginStart = 2.dp; marginEnd = 2.dp })
        }
        segments.visibility = if (list.size > 1) View.VISIBLE else View.GONE
        show(0, forward = true, animate = false)
    }

    fun pause() = timer?.pause()

    fun resume() {
        if (!held) timer?.resume()
    }

    private fun next() = show((current + 1) % stories.size, forward = true)
    private fun prev() = show((current - 1 + stories.size) % stories.size, forward = false)

    private fun fill(i: Int) = (segments.getChildAt(i) as ViewGroup).getChildAt(0)

    private fun show(i: Int, forward: Boolean, animate: Boolean = true) {
        flipper.inAnimation = if (animate) AnimationUtils.loadAnimation(activity, if (forward) R.anim.slide_in_next else R.anim.slide_in_prev) else null
        flipper.outAnimation = if (animate) AnimationUtils.loadAnimation(activity, if (forward) R.anim.slide_out_next else R.anim.slide_out_prev) else null
        flipper.displayedChild = i
        frame.contentDescription = stories[i].lead.title
        for (k in 0 until segments.childCount) fill(k).scaleX = if (k < i) 1f else 0f
        enter(flipper.getChildAt(i))
        timer?.cancel()
        timer = null
        if (stories.size < 2) return
        if (!auto) return run { fill(i).scaleX = 1f }
        timer = ObjectAnimator.ofFloat(fill(i), View.SCALE_X, 0f, 1f).apply {
            duration = SLIDE_MS
            interpolator = LinearInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) { if (!canceled) next() }
            })
            start()
        }
    }

    /** Slow zoom on the photo, then the text rises in line by line. */
    private fun enter(slide: View) {
        val image = slide.findViewById<View>(R.id.image)
        image.scaleX = 1f
        image.scaleY = 1f
        image.animate().scaleX(1.08f).scaleY(1.08f).setDuration(SLIDE_MS + 1000).setInterpolator(LinearInterpolator())
        listOf(R.id.badge, R.id.title, R.id.excerpt, R.id.sources).forEachIndexed { k, id ->
            slide.findViewById<View>(id).apply {
                alpha = 0f
                translationY = 18.dp.toFloat()
                animate().alpha(1f).translationY(0f).setStartDelay(140L + k * 70).setDuration(420)
            }
        }
    }

    private fun slide(story: Story): View = activity.layoutInflater.inflate(R.layout.item_slide, flipper, false).apply {
        val lead = story.lead
        findViewById<TextView>(R.id.badge).text =
            if (story.size > 1) activity.getString(R.string.covered_by, story.size) else activity.getString(R.string.top_story)
        findViewById<TextView>(R.id.title).text = lead.title
        findViewById<TextView>(R.id.excerpt).text = lead.excerpt
        findViewById<TextView>(R.id.sources).text = activity.getString(R.string.min_short, lead.minutes) +
            if (story.size > 1) activity.getString(R.string.dot) + activity.resources.getQuantityString(R.plurals.sources, story.size, story.size) else ""
        val image = findViewById<ImageView>(R.id.image)
        image.background = activity.sourceGradient(lead.source) // shows through when there is no photo
        Img.load(lead.image, image)
    }

    companion object {
        const val SLIDE_MS = 5000L
    }
}
