package com.ashusnapx.news

import android.net.http.HttpResponseCache
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.LocalDate

class Item(
    val source: String,
    val title: String,
    val link: String,
    val image: String?,
    val excerpt: String,
    val minutes: Int,
    val html: String,
)

/** One piece of news; several items when more than one coaching covered it. */
typealias Story = List<Item>

val Story.lead get() = firstOrNull { it.image != null } ?: first()

class Feed(val updated: String, val stories: List<Story>) {
    companion object {
        private const val URL = "https://ashusnapx.github.io/news/news.json"

        /** Last loaded feed. Articles read from here instead of pushing megabytes of HTML through an Intent. */
        @Volatile
        var current: Feed? = null

        /** Today's (IST) stories. Revalidates with the server every time; falls back to the HTTP cache offline. */
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
            if (o.getString("date") != LocalDate.now(IST).toString()) return Feed(o.getString("updated"), emptyList())
            val arr = o.getJSONArray("stories")
            return Feed(o.getString("updated"), List(arr.length()) { i ->
                val s = arr.getJSONArray(i)
                List(s.length()) { j ->
                    val x = s.getJSONObject(j)
                    Item(
                        x.getString("source"), x.getString("title"), x.getString("link"),
                        x.getString("image").ifEmpty { null }, x.getString("excerpt"), x.getInt("min"), x.getString("html"),
                    )
                }
            })
        }

        private fun get(cacheControl: String) = URL(URL).openConnection().run {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Cache-Control", cacheControl)
            getInputStream().bufferedReader().use { it.readText() }
        }
    }
}
