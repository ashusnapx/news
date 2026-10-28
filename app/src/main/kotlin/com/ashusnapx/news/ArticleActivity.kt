package com.ashusnapx.news

import android.app.Activity
import android.app.SharedElementCallback
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.DisplayMetrics
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.content.Intent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class ArticleActivity : Activity() {
    companion object {
        const val LINK = "link"
        const val TAB = "tab"
        private val IMAGE = Regex("""\.(jpe?g|png|webp|gif)$""", RegexOption.IGNORE_CASE)

        // App fonts served to the WebView under a host that never reaches the network.
        private const val FONT_HOST = "appassets.androidplatform.net"
        private val FONTS = mapOf(
            "literata_regular" to R.font.literata_regular,
            "literata_semibold" to R.font.literata_semibold,
            "jakarta_bold" to R.font.jakarta_bold,
        )
    }

    private lateinit var story: Story
    private lateinit var stories: List<Story> // the tab the reader came from, for swiping between stories
    private lateinit var pills: List<TextView>
    private lateinit var scroll: ScrollView
    private lateinit var hero: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var bar: TextView
    private lateinit var progress: View
    private lateinit var body: FrameLayout
    private lateinit var toggle: HorizontalScrollView
    private lateinit var track: LinearLayout
    private lateinit var upNext: View
    private var web: WebView? = null
    private var heroUrl: String? = null
    private var hasHero = false
    private var selected = -1
    private var barShown = false
    private var switched = false
    private lateinit var sheet: View
    private lateinit var paperBtn: ImageView
    private val prefs by lazy { getSharedPreferences("profile", MODE_PRIVATE) }
    private var paper = false // reading on paper (warm stock, grain, sepia ink) instead of the screen theme // swiped to another story: the hero no longer belongs to the card we came from

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra(LINK)
        val tab = intent.getStringExtra(TAB)
        // shortcut: the feed lives in memory only; after process death we fall back to the list, which refetches
        stories = Feed.current?.stories.orEmpty().filter { s -> tab == null || s.any { it.kind == tab } }
        story = stories.find { s -> s.any { it.link == link } } ?: return finish()
        setContentView(R.layout.activity_article)
        scroll = findViewById(R.id.scroll)
        hero = findViewById(R.id.hero)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        bar = findViewById(R.id.bar)
        progress = findViewById(R.id.progress)
        body = findViewById(R.id.body)
        toggle = findViewById(R.id.toggleScroll)
        track = findViewById(R.id.toggle)
        upNext = findViewById(R.id.upNext)
        sheet = findViewById(R.id.sheet)
        paperBtn = findViewById(R.id.paperBtn)
        paper = prefs.getBoolean("paper", false)
        paperBtn.setOnClickListener { it.haptic(); togglePaper() }
        val nextWrap = findViewById<View>(R.id.nextWrap)
        val back = findViewById<View>(R.id.back)
        hero.layoutParams.height = minOf(300.dp, windowManager.currentWindowMetrics.bounds.height() * 2 / 5) // landscape, folds
        upNext.clipToOutline = true
        findViewById<View>(R.id.nextThumb).clipToOutline = true

        back.setOnClickListener { it.haptic(); finishAfterTransition() }
        hero.setOnClickListener { v -> heroUrl?.let { v.haptic(); viewImage(it) } }
        meta.setOnClickListener { it.haptic(); browse(Uri.parse(story[selected].link)) }
        upNext.setOnClickListener { go(+1) }
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>, sharedElements: MutableMap<String, View>) {
                if (switched) { names.clear(); sharedElements.clear() } // plain fade back instead of flying to the wrong card
            }
        })

        // Reading column stays <= 720dp on unfolded foldables, tablets and landscape.
        edgeToEdge { i ->
            val side = gutter(windowWidth - i.left - i.right, 720.dp)
            sheet.setPadding(i.left + side + 20.dp, 24.dp + if (hasHero) 0 else i.top + 56.dp, i.right + side + 20.dp, 0)
            body.setPadding(i.left + side, 0, i.right + side, 0)
            nextWrap.setPadding(i.left + side + 20.dp, 8.dp, i.right + side + 20.dp, i.bottom + 28.dp)
            bar.minHeight = i.top + 56.dp
            bar.setPadding(i.left + 72.dp, i.top, i.right + 20.dp, 0)
            (back.layoutParams as MarginLayoutParams).apply { topMargin = i.top + 4.dp; marginStart = i.left + 8.dp }
            (paperBtn.layoutParams as MarginLayoutParams).setMargins(0, i.top + 4.dp, i.right + 8.dp, 0)
            paperBtn.requestLayout()
            (progress.layoutParams as MarginLayoutParams).topMargin = i.top
            back.requestLayout()
            progress.requestLayout()
        }

        // Parallax hero; a solid top bar with the title fades in once the hero scrolls away.
        val content = scroll.getChildAt(0)
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            hero.translationY = y * 0.45f
            progress.scaleX = (y / (content.height - scroll.height).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val start = if (hasHero) hero.height - bar.height else 0
            bar.alpha = ((y - start) / 48.dp.toFloat()).coerceIn(0f, 1f)
            if ((bar.alpha > 0.5f) != barShown) {
                barShown = !barShown
                statusIcons(onSurface = barShown || !hasHero)
            }
        }

        surface()
        open(story)
    }

    /** Everything that belongs to one story: hero, coaching pills, the article, and what comes next. */
    private fun open(s: Story) {
        story = s
        intent.putExtra(LINK, s.lead.link) // a rotation reopens this story, not the one we started on
        Reads.mark(this, s)
        val lead = s.lead
        hasHero = lead.image != null
        hero.visibility = if (hasHero) View.VISIBLE else View.GONE
        hero.translationY = 0f
        bar.alpha = 0f
        barShown = false
        statusIcons(onSurface = !hasHero)
        window.decorView.requestApplyInsets() // sheet padding depends on whether there is a hero

        track.removeAllViews()
        pills = s.mapIndexed { i, item ->
            newPill(track, item.source.substringBefore(' ')) { select(i) }.apply { contentDescription = item.source }
        }
        toggle.visibility = if (s.size > 1) View.VISIBLE else View.GONE
        toggle.scrollTo(0, 0)
        selected = -1
        select(s.indexOf(lead))

        val next = stories.getOrNull(stories.indexOf(s) + 1)
        upNext.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next != null) {
            findViewById<TextView>(R.id.nextTitle).text = next.lead.title
            val thumb = findViewById<ImageView>(R.id.nextImage)
            thumb.background = sourceGradient(next.lead.source)
            Img.load(next.lead.image, thumb)
        }
    }

    /** Swipe or "Up next": slide the page out, the neighbouring story in. Past either end, a small rubber-band. */
    private fun go(step: Int) {
        val target = stories.getOrNull(stories.indexOf(story) + step)
        val w = scroll.width * 0.3f
        if (target == null) {
            scroll.animate().translationX(-step * 24.dp.toFloat()).setDuration(90).withEndAction {
                scroll.animate().translationX(0f).setDuration(180)
            }
            return
        }
        switched = true
        scroll.haptic()
        scroll.animate().translationX(-step * w).alpha(0f).setDuration(150).setInterpolator(AccelerateInterpolator()).withEndAction {
            open(target)
            scroll.scrollTo(0, 0)
            scroll.translationX = step * w
            scroll.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(DecelerateInterpolator())
        }
    }

    private val swipes by lazy {
        val minFling = ViewConfiguration.get(this).scaledMinimumFlingVelocity * 6
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || abs(vx) < minFling) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) < 80.dp || abs(dx) < abs(dy) * 2) return false // mostly sideways, and far enough
                if (toggle.isShown && toggle.hit(e1)) return false // the coaching pills scroll sideways themselves
                go(if (dx < 0) +1 else -1)
                return true
            }
        })
    }

    /** Watch every touch for a sideways fling without taking it from scrolling, the WebView or the pills. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipes.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun View.hit(e: MotionEvent): Boolean {
        val at = IntArray(2).also(::getLocationOnScreen)
        return e.rawX >= at[0] && e.rawX <= at[0] + width && e.rawY >= at[1] && e.rawY <= at[1] + height
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }

    /** Switch coaching: pill color morphs and bounces, the article crossfades. */
    private fun select(i: Int) {
        if (i == selected) return
        val animate = selected >= 0
        selected = i
        pills.forEachIndexed { j, p -> p.pill(j == i, getColor(sourceColor(story[j].source)), animate) }
        val item = story[i]
        if (!animate) return show(item)
        pills[i].bounce()
        toggle.smoothScrollTo(pills[i].left - 40.dp, 0)
        listOf(title, meta).forEach { it.animate().alpha(0f).setDuration(120) }
        body.animate().alpha(0f).setDuration(120).withEndAction {
            show(item)
            listOf(title, meta, body).forEach { it.animate().alpha(1f).setDuration(240) }
        }
    }

    private fun show(item: Item) {
        title.text = item.title
        bar.text = item.title
        val color = getColor(sourceColor(item.source))
        meta.text = SpannableStringBuilder().append(item.source, ForegroundColorSpan(color), 0)
            .append(getString(R.string.meta, item.minutes))
        progress.setBackgroundColor(color)
        if (item.image != null) {
            heroUrl = item.image
            Img.load(item.image, hero)
        }
        // A fresh WebView per article: a reused one keeps the old (taller) height inside the ScrollView.
        web?.let { body.removeView(it); it.destroy() }
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            // Scraped third-party HTML: render only. No scripts, no local files, no mixed content.
            settings.apply {
                javaScriptEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                textZoom = (resources.configuration.fontScale * 100).roundToInt()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // A linked image opens in the viewer, any other link in the browser.
                    if (IMAGE.containsMatchIn(request.url.path.orEmpty())) viewImage(request.url.toString()) else browse(request.url)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val font = FONTS[request.url.lastPathSegment]?.takeIf { request.url.host == FONT_HOST } ?: return null
                    return WebResourceResponse(
                        "font/ttf", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), resources.openRawResource(font),
                    )
                }
            }
            val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE) hitTestResult.extra?.let(::viewImage)
                    return false
                }
            })
            setOnTouchListener { _, e -> taps.onTouchEvent(e); false } // observe only: the WebView still scrolls and selects
            loadDataWithBaseURL(item.link, page(item.html, item.source), "text/html", "utf-8", null)
        }
        body.addView(web, MATCH_PARENT, WRAP_CONTENT)
    }

    /** Coaching HTML restyled into one calm reading theme, accented in the coaching's colour. */
    private fun page(html: String, source: String): String {
        fun c(color: Int) = "#%06X".format(color and 0xFFFFFF)
        val ink = c(ink())
        val line = c(col(R.color.line, R.color.paper_line))
        val src = c(getColor(sourceColor(source)))
        val f = "https://$FONT_HOST/"
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
@font-face{font-family:Lit;src:url(${f}literata_regular);font-weight:100 500}
@font-face{font-family:Lit;src:url(${f}literata_semibold);font-weight:600 900}
@font-face{font-family:Jak;src:url(${f}jakarta_bold);font-weight:100 900}
body{margin:0;padding:0 22px 56px;font:17px/1.68 Lit,serif;color:$ink;overflow-wrap:break-word}
body>*:first-child{margin-top:4px}
body *{background:transparent!important;color:inherit!important;font-family:inherit!important;max-width:100%!important;box-sizing:border-box}
h1,h2,h3,h4,h5,h6{font-family:Jak,sans-serif!important;font-size:1.05em;line-height:1.35;margin:1.8em 0 .6em;padding-left:12px;border-left:4px solid $src}
h1,h2{font-size:1.2em}
b,strong{font-weight:600}
img{height:auto!important;border-radius:16px;margin:12px 0}
a{color:$src!important;text-decoration-thickness:1px;text-underline-offset:3px}
ul,ol{padding-left:1.25em}li{margin:.4em 0}li::marker{color:$src}
table{border-collapse:collapse;display:block;overflow-x:auto;margin:1.2em 0;font:15px/1.5 Jak,sans-serif!important}
td,th{border:1px solid $line;padding:10px;vertical-align:top}
th{background:${src}1F!important}
blockquote{margin:1.2em 0;padding:2px 0 2px 16px;border-left:3px solid $src;font-style:italic}
</style></head><body>$html</body></html>"""
    }

    private fun col(screen: Int, onPaper: Int) = getColor(if (paper) onPaper else screen)
    private fun ink() = col(R.color.ink, R.color.paper_ink)

    /** The page itself: theme surface, or paper stock with a faint grain; every reading colour follows. */
    private fun surface() {
        val bg = col(R.color.surface, R.color.paper_bg)
        window.setBackgroundDrawable(page(bg))
        scroll.getChildAt(0).background = page(bg)
        sheet.background = GradientDrawable().apply {
            val r = 28.dp.toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(bg)
        }
        sheet.foreground = if (paper) grain() else null
        sheet.clipToOutline = true // keeps the grain inside the rounded top
        title.setTextColor(ink())
        meta.setTextColor(col(R.color.ink2, R.color.paper_ink2))
        bar.setBackgroundColor(bg)
        bar.setTextColor(ink())
        track.background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat(); setColor(col(R.color.bg, R.color.paper_raised)) }
        upNext.background = GradientDrawable().apply {
            cornerRadius = 22.dp.toFloat()
            setColor(col(R.color.surface, R.color.paper_raised))
            setStroke(1.dp, col(R.color.line, R.color.paper_line))
        }
        findViewById<TextView>(R.id.nextTitle).setTextColor(ink())
        paperBtn.setImageResource(if (paper) R.drawable.ic_screen else R.drawable.ic_paper)
        paperBtn.contentDescription = getString(if (paper) R.string.to_screen else R.string.to_paper)
    }

    private fun page(bg: Int): Drawable = if (paper) LayerDrawable(arrayOf(ColorDrawable(bg), grain())) else ColorDrawable(bg)

    /** Paper grain, generated rather than shipped: sparse speckles, drawn at ~3 device pixels each. */
    private fun grain(): Drawable {
        val bitmap = paperGrain ?: run {
            val size = 128
            val random = java.util.Random(7)
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val px = IntArray(size * size) {
                val a = if (random.nextInt(3) == 0) random.nextInt(if (night) 10 else 16) else 0
                if (night) Color.argb(a, 255, 236, 200) else Color.argb(a, 70, 50, 20)
            }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).apply { density = DisplayMetrics.DENSITY_MEDIUM }
        }.also { paperGrain = it }
        return BitmapDrawable(resources, bitmap).apply { setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private var paperGrain: Bitmap? = null

    /** Paper <-> screen: the page fades across, and the article keeps its place. */
    private fun togglePaper() {
        val y = scroll.scrollY
        scroll.animate().alpha(0f).setDuration(140).withEndAction {
            paper = !paper
            prefs.edit().putBoolean("paper", paper).apply()
            surface()
            show(story[selected])
            web?.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    scroll.scrollTo(0, y)
                    scroll.animate().alpha(1f).setDuration(220)
                }
            })
        }
    }

    private fun viewImage(url: String) {
        if (url.startsWith("https://")) startActivity(Intent(this, ImageActivity::class.java).putExtra(ImageActivity.URL, url))
    }

    private fun statusIcons(onSurface: Boolean) {
        val dark = onSurface && resources.getBoolean(R.bool.light_bars)
        window.insetsController?.setSystemBarsAppearance(if (dark) APPEARANCE_LIGHT_STATUS_BARS else 0, APPEARANCE_LIGHT_STATUS_BARS)
    }

}
