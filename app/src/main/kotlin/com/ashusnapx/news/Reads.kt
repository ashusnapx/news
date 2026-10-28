package com.ashusnapx.news

import android.content.Context

/** Links opened today, so the feed can dim them and count progress. Pruned to the live feed: never grows. */
object Reads {
    private fun prefs(c: Context) = c.getSharedPreferences("reads", Context.MODE_PRIVATE)

    fun all(c: Context): Set<String> = prefs(c).getStringSet("links", null).orEmpty()

    /** True when the story had not been opened before: it counts toward today's goal and the streak. */
    fun mark(c: Context, story: Story): Boolean {
        val before = all(c)
        if (story.any { it.link in before }) return false
        prefs(c).edit().putStringSet("links", before + story.map { it.link }).apply()
        return true
    }

    fun prune(c: Context, feed: Feed) {
        val live = feed.stories.flatten().mapTo(HashSet()) { it.link }
        prefs(c).edit().putStringSet("links", all(c).filterTo(HashSet()) { it in live }).apply()
    }
}
