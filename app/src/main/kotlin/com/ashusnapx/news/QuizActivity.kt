package com.ashusnapx.news

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Today's MCQs as a deck of flashcards. Front: question and options. Answer, and the options turn green/red,
 * then the card flips to the verdict and the reason, with background folded behind "More context".
 */
class QuizActivity : Activity() {
    private lateinit var quiz: List<Question>
    private lateinit var card: View
    private lateinit var front: ScrollView
    private lateinit var back: ScrollView
    private lateinit var step: TextView
    private lateinit var question: TextView
    private lateinit var options: LinearLayout
    private lateinit var verdict: TextView
    private lateinit var answer: TextView
    private lateinit var why: TextView
    private lateinit var whyLabel: View
    private lateinit var more: TextView
    private lateinit var moreToggle: TextView
    private lateinit var progress: View
    private lateinit var next: TextView
    private var index = 0
    private var score = 0
    private var chosen = -1 // option picked on the current card, -1 before answering

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // shortcut: the quiz lives in memory with the feed; after process death we fall back to the list
        quiz = Feed.current?.quiz?.takeIf { it.isNotEmpty() } ?: return finish()
        setContentView(R.layout.activity_quiz)
        card = findViewById(R.id.card)
        front = findViewById(R.id.front)
        back = findViewById(R.id.back_face)
        step = findViewById(R.id.step)
        question = findViewById(R.id.question)
        options = findViewById(R.id.options)
        verdict = findViewById(R.id.verdict)
        answer = findViewById(R.id.answer)
        why = findViewById(R.id.why)
        whyLabel = findViewById(R.id.whyLabel)
        more = findViewById(R.id.more)
        moreToggle = findViewById(R.id.moreToggle)
        progress = findViewById(R.id.progress)
        next = findViewById(R.id.next)
        val page = findViewById<View>(R.id.page)
        findViewById<View>(R.id.track).clipToOutline = true
        card.cameraDistance = 12000 * resources.displayMetrics.density // gentle perspective on the flip

        findViewById<View>(R.id.back).setOnClickListener { it.haptic(); finish() }
        next.setOnClickListener { it.haptic(); advance() }
        findViewById<View>(R.id.done).setOnClickListener { it.haptic(); finish() }
        back.setOnClickListener { it.haptic(); flip(toBack = false) }
        front.setOnClickListener { if (chosen >= 0) { it.haptic(); flip(toBack = true) } }
        moreToggle.setOnClickListener {
            it.haptic()
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            moreToggle.setText(if (open) R.string.quiz_less else R.string.quiz_more)
            if (open) {
                more.alpha = 0f
                more.animate().alpha(1f).setDuration(240)
                back.post { back.smoothScrollTo(0, moreToggle.top) }
            }
        }

        edgeToEdge { i ->
            val side = 20.dp + gutter(windowWidth - i.left - i.right, 640.dp)
            page.setPadding(i.left + side, i.top + 8.dp, i.right + side, i.bottom + 20.dp)
        }

        if (savedInstanceState != null) {
            index = savedInstanceState.getInt("index")
            score = savedInstanceState.getInt("score")
            chosen = savedInstanceState.getInt("chosen", -1)
        }
        if (index >= quiz.size) return result()
        show()
        if (chosen >= 0) reveal(restoring = true) // rotated after answering: same card, already turned
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putInt("score", score)
        outState.putInt("chosen", chosen)
    }

    private fun show() {
        val q = quiz[index]
        chosen = -1
        step.text = getString(R.string.quiz_step, index + 1, quiz.size, q.source.uppercase())
        question.text = Html.fromHtml(q.q, Html.FROM_HTML_MODE_COMPACT).trim()
        options.removeAllViews()
        q.options.forEachIndexed { i, text ->
            val row = layoutInflater.inflate(R.layout.quiz_option, options, false)
            row.background = GradientDrawable().apply {
                cornerRadius = 16.dp.toFloat()
                setColor(getColor(R.color.bg))
                setStroke(1.dp, getColor(R.color.line))
            }
            row.findViewById<TextView>(R.id.letter).text = letter(i)
            row.findViewById<TextView>(R.id.text).text = text
            row.setOnClickListener { pick(i) }
            options.addView(row)
        }
        front.visibility = View.VISIBLE
        back.visibility = View.GONE
        front.scrollTo(0, 0)
        next.visibility = View.INVISIBLE
        // The deck thins out as the questions run down.
        val left = quiz.size - index - 1
        findViewById<View>(R.id.ghost1).visibility = if (left >= 1) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.ghost2).visibility = if (left >= 2) View.VISIBLE else View.INVISIBLE
        progress.animate().scaleX(index / quiz.size.toFloat()).setDuration(300)
    }

    private fun pick(i: Int) {
        if (chosen >= 0) return
        chosen = i
        val right = i == quiz[index].answer
        if (right) score++
        options.performHapticFeedback(if (right) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT)
        reveal(restoring = false)
    }

    /** Colour the options, fill the back of the card, then turn it over. */
    private fun reveal(restoring: Boolean) {
        val q = quiz[index]
        val right = chosen == q.answer
        for (i in 0 until options.childCount) options.getChildAt(i).isClickable = false
        mark(q.answer, R.color.good)
        if (!right) mark(chosen, R.color.bad)
        if (!restoring) options.getChildAt(chosen).bounce()

        val color = getColor(if (right) R.color.good else R.color.bad)
        back.background = GradientDrawable().apply {
            cornerRadius = 28.dp.toFloat()
            setColor(getColor(R.color.surface))
            setStroke(2.dp, color)
        }
        verdict.setText(if (right) R.string.quiz_right else R.string.quiz_wrong)
        verdict.setTextColor(color)
        answer.text = getString(R.string.quiz_answer, letter(q.answer), q.options[q.answer])
        why.text = Html.fromHtml(q.why, Html.FROM_HTML_MODE_COMPACT).trim()
        listOf(why, whyLabel).forEach { it.visibility = if (why.text.isEmpty()) View.GONE else View.VISIBLE }
        more.text = Html.fromHtml(q.more, Html.FROM_HTML_MODE_COMPACT).trim()
        more.visibility = View.GONE
        moreToggle.setText(R.string.quiz_more)
        moreToggle.visibility = if (more.text.isEmpty()) View.GONE else View.VISIBLE

        next.setText(if (index == quiz.lastIndex) R.string.quiz_finish else R.string.quiz_next)
        next.visibility = View.VISIBLE
        progress.animate().scaleX((index + 1) / quiz.size.toFloat()).setDuration(300)
        if (restoring) {
            front.visibility = View.GONE
            back.visibility = View.VISIBLE
            return
        }
        next.translationY = 80.dp.toFloat()
        next.animate().translationY(0f).setStartDelay(500).setDuration(320)
        card.postDelayed({ if (chosen >= 0 && front.isShown) flip(toBack = true) }, 650) // let the colours register first
    }

    private fun flip(toBack: Boolean) {
        val (hide, reveal) = if (toBack) front to back else back to front
        card.animate().rotationY(if (toBack) 90f else -90f).setDuration(170).setInterpolator(AccelerateInterpolator()).withEndAction {
            hide.visibility = View.GONE
            reveal.visibility = View.VISIBLE
            reveal.scrollTo(0, 0)
            card.rotationY = if (toBack) -90f else 90f
            card.animate().rotationY(0f).setDuration(230).setInterpolator(DecelerateInterpolator())
        }
    }

    /** Tint an option: soft fill, solid stroke, filled letter. */
    private fun mark(i: Int, colorRes: Int) {
        val row = options.getChildAt(i)
        val color = getColor(colorRes)
        (row.background as GradientDrawable).apply {
            setColor(color and 0xFFFFFF or 0x1F000000)
            setStroke(2.dp, color)
        }
        row.findViewById<TextView>(R.id.letter).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            setTextColor(getColor(R.color.on_src))
        }
    }

    /** The answered card is tossed off to the left; the next rises out of the deck. */
    private fun advance() {
        if (index == quiz.lastIndex) {
            index = quiz.size
            return result()
        }
        card.animate().translationX(-card.width * 1.1f).rotation(-8f).alpha(0f).setDuration(260)
            .setInterpolator(AccelerateInterpolator()).withEndAction {
                index++
                show()
                card.apply {
                    translationX = 0f
                    rotation = 0f
                    rotationY = 0f
                    alpha = 0f
                    scaleX = 0.94f
                    scaleY = 0.94f
                    translationY = 17.dp.toFloat()
                    animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator())
                }
            }
    }

    private fun result() {
        findViewById<TextView>(R.id.score).text = getString(R.string.quiz_score, score, quiz.size)
        findViewById<TextView>(R.id.verdictAll).setText(
            when {
                score * 10 >= quiz.size * 8 -> R.string.quiz_great
                score * 2 >= quiz.size -> R.string.quiz_okay
                else -> R.string.quiz_low
            }
        )
        findViewById<View>(R.id.result).apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360)
        }
    }

    private fun letter(i: Int) = ('A' + i).toString()
}
