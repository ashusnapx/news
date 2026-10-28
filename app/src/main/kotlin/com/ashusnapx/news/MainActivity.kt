package com.ashusnapx.news

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var label: TextView
    private lateinit var state: TextView
    private lateinit var summary: TextView
    private lateinit var refresh: View
    private val spinner by lazy {
        ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private var cols = 1
    private var stories: List<Story> = emptyList()
    private var shown: String? = null // `updated` stamp of the feed on screen
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        val header = layoutInflater.inflate(R.layout.header_feed, list, false)
        label = header.findViewById(R.id.label)
        state = header.findViewById(R.id.state)
        summary = header.findViewById(R.id.summary)
        refresh = header.findViewById(R.id.refresh)
        refresh.setOnClickListener { it.haptic(); load() }
        list.addHeaderView(header, null, false)
        list.adapter = adapter

        // 1 column on phones, 2 on unfolded foldables / landscape, 3 on tablets. Folding recreates the activity.
        val width = windowWidth
        cols = (width / 320.dp).coerceIn(1, 3)
        edgeToEdge { i ->
            val side = 16.dp + gutter(width - i.left - i.right - 32.dp, cols * 440.dp)
            list.setPadding(i.left + side, i.top + 8.dp, i.right + side, i.bottom + 24.dp)
        }

        Feed.current?.let(::show) // instant redraw after rotate/fold
        Looper.myQueue().addIdleHandler { WebView(this).destroy(); false } // warm up Chromium so the first article opens instantly
    }

    override fun onStart() {
        super.onStart()
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        label.text = getString(R.string.today, DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH).format(LocalDate.now(IST)).uppercase())
        spinner.start()
        if (shown == null) summary.setText(R.string.loading)
        Thread {
            val result = runCatching { Feed.fetch(cacheDir) }
            runOnUiThread {
                loading = false
                spinner.cancel()
                refresh.animate().rotation(360f).setDuration(250).withEndAction { refresh.rotation = 0f }
                result.onSuccess(::show).onFailure {
                    if (shown != null) return@onFailure
                    state.setText(R.string.state_offline)
                    state.visibility = View.VISIBLE
                    summary.setText(R.string.offline)
                }
            }
        }.start()
    }

    private fun show(feed: Feed) {
        Feed.current = feed
        val coachings = feed.stories.flatten().distinctBy { it.source }.size
        state.setText(R.string.state_empty)
        state.visibility = if (feed.stories.isEmpty()) View.VISIBLE else View.GONE
        summary.text = if (feed.stories.isEmpty()) getString(R.string.empty) else getString(
            R.string.summary,
            resources.getQuantityString(R.plurals.stories, feed.stories.size, feed.stories.size),
            resources.getQuantityString(R.plurals.coachings, coachings, coachings),
            feed.updated.substring(11, 16),
        )
        if (feed.updated == shown) return
        val first = shown == null
        shown = feed.updated
        stories = feed.stories
        adapter.notifyDataSetChanged()
        if (first) list.scheduleLayoutAnimation()
    }

    /** Row 0 is the most-covered story as a full-bleed feature; the rest are compact cards, [cols] per row. */
    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (stories.isEmpty()) 0 else 1 + (stories.size - 1 + cols - 1) / cols
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position == 0) return (convertView ?: newFeature(parent)).also(::bindFeature)
            val row = convertView as LinearLayout? ?: newRow()
            for (k in 0 until cols) {
                val i = 1 + (position - 1) * cols + k
                val card = row.getChildAt(k)
                card.visibility = if (i < stories.size) View.VISIBLE else View.INVISIBLE
                if (i < stories.size) bind(card, stories[i])
            }
            return row
        }
    }

    private fun newFeature(parent: ViewGroup) = layoutInflater.inflate(R.layout.item_featured, parent, false).apply {
        val card = findViewById<View>(R.id.card)
        card.clipToOutline = true
        card.layoutParams.height = minOf(400.dp, windowManager.currentWindowMetrics.bounds.height() * 3 / 5) // landscape
    }

    private fun bindFeature(v: View) {
        val story = stories[0]
        val lead = story.lead
        val image = v.findViewById<ImageView>(R.id.image)
        v.findViewById<TextView>(R.id.badge).text =
            if (story.size > 1) getString(R.string.covered_by, story.size) else getString(R.string.top_story)
        v.findViewById<TextView>(R.id.title).text = lead.title
        v.findViewById<TextView>(R.id.excerpt).text = lead.excerpt
        v.findViewById<TextView>(R.id.sources).text = getString(R.string.sources_min, story.joinToString("  ·  ") { it.source }, lead.minutes)
        v.findViewById<View>(R.id.more).visibility = if (stories.size > 1) View.VISIBLE else View.GONE
        image.background = sourceGradient(lead.source) // shows through when there is no photo
        Img.load(lead.image, image)
        v.findViewById<View>(R.id.card).setOnClickListener { open(it, image, lead) }
    }

    private class Holder(card: View) {
        val image: ImageView = card.findViewById(R.id.image)
        val mono: TextView = card.findViewById(R.id.mono)
        val sources: TextView = card.findViewById(R.id.sources)
        val title: TextView = card.findViewById(R.id.title)
    }

    private fun newRow() = LinearLayout(this).apply {
        layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        repeat(cols) { k ->
            val card = layoutInflater.inflate(R.layout.item_story, this, false)
            card.clipToOutline = true // rounds the ripple
            card.findViewById<View>(R.id.thumb).clipToOutline = true
            card.tag = Holder(card)
            addView(card, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = 12.dp })
        }
    }

    private fun bind(card: View, story: Story) {
        val h = card.tag as Holder
        val lead = story.lead
        h.sources.text = dots(story, lead.minutes)
        h.sources.contentDescription = story.joinToString { it.source }
        h.title.text = lead.title
        h.mono.text = lead.source.take(1)
        h.mono.background = sourceGradient(lead.source)
        h.image.visibility = if (lead.image == null) View.GONE else View.VISIBLE
        Img.load(lead.image, h.image)
        card.setOnClickListener { open(it, h.image, lead) }
    }

    /** "●●● 3 coachings · 4 min": one colored dot per coaching that covered the story. */
    private fun dots(story: Story, minutes: Int) = SpannableStringBuilder().apply {
        story.forEach { append("●", ForegroundColorSpan(getColor(sourceColor(it.source))), 0) }
        append("  ")
        if (story.size == 1) append(story[0].source, ForegroundColorSpan(getColor(sourceColor(story[0].source))), 0)
        else append(resources.getQuantityString(R.plurals.coachings, story.size, story.size))
        append(getString(R.string.dot_min, minutes))
    }

    private fun open(card: View, image: ImageView, lead: Item) {
        card.haptic()
        val hero = if (image.isShown && image.drawable != null) {
            ActivityOptions.makeSceneTransitionAnimation(this, image, "hero").toBundle()
        } else null
        startActivity(Intent(this, ArticleActivity::class.java).putExtra(ArticleActivity.LINK, lead.link), hero)
    }
}
