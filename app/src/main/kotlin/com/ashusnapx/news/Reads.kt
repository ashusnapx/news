package com.ashusnapx.news

import android.content.Context

/** Links opened today, so the feed can dim them and count progress. Pruned to the live feed: never grows. */
object Reads {
    private fun prefs(c: Context) = c.getSharedPreferences("reads", Context.MODE_PRIVATE)

    fun all(c: Context): Set<String> = prefs(c).getStringSet("links", null).orEmpty()

    fun mark(c: Context, story: Story) =
        prefs(c).edit().putStringSet("links", all(c) + story.map { it.link }).apply()

    fun prune(c: Context, feed: Feed) {
        val live = feed.stories.flatten().mapTo(HashSet()) { it.link }
        prefs(c).edit().putStringSet("links", all(c).filterTo(HashSet()) { it in live }).apply()
    }
}
