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
                result.onSuccess(::show).onFailure { if (shown == null) summary.setText(R.string.offline) }
            }
        }.start()
    }

    private fun show(feed: Feed) {
        Feed.current = feed
        val coachings = feed.stories.flatten().distinctBy { it.source }.size
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

    private val adapter = object : BaseAdapter() {
        override fun getCount() = (stories.size + cols - 1) / cols
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as LinearLayout? ?: newRow()
            for (k in 0 until cols) {
                val i = position * cols + k
                val card = row.getChildAt(k)
                card.visibility = if (i < stories.size) View.VISIBLE else View.INVISIBLE
                if (i < stories.size) bind(card, stories[i])
            }
            return row
        }
    }

    private class Holder(card: View) {
        val image: ImageView = card.findViewById(R.id.image)
        val sources: TextView = card.findViewById(R.id.sources)
        val title: TextView = card.findViewById(R.id.title)
        val excerpt: TextView = card.findViewById(R.id.excerpt)
        val meta: TextView = card.findViewById(R.id.meta)
    }

    private fun newRow() = LinearLayout(this).apply {
        layoutParams = AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        repeat(cols) { k ->
            val card = layoutInflater.inflate(R.layout.item_story, this, false)
            card.clipToOutline = true // rounds the image corners and the ripple
            card.tag = Holder(card)
            addView(card, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (k > 0) marginStart = 14.dp })
        }
    }

    private fun bind(card: View, story: Story) {
        val h = card.tag as Holder
        val lead = story.lead
        h.sources.text = dots(story)
        h.sources.contentDescription = story.joinToString { it.source }
        h.title.text = lead.title
        h.excerpt.text = lead.excerpt
        h.meta.text = getString(R.string.min_read, lead.minutes)
        h.image.visibility = if (lead.image == null) View.GONE else View.VISIBLE
        Img.load(lead.image, h.image)
        card.setOnClickListener { open(it, h.image, lead) }
    }

    /** "●●● 3 coachings" — one colored dot per coaching that covered the story. */
    private fun dots(story: Story) = SpannableStringBuilder().apply {
        story.forEach { append("●", ForegroundColorSpan(getColor(sourceColor(it.source))), 0) }
        append("  ")
        if (story.size == 1) append(story[0].source, ForegroundColorSpan(getColor(sourceColor(story[0].source))), 0)
        else append(resources.getQuantityString(R.plurals.coachings, story.size, story.size))
    }

    private fun open(card: View, image: View, lead: Item) {
        card.haptic()
        val hero = if (image.isShown) ActivityOptions.makeSceneTransitionAnimation(this, image, "hero").toBundle() else null
        startActivity(Intent(this, ArticleActivity::class.java).putExtra(ArticleActivity.LINK, lead.link), hero)
    }
}
