package com.ashusnapx.news

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.ashusnapx.news.Store.goal
import java.time.LocalDate

/** Progress and keepsakes: streak, daily goal, twelve weeks of reading, the revision deck, saved stories, highlights. */
class YouActivity : Activity() {
    private lateinit var ring: Ring

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_you)
        ring = findViewById(R.id.ring)
        val back = findViewById<View>(R.id.back)
        back.setOnClickListener { it.haptic(); finish() }
        ring.setOnClickListener {
            it.haptic()
            Analytics.log(this, "goal_change")
            goal = listOf(5, 10, 15, 20).let { g -> g[(g.indexOf(goal) + 1) % g.size] } // 5 -> 10 -> 15 -> 20 -> 5
            render()
            ring.bounce()
        }
        findViewById<View>(R.id.revise).setOnClickListener {
            it.haptic()
            Analytics.log(this, "revise_open")
            startActivity(Intent(this, QuizActivity::class.java).putExtra(QuizActivity.REVISE, true))
        }
        findViewById<View>(R.id.savedClear).setOnClickListener {
            it.haptic()
            confirm(R.string.clear_saved_title) { Store.clearSaved(this); Analytics.log(this, "clear_all", "what" to "saved"); render() }
        }
        findViewById<View>(R.id.highlightsClear).setOnClickListener {
            it.haptic()
            confirm(R.string.clear_highlights_title) { Store.clearHighlights(this); Analytics.log(this, "clear_all", "what" to "highlights"); render() }
        }
        findViewById<Switch>(R.id.stats).apply {
            visibility = View.VISIBLE
            isChecked = Analytics.enabled(this@YouActivity)
            setOnCheckedChangeListener { v, on -> v.haptic(); Analytics.setEnabled(this@YouActivity, on) }
        }
        edgeToEdge { i ->
            val side = 20.dp + gutter(windowWidth - i.left - i.right, 680.dp)
            findViewById<View>(R.id.page).setPadding(i.left + side, i.top + 72.dp, i.right + side, i.bottom + 32.dp)
            (back.layoutParams as MarginLayoutParams).apply { topMargin = i.top + 8.dp; leftMargin = i.left + 8.dp }
            back.requestLayout()
        }
    }

    override fun onStart() {
        super.onStart()
        render() // back from an article or a revision: counts may have moved
    }

    override fun onStop() {
        Analytics.send()
        Store.pruneKept(this) // past the undo window: drop stored stories no highlight points at
        super.onStop()
    }

    private fun render() {
        val name = getSharedPreferences("profile", MODE_PRIVATE).getString("name", null)?.ifBlank { null }
        findViewById<TextView>(R.id.name).text = name ?: getString(R.string.nav_you)
        val today = Store.readOn(this, LocalDate.now())
        ring.label = today.toString()
        ring.set(today / goal.toFloat())
        val streak = Store.streak(this)
        findViewById<TextView>(R.id.streak).text = if (streak > 0) getString(R.string.you_streak, streak) else getString(R.string.you_streak_zero)
        findViewById<TextView>(R.id.goalText).text = getString(R.string.you_goal, today, goal)
        findViewById<View>(R.id.heat).invalidate()

        val due = Store.due(this).size
        val deck = Store.deckSize(this)
        findViewById<TextView>(R.id.deckText).text = if (deck == 0) getString(R.string.deck_empty) else getString(R.string.deck_text, deck, due)
        findViewById<View>(R.id.revise).visibility = if (due > 0) View.VISIBLE else View.GONE

        val saved = Store.saved(this)
        findViewById<TextView>(R.id.savedLabel).text = getString(R.string.saved_label, saved.size)
        findViewById<View>(R.id.savedClear).visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.savedList).apply {
            removeAllViews()
            if (saved.isEmpty()) addView(hint(getString(R.string.saved_empty)))
            saved.forEach { item -> addView(row(item), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp }) }
        }

        val marks = Store.highlights(this).asReversed()
        findViewById<TextView>(R.id.highlightsLabel).text = getString(R.string.highlights_label, marks.size)
        findViewById<View>(R.id.highlightsClear).visibility = if (marks.isEmpty()) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.highlightList).apply {
            removeAllViews()
            if (marks.isEmpty()) addView(hint(getString(R.string.highlights_empty)))
            marks.forEach { h -> addView(quote(h), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10.dp }) }
        }
    }

    /** A saved story, with a trash button over its corner. */
    private fun row(item: Item): View = FrameLayout(this).apply {
        val card = story(item)
        addView(card)
        addView(ImageButton(this@YouActivity).apply {
            setImageResource(R.drawable.ic_trash)
            background = getDrawable(R.drawable.round_btn)
            contentDescription = getString(R.string.delete_saved)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(11.dp, 11.dp, 11.dp, 11.dp)
            setOnClickListener {
                it.haptic()
                removeWithUndo(this@apply, undo = { Store.toggleSave(this@YouActivity, item) }) { Store.toggleSave(this@YouActivity, item) }
            }
        }, FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.END).apply { setMargins(0, 8.dp, 8.dp, 0) })
    }

    private fun story(item: Item): View = layoutInflater.inflate(R.layout.item_story, findViewById(R.id.savedList), false).apply {
        clipToOutline = true
        findViewById<View>(R.id.thumb).clipToOutline = true
        findViewById<TextView>(R.id.title).text = item.title
        findViewById<TextView>(R.id.sources).text = getString(R.string.min_short, item.minutes)
        findViewById<TextView>(R.id.mono).apply { text = item.title.take(1); background = sourceGradient(item.source) }
        val image = findViewById<ImageView>(R.id.image)
        image.visibility = if (item.image == null) View.GONE else View.VISIBLE
        Img.load(item.image, image)
        setOnClickListener {
            it.haptic()
            startActivity(Intent(this@YouActivity, ArticleActivity::class.java).putExtra(ArticleActivity.LINK, item.link).putExtra(ArticleActivity.SAVED, true))
        }
    }

    private fun quote(h: Store.Highlight): View = layoutInflater.inflate(R.layout.item_highlight, findViewById(R.id.highlightList), false).apply {
        findViewById<TextView>(R.id.quote).text = h.text
        findViewById<TextView>(R.id.from).text = h.title
        val row = this
        setOnClickListener {
            it.haptic()
            startActivity(Intent(this@YouActivity, ArticleActivity::class.java).putExtra(ArticleActivity.LINK, h.link).putExtra(ArticleActivity.KEPT, true))
        }
        findViewById<View>(R.id.delete).setOnClickListener {
            it.haptic()
            removeWithUndo(row, undo = { Store.restoreHighlight(this@YouActivity, h) }) { Store.removeHighlight(this@YouActivity, h) }
        }
    }

    /** Fade the row out, delete, and offer Undo for six seconds. */
    private fun removeWithUndo(view: View, undo: () -> Unit, delete: () -> Unit) {
        delete()
        view.animate().alpha(0f).translationX(view.width / 3f).setDuration(220).withEndAction { render() }
        val pill = findViewById<View>(R.id.undo)
        pill.removeCallbacks(hideUndo)
        pill.setOnClickListener {
            it.haptic()
            undo()
            pill.visibility = View.GONE
            render()
        }
        if (pill.visibility != View.VISIBLE) {
            pill.visibility = View.VISIBLE
            pill.translationY = 120.dp.toFloat()
            pill.animate().translationY(-24.dp.toFloat()).setDuration(240)
        }
        pill.postDelayed(hideUndo, 6000)
    }

    private val hideUndo = Runnable {
        findViewById<View>(R.id.undo).apply { animate().translationY(120.dp.toFloat()).setDuration(200).withEndAction { visibility = View.GONE } }
    }

    private fun confirm(title: Int, yes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(R.string.clear_body)
            .setPositiveButton(R.string.clear_yes) { _, _ -> yes() }
            .setNegativeButton(R.string.clear_no, null)
            .show()
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.ink2))
        textSize = 13.5f
        setLineSpacing(3.dp.toFloat(), 1f)
    }
}
