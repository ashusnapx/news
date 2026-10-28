package com.ashusnapx.news

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val KINDS = listOf("prelims" to R.string.prelims, "mains" to R.string.mains)
private const val TOP = 5 // stories in the carousel
private const val AVATAR = "https://avatars.githubusercontent.com/u/75922918?v=4&s=192"

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var label: TextView
    private lateinit var state: TextView
    private lateinit var summary: TextView
    private lateinit var refresh: View
    private lateinit var quizCard: View
    private lateinit var quizTitle: TextView
    private lateinit var quizMeta: TextView
    private lateinit var tabs: View
    private lateinit var tabPills: List<TextView>
    private lateinit var readRow: View
    private lateinit var readText: TextView
    private lateinit var readBar: View
    private lateinit var carousel: Carousel
    private lateinit var greetEmoji: TextView
    private lateinit var greetHello: TextView
    private lateinit var greetName: TextView
    private lateinit var greetLine: TextView
    private val profile by lazy { getSharedPreferences("profile", MODE_PRIVATE) }
    private val spinner by lazy {
        ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private var cols = 1
    private var tab = KINDS[0].first
    private var stories: List<Story> = emptyList()
    private var reads: Set<String> = emptySet()
    private var caughtUp = false
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
        quizCard = header.findViewById(R.id.quizCard)
        quizTitle = header.findViewById(R.id.quizTitle)
        quizMeta = header.findViewById(R.id.quizMeta)
        quizCard.setOnClickListener { it.haptic(); startActivity(Intent(this, QuizActivity::class.java)) }
        tabs = header.findViewById(R.id.tabs)
        tabPills = KINDS.map { (kind, _) ->
            newPill(tabs as LinearLayout, "") { selectTab(kind) }.apply { layoutParams = LinearLayout.LayoutParams(0, 40.dp, 1f) }
        }
        readRow = header.findViewById(R.id.readRow)
        readText = header.findViewById(R.id.readText)
        readBar = header.findViewById(R.id.readBar)
        header.findViewById<View>(R.id.readTrack).clipToOutline = true
        carousel = Carousel(this, layoutInflater.inflate(R.layout.item_carousel, list, false)) { v, image, lead -> open(v, image, lead) }
        greetEmoji = header.findViewById(R.id.greetEmoji)
        greetHello = header.findViewById(R.id.greetHello)
        greetName = header.findViewById(R.id.greetName)
        greetLine = header.findViewById(R.id.greetLine)
        header.findViewById<View>(R.id.greetCard).setOnLongClickListener { it.haptic(); welcome(); true } // change the name
        if (!profile.contains("name")) welcome()

        tab = savedInstanceState?.getString("tab") ?: tab
        selectTab(tab, animate = false)
        list.addHeaderView(header, null, false)
        list.addFooterView(footer(), null, false)
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                if (first > 1) carousel.pause() else carousel.resume() // header is 0, carousel 1
            }
        })

        // 1 column on phones, 2 on unfolded foldables / landscape, 3 on tablets. Folding recreates the activity.
        val width = windowWidth
        cols = (width / 320.dp).coerceIn(1, 3)
        edgeToEdge { i ->
            val side = 16.dp + gutter(width - i.left - i.right - 32.dp, cols * 440.dp)
            list.setPadding(i.left + side, i.top + 8.dp, i.right + side, i.bottom + 24.dp)
            findViewById<View>(R.id.scrim).apply { layoutParams.height = i.top + 28.dp; requestLayout() }
        }

        Feed.current?.let(::show) // instant redraw after rotate/fold
        Looper.myQueue().addIdleHandler { WebView(this).destroy(); false } // warm up Chromium so the first article opens instantly
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab)
    }

    override fun onStart() {
        super.onStart()
        reads = Reads.all(this) // coming back from an article: dim it, move the progress bar
        adapter.notifyDataSetChanged()
        progress()
        carousel.resume()
        load()
    }

    override fun onStop() {
        carousel.pause()
        super.onStop()
    }

    private fun load() {
        if (loading) return
        loading = true
        label.text = getString(R.string.today, DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH).format(LocalDate.now(IST)).uppercase())
        greet()
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
        val n = feed.quiz.size
        quizCard.visibility = if (n == 0) View.GONE else View.VISIBLE
        quizTitle.text = resources.getQuantityString(R.plurals.questions, n, n)
        quizMeta.text = getString(R.string.quiz_meta, (n * 6 + 4) / 5) // 72 s a question, the exam's pace
        tabs.visibility = if (feed.stories.isEmpty()) View.GONE else View.VISIBLE
        KINDS.forEachIndexed { i, (kind, label) ->
            tabPills[i].text = getString(R.string.tab, getString(label), feed.stories.count { s -> s.any { it.kind == kind } })
        }
        if (feed.updated == shown) return
        val first = shown == null
        shown = feed.updated
        Reads.prune(this, feed)
        reads = Reads.all(this)
        filter()
        if (first) list.scheduleLayoutAnimation()
    }

    /** Prelims / Mains: a story shows under a tab when any coaching filed it there. */
    private fun selectTab(kind: String, animate: Boolean = true) {
        if (animate && kind == tab) return
        tab = kind
        KINDS.forEachIndexed { i, (k, _) -> tabPills[i].pill(k == kind, getColor(R.color.accent), animate) }
        if (!animate) return
        tabPills[KINDS.indexOfFirst { it.first == kind }].bounce()
        filter()
        // Only the rows below the header rise in; the header, tabs and quiz card stay put.
        list.post {
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                if (list.getPositionForView(row) < list.headerViewsCount) continue
                row.alpha = 0f
                row.translationY = 28.dp.toFloat()
                row.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(300).setInterpolator(DecelerateInterpolator())
            }
        }
    }

    private fun filter() {
        stories = Feed.current?.stories.orEmpty().filter { s -> s.any { it.kind == tab } }
        caughtUp = false
        adapter.notifyDataSetChanged()
        progress()
    }

    private fun Story.read() = any { it.link in reads }

    /** "3 of 22 read" with a filling bar; finishing a tab gets a small celebration. */
    private fun progress() {
        readRow.visibility = if (stories.isEmpty()) View.GONE else View.VISIBLE
        val done = stories.count { it.read() }
        val all = done == stories.size && done > 0
        readText.text = if (all) getString(R.string.caught_up) else getString(R.string.reads, done, stories.size)
        readText.setTextColor(getColor(if (all) R.color.good else R.color.ink2))
        readBar.setBackgroundColor(getColor(if (all) R.color.good else R.color.accent))
        readBar.animate().scaleX(if (stories.isEmpty()) 0f else done / stories.size.toFloat()).setDuration(500)
        val unread = Feed.current?.stories.orEmpty().count { !it.read() }
        greetLine.text = if (unread == 0 && done > 0) getString(R.string.greet_done)
            else getString(R.string.greet_line, resources.getQuantityString(R.plurals.stories, unread, unread))
        if (all && !caughtUp) {
            readText.bounce()
            readText.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        caughtUp = all
    }

    /** Greeting by the hour in IST, with the name given on first launch. */
    private fun greet() {
        val (emoji, hello) = when (java.time.LocalTime.now(IST).hour) {
            in 5..11 -> "🌅" to R.string.hello_morning
            in 12..16 -> "☀️" to R.string.hello_afternoon
            in 17..20 -> "🌆" to R.string.hello_evening
            else -> "🌙" to R.string.hello_night
        }
        greetEmoji.text = emoji
        greetHello.setText(hello)
        greetName.text = profile.getString("name", null)?.ifBlank { null } ?: getString(R.string.hello_reader)
    }

    /** One question on first launch; long-press the greeting to change the answer. Skipping counts as answered. */
    private fun welcome() {
        val screen = findViewById<View>(R.id.welcome)
        val body = findViewById<View>(R.id.welcomeBody)
        val input = findViewById<EditText>(R.id.nameInput)
        screen.setOnApplyWindowInsetsListener { _, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            val side = 32.dp + gutter(windowWidth - b.left - b.right, 520.dp)
            body.setPadding(b.left + side, b.top + 24.dp, b.right + side, b.bottom + 24.dp) // rides up with the keyboard
            i
        }
        input.setText(profile.getString("name", ""))
        screen.visibility = View.VISIBLE
        screen.alpha = 1f
        screen.requestApplyInsets()
        input.requestFocus()
        input.post { window.insetsController?.show(WindowInsets.Type.ime()) }
        fun done(name: String) {
            // Plain text only: printable characters, trimmed, capped (also capped by maxLength in the layout).
            profile.edit().putString("name", name.filter { !it.isISOControl() }.trim().take(30)).apply()
            window.insetsController?.hide(WindowInsets.Type.ime())
            greet()
            screen.animate().alpha(0f).setDuration(260).withEndAction { screen.visibility = View.GONE }
        }
        findViewById<View>(R.id.nameGo).setOnClickListener { it.haptic(); done(input.text.toString()) }
        findViewById<View>(R.id.nameSkip).setOnClickListener { it.haptic(); done("") }
        input.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) done(input.text.toString()) }
        }
    }

    /** Row 0 is the carousel of the [TOP] most-covered stories; the rest are compact cards, [cols] per row. */
    private val adapter = object : BaseAdapter() {
        private val rest get() = stories.drop(TOP)
        override fun getCount() = if (stories.isEmpty()) 0 else 1 + (rest.size + cols - 1) / cols
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (position == 0) {
                carousel.bind(stories.take(TOP))
                carousel.view.findViewById<View>(R.id.more).visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
                return carousel.view
            }
            val row = convertView as LinearLayout? ?: newRow()
            for (k in 0 until cols) {
                val i = (position - 1) * cols + k
                val card = row.getChildAt(k)
                card.visibility = if (i < rest.size) View.VISIBLE else View.INVISIBLE
                if (i < rest.size) bind(card, rest[i])
            }
            return row
        }
    }

    private class Holder(card: View) {
        val image: ImageView = card.findViewById(R.id.image)
        val thumb: View = card.findViewById(R.id.thumb)
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
        val read = story.read()
        h.sources.text = meta(story, read)
        h.title.text = lead.title
        h.title.alpha = if (read) 0.55f else 1f
        h.thumb.alpha = if (read) 0.55f else 1f
        h.mono.text = lead.title.take(1)
        h.mono.background = sourceGradient(lead.source)
        h.image.visibility = if (lead.image == null) View.GONE else View.VISIBLE
        Img.load(lead.image, h.image)
        card.setOnClickListener { open(it, h.image, lead) }
    }

    /** "4 min · 3 sources · ✓ Read". No coaching names on the front page; the tab already says prelims or mains. */
    private fun meta(story: Story, read: Boolean) = SpannableStringBuilder().apply {
        append(getString(R.string.min_short, story.lead.minutes))
        if (story.size > 1) append(getString(R.string.dot)).append(resources.getQuantityString(R.plurals.sources, story.size, story.size))
        if (read) append(getString(R.string.read_mark), ForegroundColorSpan(getColor(R.color.good)), 0)
    }

    private fun open(card: View, image: ImageView, lead: Item) {
        card.haptic()
        val hero = if (image.isShown && image.drawable != null) {
            ActivityOptions.makeSceneTransitionAnimation(this, image, "hero").toBundle()
        } else null
        startActivity(Intent(this, ArticleActivity::class.java).putExtra(ArticleActivity.LINK, lead.link).putExtra(ArticleActivity.TAB, tab), hero)
    }

    private fun footer() = layoutInflater.inflate(R.layout.footer_feed, list, false).apply {
        val avatar = findViewById<ImageView>(R.id.avatar)
        avatar.clipToOutline = true
        Img.load(AVATAR, avatar)
        fun wire(v: View) {
            if (v is ViewGroup) for (i in 0 until v.childCount) wire(v.getChildAt(i))
            else (v.tag as? String)?.let { url -> v.setOnClickListener { it.haptic(); browse(Uri.parse(url)) } }
        }
        wire(this)
    }
}
