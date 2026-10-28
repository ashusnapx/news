package com.ashusnapx.news

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TOP = 5 // stories in the carousel
private const val AVATAR = "https://avatars.githubusercontent.com/u/75922918?v=4&s=192"

class MainActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var state: TextView
    private lateinit var summary: TextView
    private lateinit var refresh: View
    private lateinit var navItems: Map<String, View> // prelims, mains
    private lateinit var navQuiz: View
    private lateinit var ring: Ring
    private lateinit var gsScroll: View
    private lateinit var gsChips: LinearLayout
    private var paperFilter: String? = null // "GS1".."GS4", or null for all
    private lateinit var readRow: View
    private lateinit var readText: TextView
    private lateinit var readBar: View
    private lateinit var carousel: Carousel
    private lateinit var greetTitle: TextView
    private lateinit var greetSub: TextView
    private val profile by lazy { getSharedPreferences("profile", MODE_PRIVATE) }
    private val spinner by lazy {
        ObjectAnimator.ofFloat(refresh, View.ROTATION, 0f, 360f).apply { // on the pull badge
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private var cols = 1
    private var tab = "prelims" // or "mains"
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
        state = header.findViewById(R.id.state)
        summary = header.findViewById(R.id.summary)
        refresh = findViewById(R.id.pull)
        pullToRefresh()
        navItems = mapOf(
            "prelims" to navItem(R.id.navPrelims, R.drawable.ic_prelims, R.string.prelims),
            "mains" to navItem(R.id.navMains, R.drawable.ic_mains, R.string.mains),
        )
        navItems.forEach { (kind, item) -> item.setOnClickListener { it.haptic(); selectTab(kind) } }
        navQuiz = navItem(R.id.navQuiz, R.drawable.ic_quiz, R.string.nav_quiz).apply {
            findViewById<ImageView>(R.id.navIcon).imageTintList = ColorStateList.valueOf(getColor(R.color.dock_ink))
            setOnClickListener {
                if (Feed.current?.quiz.isNullOrEmpty() && Store.due(this@MainActivity).isEmpty()) return@setOnClickListener
                it.haptic()
                it.bounce()
                Analytics.log(this@MainActivity, "quiz_open", "from" to "dock")
                startActivity(Intent(this@MainActivity, QuizActivity::class.java))
            }
        }
        ring = header.findViewById(R.id.ring)
        ring.setOnClickListener { it.haptic(); startActivity(Intent(this, YouActivity::class.java)) }
        gsScroll = header.findViewById(R.id.gsScroll)
        gsChips = header.findViewById(R.id.gsChips)
        navItem(R.id.navYou, R.drawable.ic_you, R.string.nav_you).apply {
            findViewById<ImageView>(R.id.navIcon).imageTintList = ColorStateList.valueOf(getColor(R.color.dock_ink))
            setOnClickListener { it.haptic(); it.bounce(); startActivity(Intent(this@MainActivity, YouActivity::class.java)) }
        }
        readRow = header.findViewById(R.id.readRow)
        readText = header.findViewById(R.id.readText)
        readBar = header.findViewById(R.id.readBar)
        header.findViewById<View>(R.id.readTrack).clipToOutline = true
        carousel = Carousel(this, layoutInflater.inflate(R.layout.item_carousel, list, false)) { v, image, lead -> open(v, image, lead) }
        greetTitle = header.findViewById(R.id.greetTitle)
        greetSub = header.findViewById(R.id.greetSub)
        header.findViewById<View>(R.id.greetRow).setOnLongClickListener { it.haptic(); welcome(); true } // change the name
        val returning = profile.getBoolean("askedNotify", false)
        if (!profile.contains("name")) welcome() else askNotifications()
        if (returning) askStats() // one prompt per launch: notifications first, stats on a later open
        Analytics.log(this, "app_open", "streak" to Store.streak(this))
        intent.getStringExtra(Reminder.FROM)?.let { Analytics.log(this, "notification_open", "slot" to it) }
        Reminder.schedule(this)

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
            list.setPadding(i.left + side, i.top + 8.dp, i.right + side, i.bottom + 112.dp) // room above the bottom bar
            findViewById<View>(R.id.nav).apply {
                // Absolute margins: start/end set after attach are not re-resolved, so the bar went edge to edge.
                (layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, i.bottom + 14.dp)
                requestLayout()
            }
            findViewById<View>(R.id.scrim).apply { layoutParams.height = i.top + 28.dp; requestLayout() }
            (refresh.layoutParams as ViewGroup.MarginLayoutParams).topMargin = i.top + 8.dp
            refresh.requestLayout()
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
        Reminder.opened(this)
        reads = Reads.all(this) // coming back from an article: dim it, move the progress bar
        adapter.notifyDataSetChanged()
        progress()
        carousel.resume()
        load()
    }

    override fun onStop() {
        Analytics.flush(this)
        carousel.pause()
        super.onStop()
    }

    /**
     * Pull down at the top of the list: the badge follows the finger and turns; past [threshold] a tick, and
     * letting go refreshes. Moves are taken from the list only while pulling, so ordinary scrolling is untouched.
     */
    private fun pullToRefresh() {
        val threshold = 88.dp.toFloat()
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var start = -1f
        var pulling = false
        var armed = false
        fun atTop() = list.firstVisiblePosition == 0 && (list.getChildAt(0)?.top ?: 0) >= list.paddingTop
        list.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = if (atTop() && !loading) e.rawY else -1f
                    pulling = false
                    armed = false
                }
                MotionEvent.ACTION_MOVE -> if (start >= 0) {
                    val d = e.rawY - start
                    if (!pulling && d > slop && atTop()) pulling = true
                    if (pulling) {
                        if (d <= 0) { pulling = false; start = -1f; refresh.animate().translationY(-64.dp.toFloat()).alpha(0f); return@setOnTouchListener false }
                        val p = (d * 0.55f).coerceAtMost(threshold * 1.3f) // resistance, like every other app
                        refresh.translationY = p - 52.dp
                        refresh.alpha = (p / threshold).coerceAtMost(1f)
                        refresh.rotation = p * 2.5f
                        if (!armed && p >= threshold) { armed = true; refresh.haptic(); refresh.bounce() }
                        if (armed && p < threshold) armed = false
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                    pulling = false
                    if (armed) { Analytics.log(this, "refresh"); load() }
                    else refresh.animate().translationY(-64.dp.toFloat()).alpha(0f).setDuration(200)
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun load() {
        if (loading) return
        loading = true
        greet()
        spinner.start()
        if (shown == null) {
            summary.setText(R.string.loading)
            summary.visibility = View.VISIBLE
        }
        Thread {
            val result = runCatching { Feed.fetch(cacheDir) }
            runOnUiThread {
                loading = false
                spinner.cancel()
                refresh.animate().translationY(-64.dp.toFloat()).alpha(0f).rotation(0f).setDuration(260) // tuck the badge away
                result.onSuccess(::show).onFailure {
                    if (shown != null) return@onFailure
                    state.setText(R.string.state_offline)
                    state.visibility = View.VISIBLE
                    summary.setText(R.string.offline)
                    summary.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun show(feed: Feed) {
        Feed.current = feed
        val empty = feed.stories.isEmpty()
        state.setText(R.string.state_empty)
        summary.setText(R.string.empty)
        state.visibility = if (empty) View.VISIBLE else View.GONE
        summary.visibility = state.visibility
        val n = feed.quiz.size
        navQuiz.alpha = if (n == 0) 0.4f else 1f // stays in place so the bar never shifts
        navQuiz.findViewById<TextView>(R.id.navBadge).apply {
            text = n.toString()
            visibility = if (n == 0) View.GONE else View.VISIBLE
        }
        navQuiz.contentDescription = getString(R.string.nav_quiz_desc, resources.getQuantityString(R.plurals.questions, n, n))
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
        if (animate) Analytics.log(this, "tab", "tab" to kind)
        navItems.forEach { (k, item) -> paintNav(item, k == kind, animate, if (k == "mains") R.drawable.ic_mains_on else R.drawable.ic_prelims_on) }
        if (!animate) return
        navItems.getValue(kind).bounce()
        list.setSelection(0) // a new tab starts at the top
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

    private fun navItem(id: Int, icon: Int, label: Int): View = findViewById<View>(id).apply {
        findViewById<ImageView>(R.id.navIcon).setImageResource(icon)
        findViewById<TextView>(R.id.navLabel).setText(label)
        contentDescription = getString(label)
        background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(Color.TRANSPARENT) }
    }

    /** Selected: the item grows into an accent pill with its label; the others shrink back to an icon. */
    private fun paintNav(item: View, on: Boolean, animate: Boolean, filledIcon: Int) {
        if (animate) TransitionManager.beginDelayedTransition(findViewById(R.id.nav), AutoTransition().setDuration(220))
        val accent = getColor(R.color.accent)
        val pill = item.background as GradientDrawable
        val to = if (on) accent else accent and 0xFFFFFF
        if (animate) {
            ValueAnimator.ofArgb(pill.color?.defaultColor ?: to, to).apply {
                duration = 220
                addUpdateListener { pill.setColor(it.animatedValue as Int) }
                start()
            }
        } else pill.setColor(to)
        val tint = getColor(if (on) R.color.on_src else R.color.dock_ink2)
        item.findViewById<ImageView>(R.id.navIcon).apply {
            setImageResource(if (on) filledIcon else if (filledIcon == R.drawable.ic_mains_on) R.drawable.ic_mains else R.drawable.ic_prelims)
            imageTintList = ColorStateList.valueOf(tint)
        }
        item.findViewById<TextView>(R.id.navLabel).apply {
            setTextColor(tint)
            visibility = if (on) View.VISIBLE else View.GONE
        }
        item.isSelected = on
    }

    private fun filter() {
        val inTab = Feed.current?.stories.orEmpty().filter { s -> s.any { it.kind == tab } }
        chips(inTab)
        stories = inTab.filter { s -> paperFilter == null || s.any { paperFilter in it.gs } }
        caughtUp = false
        adapter.notifyDataSetChanged()
        progress()
    }

    /**
     * "All · GS1 · GS2 · GS3 · GS4": always all four, so a missing paper reads as "none today", not as a bug.
     * An empty paper is dimmed and says so when tapped. No counts: "GS1 4" read as "GS14".
     */
    private fun chips(inTab: List<Story>) {
        val counts = listOf("GS1", "GS2", "GS3", "GS4").associateWith { p -> inTab.count { s -> s.any { p in it.gs } } }
        if (paperFilter != null && counts[paperFilter] == 0) paperFilter = null
        gsScroll.visibility = if (inTab.isEmpty()) View.GONE else View.VISIBLE
        gsChips.removeAllViews()
        (listOf<String?>(null) + counts.keys).forEach { p ->
            val empty = p != null && counts[p] == 0
            newPill(gsChips, p ?: getString(R.string.gs_all)) {
                if (empty) return@newPill Toast.makeText(this, getString(R.string.gs_none, p), Toast.LENGTH_SHORT).show()
                if (paperFilter == p) return@newPill
                paperFilter = p
                Analytics.log(this, "gs_filter", "paper" to (p ?: "all"))
                filter()
                list.setSelection(0)
            }.apply {
                pill(p == paperFilter, getColor(R.color.accent), animate = false)
                alpha = if (empty) 0.4f else 1f
            }
        }
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
        greetContext()
        val today = Store.readOn(this, LocalDate.now())
        ring.label = today.toString()
        ring.set(today / Store.run { this@MainActivity.goal }.toFloat())
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
        val name = profile.getString("name", null)?.ifBlank { null } ?: getString(R.string.hello_reader)
        greetTitle.text = "${getString(hello)} $name $emoji"
        greetContext()
    }

    /** "Wed, 28 Oct · 20 unread", or "all read ✓" once the day's stories are done. */
    private fun greetContext() {
        val date = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH).format(LocalDate.now(IST))
        val all = Feed.current?.stories.orEmpty()
        val unread = all.count { !it.read() }
        val streak = Store.streak(this).let { if (it > 0) "  ·  🔥 $it" else "" }
        greetSub.text = if (all.isEmpty()) date + streak
            else getString(R.string.greet_sub, date + streak, if (unread == 0) getString(R.string.greet_done) else getString(R.string.unread, unread))
    }

    /** Consent for anonymous stats: asked once, only when analytics is configured, never again whatever the answer. */
    private fun askStats() {
        if (!BuildConfig.GA_ID.isNotEmpty() || Analytics.asked(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_title)
            .setMessage(R.string.stats_body)
            .setPositiveButton(R.string.stats_yes) { _, _ -> Analytics.setEnabled(this, true); Analytics.log(this, "stats_on") }
            .setNegativeButton(R.string.stats_no) { _, _ -> Analytics.setEnabled(this, false) }
            .setCancelable(false)
            .show()
    }

    /** Asked once, after the name, never again whatever the answer. */
    private fun askNotifications() {
        if (profile.getBoolean("askedNotify", false)) return
        profile.edit().putBoolean("askedNotify", true).apply()
        // Android 11-12 have no runtime notification permission: notifications are simply on.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
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
            screen.animate().alpha(0f).setDuration(260).withEndAction { screen.visibility = View.GONE; askNotifications() }
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
        card.background = getDrawable(if (read) R.drawable.card_read else R.drawable.card)
        h.mono.text = lead.title.take(1)
        h.mono.background = sourceGradient(lead.source)
        h.image.visibility = if (lead.image == null) View.GONE else View.VISIBLE
        Img.load(lead.image, h.image)
        card.setOnClickListener { open(it, h.image, lead) }
        card.setOnLongClickListener {
            it.haptic()
            it.bounce()
            val now = Store.toggleSave(this, lead)
            Analytics.log(this, if (now) "save" else "unsave", "from" to "feed")
            Toast.makeText(this, if (now) R.string.saved_toast else R.string.unsaved_toast, Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** "4 min · 3 sources · ✓ Read". No coaching names on the front page; the tab already says prelims or mains. */
    private fun meta(story: Story, read: Boolean) = SpannableStringBuilder().apply {
        append(getString(R.string.min_short, story.lead.minutes))
        if (story.size > 1) append(getString(R.string.dot)).append(resources.getQuantityString(R.plurals.sources, story.size, story.size))
        if (read) append(getString(R.string.read_mark), ForegroundColorSpan(getColor(R.color.good)), 0)
    }

    private fun open(card: View, image: ImageView, lead: Item) {
        card.haptic()
        Analytics.log(this, "article_open", "source" to lead.source, "kind" to lead.kind, "gs" to lead.gs.joinToString(","), "from" to "feed")
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
