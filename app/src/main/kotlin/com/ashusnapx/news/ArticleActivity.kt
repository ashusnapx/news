package com.ashusnapx.news

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
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
import kotlin.math.roundToInt

class ArticleActivity : Activity() {
    companion object {
        const val LINK = "link"

        // App fonts served to the WebView under a host that never reaches the network.
        private const val FONT_HOST = "appassets.androidplatform.net"
        private val FONTS = mapOf(
            "literata_regular" to R.font.literata_regular,
            "literata_semibold" to R.font.literata_semibold,
            "jakarta_bold" to R.font.jakarta_bold,
        )
    }

    private lateinit var story: Story
    private lateinit var pills: List<TextView>
    private lateinit var hero: ImageView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var bar: TextView
    private lateinit var progress: View
    private lateinit var body: FrameLayout
    private lateinit var toggle: HorizontalScrollView
    private var web: WebView? = null
    private var selected = -1
    private var barShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra(LINK)
        // shortcut: the feed lives in memory only; after process death we fall back to the list, which refetches
        story = Feed.current?.stories?.find { s -> s.any { it.link == link } } ?: return finish()
        setContentView(R.layout.activity_article)
        hero = findViewById(R.id.hero)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        bar = findViewById(R.id.bar)
        progress = findViewById(R.id.progress)
        body = findViewById(R.id.body)
        toggle = findViewById(R.id.toggleScroll)
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val sheet = findViewById<View>(R.id.sheet)
        val back = findViewById<View>(R.id.back)

        val lead = story.lead
        val hasHero = lead.image != null
        hero.visibility = if (hasHero) View.VISIBLE else View.GONE
        hero.layoutParams.height = minOf(300.dp, windowManager.currentWindowMetrics.bounds.height() * 2 / 5) // landscape, folds
        statusIcons(onSurface = !hasHero)

        val track = findViewById<LinearLayout>(R.id.toggle)
        pills = story.mapIndexed { i, item ->
            (layoutInflater.inflate(R.layout.pill, track, false) as TextView).apply {
                text = item.source.substringBefore(' ')
                contentDescription = item.source
                background = GradientDrawable().apply { cornerRadius = 100.dp.toFloat() }
                setOnClickListener { haptic(); select(i) }
                track.addView(this)
            }
        }
        toggle.visibility = if (story.size > 1) View.VISIBLE else View.GONE
        back.setOnClickListener { it.haptic(); finishAfterTransition() }
        meta.setOnClickListener { it.haptic(); browse(Uri.parse(story[selected].link)) }

        // Reading column stays <= 720dp on unfolded foldables, tablets and landscape.
        edgeToEdge { i ->
            val side = gutter(windowWidth - i.left - i.right, 720.dp)
            sheet.setPadding(i.left + side + 20.dp, 24.dp + if (hasHero) 0 else i.top + 56.dp, i.right + side + 20.dp, 0)
            body.setPadding(i.left + side, 0, i.right + side, i.bottom)
            bar.minHeight = i.top + 56.dp
            bar.setPadding(i.left + 72.dp, i.top, i.right + 20.dp, 0)
            (back.layoutParams as MarginLayoutParams).apply { topMargin = i.top + 4.dp; marginStart = i.left + 8.dp }
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

        select(story.indexOf(lead))
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
        pills.forEachIndexed { j, p -> paint(p, j == i, story[j].source, animate) }
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

    private fun paint(pill: TextView, on: Boolean, source: String, animate: Boolean) {
        val bg = pill.background as GradientDrawable
        val color = getColor(sourceColor(source))
        val to = if (on) color else color and 0xFFFFFF // same hue at zero alpha: no grey midpoint
        pill.setTextColor(getColor(if (on) R.color.on_src else R.color.ink2))
        if (!animate) return bg.setColor(to)
        ValueAnimator.ofArgb(bg.color?.defaultColor ?: to, to).apply {
            duration = 240
            addUpdateListener { bg.setColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun show(item: Item) {
        title.text = item.title
        bar.text = item.title
        val color = getColor(sourceColor(item.source))
        meta.text = SpannableStringBuilder().append(item.source, ForegroundColorSpan(color), 0)
            .append(getString(R.string.meta, item.minutes))
        progress.setBackgroundColor(color)
        if (item.image != null) Img.load(item.image, hero)
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
                    browse(request.url)
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val font = FONTS[request.url.lastPathSegment]?.takeIf { request.url.host == FONT_HOST } ?: return null
                    return WebResourceResponse(
                        "font/ttf", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*"), resources.openRawResource(font),
                    )
                }
            }
            loadDataWithBaseURL(item.link, page(item.html, item.source), "text/html", "utf-8", null)
        }
        body.addView(web, MATCH_PARENT, WRAP_CONTENT)
    }

    /** Coaching HTML restyled into one calm reading theme, accented in the coaching's colour. */
    private fun page(html: String, source: String): String {
        fun c(color: Int) = "#%06X".format(color and 0xFFFFFF)
        val ink = c(getColor(R.color.ink))
        val line = c(getColor(R.color.line))
        val src = c(getColor(sourceColor(source)))
        val f = "https://$FONT_HOST/"
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
@font-face{font-family:Lit;src:url(${f}literata_regular);font-weight:100 500}
@font-face{font-family:Lit;src:url(${f}literata_semibold);font-weight:600 900}
@font-face{font-family:Jak;src:url(${f}jakarta_bold);font-weight:100 900}
body{margin:0;padding:8px 22px 56px;font:18px/1.75 Lit,serif;color:$ink;overflow-wrap:break-word}
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

    private fun statusIcons(onSurface: Boolean) {
        val dark = onSurface && resources.getBoolean(R.bool.light_bars)
        window.insetsController?.setSystemBarsAppearance(if (dark) APPEARANCE_LIGHT_STATUS_BARS else 0, APPEARANCE_LIGHT_STATUS_BARS)
    }

    /** Web links only, and only to apps that declare themselves browsable: no intent://, javascript:, file:. */
    private fun browse(uri: Uri) {
        if (uri.scheme != "https" && uri.scheme != "http") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }
}
