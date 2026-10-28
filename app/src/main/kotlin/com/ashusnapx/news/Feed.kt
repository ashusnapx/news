package com.ashusnapx.news

import android.net.http.HttpResponseCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.OffsetDateTime

class Item(
    val source: String,
    val kind: String, // "prelims" or "mains"
    val title: String,
    val link: String,
    val image: String?,
    val excerpt: String,
    val minutes: Int,
    val html: String,
    val gs: List<String> = emptyList(), // "GS1".."GS4"
) {
    /** Same shape as the feed's JSON, so a saved story reads back with [from]. */
    fun json(): JSONObject = JSONObject().put("source", source).put("kind", kind).put("title", title).put("link", link)
        .put("image", image ?: "").put("excerpt", excerpt).put("min", minutes).put("html", html).put("gs", JSONArray(gs))

    companion object {
        fun from(x: JSONObject) = Item(
            x.getString("source"), x.optString("kind", "prelims"), x.getString("title"), x.getString("link"),
            x.getString("image").ifEmpty { null }, x.getString("excerpt"), x.getInt("min"), x.getString("html"),
            x.optJSONArray("gs").let { a -> List(a?.length() ?: 0) { a!!.getString(it) } },
        )
    }
}

/** One piece of news; several items when more than one coaching covered it. */
typealias Story = List<Item>

val Story.lead get() = firstOrNull { it.image != null } ?: first()

/** A quiz MCQ. [q] is a small HTML fragment; [answer] indexes [options]; the explanation comes as [Block]s. */
class Question(val source: String, val q: String, val options: List<String>, val answer: Int, val why: List<Block>, val more: List<Block>) {
    fun json(): JSONObject = JSONObject().put("source", source).put("q", q).put("options", JSONArray(options)).put("answer", answer)
        .put("why", blocks(why)).put("more", blocks(more))

    private fun blocks(bs: List<Block>) = JSONArray(bs.map { JSONArray(listOf(it.kind, it.depth, it.text)) })

    companion object {
        fun from(x: JSONObject): Question {
            val opts = x.getJSONArray("options")
            return Question(x.getString("source"), x.getString("q"), List(opts.length(), opts::getString), x.getInt("answer"),
                blocks(x.optJSONArray("why")), blocks(x.optJSONArray("more")))
        }

        private fun blocks(a: JSONArray?) = List(a?.length() ?: 0) { i ->
            a!!.getJSONArray(i).let { Block(it.getString(0), it.getInt(1), it.getString(2)) }
        }
    }
}

/** One explanation line: [kind] "p" paragraph, "li" bullet at [depth], "h" sub-heading. [text] keeps only <b>. */
class Block(val kind: String, val depth: Int, val text: String)

class Feed(val updated: String, val stories: List<Story>, val quiz: List<Question>) {
    companion object {
        private const val URL = "https://ashusnapx.github.io/news/news.json"

        /** Last loaded feed. Articles read from here instead of pushing megabytes of HTML through an Intent. */
        @Volatile
        var current: Feed? = null

        /** The last 24 hours of stories. Revalidates with the server every time; falls back to the HTTP cache offline. */
        fun fetch(cacheDir: File): Feed {
            synchronized(this) {
                if (HttpResponseCache.getInstalled() == null) HttpResponseCache.install(File(cacheDir, "http"), 64L shl 20)
            }
            val o = JSONObject(
                try {
                    get("no-cache")
                } catch (e: IOException) {
                    get("only-if-cached, max-stale=86400")
                }
            )
            // A stalled scraper must show nothing rather than yesterday's news.
            if (OffsetDateTime.parse(o.getString("updated")).isBefore(OffsetDateTime.now().minusHours(6))) {
                return Feed(o.getString("updated"), emptyList(), emptyList())
            }
            val arr = o.getJSONArray("stories")
            val qs = o.optJSONArray("quiz")
            val quiz = List(qs?.length() ?: 0) { i -> Question.from(qs!!.getJSONObject(i)) }
            return Feed(o.getString("updated"), List(arr.length()) { i ->
                val s = arr.getJSONArray(i)
                List(s.length()) { j -> Item.from(s.getJSONObject(j)) }
            }, quiz)
        }

        private fun get(cacheControl: String) = URL(URL).openConnection().run {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Cache-Control", cacheControl)
            getInputStream().bufferedReader().use { it.readText() }
        }
    }
}
