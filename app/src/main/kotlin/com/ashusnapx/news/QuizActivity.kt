package com.ashusnapx.news

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.AbsoluteSizeSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Prelims Mode: today's MCQs marked like the real paper (+2 / -0.66 / 0 for a skip). Long-press rules an option
 * out, a pace bar drains over the exam's 72 seconds a question, a streak counts consecutive right answers, and a
 * scoreboard strip fills green/red/grey. No coaching names: sets are mixed and numbered.
 */
class QuizActivity : Activity() {
    companion object {
        const val REVISE = "revise" // open straight into the revision deck
        private const val PACE_MS = 72_000L // 120 minutes / 100 questions
        private const val SET = 10
        private const val PENDING = -1
        private const val WRONG = 0
        private const val RIGHT = 1
        private const val SKIPPED = 2
    }

    private lateinit var all: List<Question>
    private lateinit var sets: List<List<Question>> // index 0 = everything, then mixed sets of SET
    private var set = 0
    private var reviseSet = -1 // index of the revision deck among [sets], -1 when nothing is due
    private val quiz get() = sets[set]
    private var outcomes = IntArray(0)
    private var index = 0
    private var chosen = PENDING
    private var streak = 0
    private var shownMarks = 0f
    private var pacer: ObjectAnimator? = null
    private var shownAt = 0L // when the current question appeared
    private lateinit var setPills: List<TextView>

    private lateinit var scroll: ScrollView
    private lateinit var intro: View
    private lateinit var play: View
    private lateinit var step: TextView
    private lateinit var marks: TextView
    private lateinit var streakView: TextView
    private lateinit var board: LinearLayout
    private lateinit var pace: View
    private lateinit var question: TextView
    private lateinit var options: LinearLayout
    private lateinit var hint: View
    private lateinit var whyBox: View
    private lateinit var verdict: TextView
    private lateinit var answer: TextView
    private lateinit var why: TextView
    private lateinit var more: TextView
    private lateinit var moreToggle: TextView
    private lateinit var actions: LinearLayout
    private lateinit var skip: View
    private lateinit var next: TextView
    private lateinit var floater: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // shortcut: the quiz lives in memory with the feed; after process death we fall back to the list
        val due = Store.due(this)
        all = Feed.current?.quiz.orEmpty()
        if (all.isEmpty() && due.isEmpty()) return finish()
        setContentView(R.layout.activity_quiz)
        scroll = findViewById(R.id.scroll)
        intro = findViewById(R.id.intro)
        play = findViewById(R.id.play)
        step = findViewById(R.id.step)
        marks = findViewById(R.id.marks)
        streakView = findViewById(R.id.streak)
        board = findViewById(R.id.board)
        pace = findViewById(R.id.pace)
        question = findViewById(R.id.question)
        options = findViewById(R.id.options)
        hint = findViewById(R.id.hint)
        whyBox = findViewById(R.id.whyBox)
        verdict = findViewById(R.id.verdict)
        answer = findViewById(R.id.answer)
        why = findViewById(R.id.why)
        more = findViewById(R.id.more)
        moreToggle = findViewById(R.id.moreToggle)
        actions = findViewById(R.id.actions)
        skip = findViewById(R.id.skip)
        next = findViewById(R.id.next)
        floater = findViewById(R.id.floater)
        findViewById<View>(R.id.paceTrack).clipToOutline = true
        if (!ValueAnimator.areAnimatorsEnabled()) findViewById<View>(R.id.paceTrack).visibility = View.GONE

        // Coachings take turns, so every numbered set mixes them.
        val bySource = all.groupBy { it.source }.values.toList()
        val mixed = (0 until (bySource.maxOfOrNull { it.size } ?: 0)).flatMap { i -> bySource.mapNotNull { it.getOrNull(i) } }
        sets = (if (mixed.isEmpty()) emptyList() else listOf(mixed) + if (mixed.size > SET) mixed.chunked(SET) else emptyList()) +
            if (due.isEmpty()) emptyList() else listOf(due)
        reviseSet = if (due.isEmpty()) -1 else sets.lastIndex
        val track = findViewById<LinearLayout>(R.id.sets)
        setPills = sets.mapIndexed { i, s ->
            val label = when {
                i == reviseSet -> getString(R.string.revise_set, s.size)
                i == 0 -> getString(R.string.set_all, s.size)
                else -> getString(R.string.set_one, i, s.size)
            }
            newPill(track, label) { pickSet(i) }
        }

        findViewById<View>(R.id.back).setOnClickListener { it.haptic(); finish() }
        findViewById<View>(R.id.start).setOnClickListener { it.haptic(); begin() }
        skip.setOnClickListener { it.haptic(); answer(PENDING) }
        next.setOnClickListener { it.haptic(); advance() }
        findViewById<View>(R.id.done).setOnClickListener { it.haptic(); finish() }
        findViewById<View>(R.id.again).setOnClickListener {
            it.haptic()
            findViewById<View>(R.id.result).visibility = View.GONE
            showIntro()
        }
        moreToggle.setOnClickListener {
            it.haptic()
            val open = more.visibility != View.VISIBLE
            more.visibility = if (open) View.VISIBLE else View.GONE
            moreToggle.setText(if (open) R.string.quiz_less else R.string.quiz_more)
            if (open) {
                more.alpha = 0f
                more.animate().alpha(1f).setDuration(240)
            }
        }

        edgeToEdge { i ->
            val side = 20.dp + gutter(windowWidth - i.left - i.right, 680.dp)
            findViewById<View>(R.id.page).setPadding(i.left + side, i.top + 72.dp, i.right + side, i.bottom + 104.dp)
            findViewById<View>(R.id.resultPage).setPadding(i.left + side, i.top + 72.dp, i.right + side, i.bottom + 32.dp)
            actions.setPadding(i.left + side, 12.dp, i.right + side, i.bottom + 16.dp)
            (actions.getChildAt(1).layoutParams as MarginLayoutParams).marginStart = 12.dp
            findViewById<View>(R.id.back).apply {
                (layoutParams as MarginLayoutParams).apply { topMargin = i.top + 8.dp; marginStart = i.left + 8.dp }
                requestLayout()
            }
        }

        if (intent.getBooleanExtra(REVISE, false) && reviseSet >= 0) set = reviseSet
        findViewById<View>(R.id.share).setOnClickListener {
            it.haptic()
            Analytics.log(this, "share", "what" to "score")
            Share.score(this, fmt(score()).removePrefix("+"), quiz.size * 2, outcomes.count { o -> o == RIGHT }, quiz.size)
        }
        val saved = savedInstanceState?.getIntArray("outcomes")
        if (saved == null) return showIntro()
        set = savedInstanceState.getInt("set")
        outcomes = saved
        index = savedInstanceState.getInt("index")
        chosen = savedInstanceState.getInt("chosen", PENDING)
        streak = savedInstanceState.getInt("streak")
        shownMarks = score()
        if (index >= quiz.size) return result()
        start()
        if (outcomes[index] != PENDING) reveal(animate = false) // rotated after answering
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (play.visibility != View.VISIBLE && findViewById<View>(R.id.result).visibility != View.VISIBLE) return
        outState.putInt("set", set)
        outState.putIntArray("outcomes", outcomes)
        outState.putInt("index", index)
        outState.putInt("chosen", chosen)
        outState.putInt("streak", streak)
    }

    override fun onDestroy() {
        pacer?.cancel()
        super.onDestroy()
    }

    private fun showIntro() {
        play.visibility = View.GONE
        actions.visibility = View.GONE
        intro.visibility = View.VISIBLE
        pickSet(set, animate = false)
        scroll.scrollTo(0, 0)
    }

    private fun pickSet(i: Int, animate: Boolean = true) {
        set = i
        setPills.forEachIndexed { k, p -> p.pill(k == i, getColor(R.color.accent), animate) }
        if (animate) setPills[i].bounce()
        findViewById<TextView>(R.id.start).text = getString(R.string.start_set, resources.getQuantityString(R.plurals.questions, quiz.size, quiz.size))
    }

    override fun onStop() {
        Analytics.send()
        super.onStop()
    }

    private fun begin() {
        Analytics.log(this, "quiz_start", "size" to quiz.size, "revision" to (set == reviseSet).toString())
        outcomes = IntArray(quiz.size) { PENDING }
        index = 0
        streak = 0
        shownMarks = 0f
        start()
    }

    /** Build the scoreboard for the chosen set and show the current question. */
    private fun start() {
        intro.visibility = View.GONE
        play.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        board.removeAllViews()
        quiz.indices.forEach { k ->
            board.addView(View(this).apply { background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat() } },
                LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (k > 0) marginStart = 3.dp })
        }
        paintBoard()
        marks.text = fmt(shownMarks)
        show()
    }

    private fun show() {
        val q = quiz[index]
        chosen = PENDING
        step.text = getString(R.string.quiz_step, index + 1, quiz.size)
        question.text = Html.fromHtml(q.q, Html.FROM_HTML_MODE_COMPACT).trim()
        options.removeAllViews()
        q.options.forEachIndexed { i, text ->
            val row = layoutInflater.inflate(R.layout.quiz_option, options, false)
            row.background = GradientDrawable().apply {
                cornerRadius = 16.dp.toFloat()
                setColor(getColor(R.color.surface))
                setStroke(1.dp + 1, getColor(R.color.line))
            }
            row.findViewById<TextView>(R.id.letter).text = letter(i)
            row.findViewById<TextView>(R.id.text).text = text
            row.setOnClickListener { if (row.tag == true) ruleOut(row) else answer(i) } // a ruled-out option comes back on tap
            row.setOnLongClickListener { ruleOut(row); true }
            options.addView(row)
        }
        hint.visibility = View.VISIBLE
        whyBox.visibility = View.GONE
        skip.visibility = View.VISIBLE
        next.visibility = View.GONE
        streakView.visibility = if (streak >= 2) View.VISIBLE else View.GONE
        streakView.text = getString(R.string.streak, streak)
        paintBoard()
        scroll.scrollTo(0, 0)
        shownAt = System.currentTimeMillis()

        pacer?.cancel()
        pace.setBackgroundColor(getColor(R.color.accent))
        pace.scaleX = 1f
        if (!ValueAnimator.areAnimatorsEnabled()) return
        pacer = ObjectAnimator.ofFloat(pace, View.SCALE_X, 1f, 0f).apply {
            duration = PACE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { if (it.animatedFraction > 0.999f) pace.setBackgroundColor(getColor(R.color.bad)) } // over exam pace
            start()
        }
    }

    /** Strike an option through, as on the OMR sheet; again to bring it back. */
    private fun ruleOut(row: View) {
        if (chosen != PENDING || outcomes[index] != PENDING) return
        val out = row.tag != true
        row.tag = out
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        row.findViewById<TextView>(R.id.text).apply {
            paintFlags = if (out) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        row.animate().alpha(if (out) 0.4f else 1f).setDuration(160)
    }

    /** [choice] is an option index, or PENDING for a skip. */
    private fun answer(choice: Int) {
        if (outcomes[index] != PENDING) return
        val q = quiz[index]
        chosen = choice
        outcomes[index] = when (choice) {
            PENDING -> SKIPPED
            q.answer -> RIGHT
            else -> WRONG
        }
        streak = if (outcomes[index] == RIGHT) streak + 1 else 0
        Analytics.log(this, "quiz_answer", "result" to when (outcomes[index]) { RIGHT -> "right"; WRONG -> "wrong"; else -> "skip" },
            "seconds" to (System.currentTimeMillis() - shownAt) / 1000, "ruled_out" to (0 until options.childCount).count { options.getChildAt(it).tag == true },
            "q_index" to index + 1, "source" to q.source, "revision" to (set == reviseSet).toString())
        // The revision deck learns from every answer: wrong or skipped comes back tomorrow, right moves it along.
        if (set == reviseSet) Store.reviewed(this, q, outcomes[index] == RIGHT)
        else if (outcomes[index] != RIGHT) Store.missed(this, q)
        options.performHapticFeedback(
            when (outcomes[index]) {
                RIGHT -> HapticFeedbackConstants.CONFIRM
                WRONG -> HapticFeedbackConstants.REJECT
                else -> HapticFeedbackConstants.CONTEXT_CLICK
            }
        )
        reveal(animate = true)
    }

    private fun reveal(animate: Boolean) {
        val q = quiz[index]
        val outcome = outcomes[index]
        pacer?.cancel()
        for (i in 0 until options.childCount) options.getChildAt(i).apply { isClickable = false; isLongClickable = false; alpha = 1f }
        mark(q.answer, R.color.good)
        if (outcome == WRONG) mark(chosen, R.color.bad)
        hint.visibility = View.GONE
        skip.visibility = View.GONE
        paintBoard()

        val color = getColor(when (outcome) { RIGHT -> R.color.good; WRONG -> R.color.bad; else -> R.color.ink2 })
        verdict.setText(when (outcome) { RIGHT -> R.string.quiz_right; WRONG -> R.string.quiz_wrong; else -> R.string.quiz_skipped })
        verdict.setTextColor(color)
        answer.text = getString(R.string.quiz_answer, letter(q.answer), q.options[q.answer])
        why.text = explain(q.why)
        why.visibility = if (why.text.isEmpty()) View.GONE else View.VISIBLE
        more.text = explain(q.more)
        more.visibility = View.GONE
        moreToggle.setText(R.string.quiz_more)
        moreToggle.visibility = if (more.text.isEmpty()) View.GONE else View.VISIBLE
        next.setText(if (index == quiz.lastIndex) R.string.quiz_finish else R.string.quiz_next)
        next.visibility = View.VISIBLE
        whyBox.visibility = View.VISIBLE

        val target = score()
        if (!animate) {
            shownMarks = target
            marks.text = fmt(target)
            return
        }
        if (outcome != SKIPPED) {
            options.getChildAt(chosen).bounce()
            float(if (outcome == RIGHT) "+2" else "−0.66", color, options.getChildAt(chosen))
        }
        ValueAnimator.ofFloat(shownMarks, target).apply {
            duration = 500
            addUpdateListener { marks.text = fmt(it.animatedValue as Float) }
            start()
        }
        shownMarks = target
        if (streak >= 2) {
            streakView.text = getString(R.string.streak, streak)
            streakView.visibility = View.VISIBLE
            streakView.bounce()
        } else {
            streakView.visibility = View.GONE
        }
        whyBox.alpha = 0f
        whyBox.translationY = 24.dp.toFloat()
        whyBox.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(300)
        scroll.post { scroll.smoothScrollTo(0, maxOf(0, play.top + whyBox.top - scroll.height / 3)) }
    }

    /** The marks earned rise off the option and fade. */
    private fun float(text: String, color: Int, from: View) {
        val at = IntArray(2).also(from::getLocationInWindow)
        floater.text = text
        floater.setTextColor(color)
        floater.visibility = View.VISIBLE
        floater.measure(0, 0)
        floater.x = at[0] + from.width - floater.measuredWidth - 24.dp.toFloat()
        floater.y = at[1] - floater.measuredHeight / 2f
        floater.alpha = 1f
        floater.animate().translationYBy(-64.dp.toFloat()).alpha(0f).setDuration(900).setInterpolator(DecelerateInterpolator())
            .withEndAction { floater.visibility = View.GONE }
    }

    private fun advance() {
        if (index == quiz.lastIndex) {
            index = quiz.size
            return result()
        }
        index++
        play.animate().alpha(0f).translationX(-24.dp.toFloat()).setDuration(140).withEndAction {
            show()
            play.translationX = 24.dp.toFloat()
            play.animate().alpha(1f).translationX(0f).setDuration(240)
        }
    }

    private fun result() {
        pacer?.cancel()
        Reminder.quizDone(this) // no afternoon nudge once today's quiz is done
        Analytics.log(this, "quiz_complete", "size" to quiz.size, "marks" to score().toDouble(),
            "right" to outcomes.count { it == RIGHT }, "wrong" to outcomes.count { it == WRONG }, "skipped" to outcomes.count { it == SKIPPED })
        play.visibility = View.GONE
        actions.visibility = View.GONE
        val right = outcomes.count { it == RIGHT }
        val wrong = outcomes.count { it == WRONG }
        val skipped = outcomes.count { it == SKIPPED }
        findViewById<TextView>(R.id.score).text = fmt(score()).removePrefix("+")
        findViewById<TextView>(R.id.outOf).text = getString(R.string.out_of, quiz.size * 2)
        findViewById<TextView>(R.id.stats).text = getString(
            R.string.result_stats, right, wrong, skipped,
            if (right + wrong == 0) 0 else right * 100 / (right + wrong), String.format(Locale.ENGLISH, "%.2f", wrong * 2f / 3),
        )
        findViewById<TextView>(R.id.verdictAll).setText(
            when {
                right * 10 >= quiz.size * 8 -> R.string.quiz_great
                right * 2 >= quiz.size -> R.string.quiz_okay
                else -> R.string.quiz_low
            }
        )
        val recap = findViewById<LinearLayout>(R.id.recap)
        recap.removeAllViews()
        outcomes.forEachIndexed { k, o ->
            recap.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = 4.dp.toFloat(); setColor(boardColor(o)) }
            }, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (k > 0) marginStart = 4.dp })
        }
        findViewById<View>(R.id.result).apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360)
        }
    }

    private fun score() = outcomes.count { it == RIGHT } * 2f - outcomes.count { it == WRONG } * 2f / 3

    private fun fmt(v: Float) = String.format(Locale.ENGLISH, if (v > 0.001f) "+%.2f" else "%.2f", v).replace("-", "−")

    private fun boardColor(o: Int) = when (o) {
        RIGHT -> getColor(R.color.good)
        WRONG -> getColor(R.color.bad)
        SKIPPED -> getColor(R.color.ink2) and 0xFFFFFF or 0x66000000
        else -> getColor(R.color.line)
    }

    private fun paintBoard() {
        for (k in 0 until board.childCount) {
            val color = if (k == index && outcomes[k] == PENDING) getColor(R.color.accent) else boardColor(outcomes[k])
            (board.getChildAt(k).background as GradientDrawable).setColor(color)
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

    /** Explanation blocks -> one styled text: accent sub-headings, real indented bullets, even paragraph gaps. */
    private fun explain(blocks: List<Block>): CharSequence {
        val out = SpannableStringBuilder()
        val accent = getColor(R.color.accent)
        val heading = resources.getFont(R.font.jakarta_bold)
        blocks.forEachIndexed { i, b ->
            if (i > 0) {
                out.append("\n")
                gap(out, when {
                    b.kind == "h" -> 14
                    b.kind == "li" && blocks[i - 1].kind == "li" -> 3
                    else -> 9
                })
            }
            val start = out.length
            out.append(Html.fromHtml(b.text, Html.FROM_HTML_MODE_COMPACT).trim())
            val end = out.length
            when (b.kind) {
                "h" -> {
                    out.setSpan(TypefaceSpan(heading), start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(ForegroundColorSpan(accent), start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.92f), start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "li" -> {
                    if (b.depth > 1) out.setSpan(LeadingMarginSpan.Standard((b.depth - 1) * 18.dp), start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BulletSpan(10.dp, if (b.depth > 1) getColor(R.color.ink2) else accent, if (b.depth > 1) 2.dp else 3.dp),
                        start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }

    /** A blank line set in a tiny size: paragraph spacing without disturbing bullet alignment. */
    private fun gap(out: SpannableStringBuilder, dp: Int) {
        val s = out.length
        out.append("​\n")
        out.setSpan(AbsoluteSizeSpan(dp, true), s, out.length, SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun letter(i: Int) = ('A' + i).toString()
}

private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
