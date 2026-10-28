package com.ashusnapx.news

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Anonymous usage stats through Google Analytics for Firebase, only after the reader says yes. Collection is off
 * in the manifest until then, and never uses the advertising ID or Android ID. Events say what was used, never
 * who: no name, no highlighted text.
 */
object Analytics {
    private fun prefs(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private fun fa(c: Context) = FirebaseAnalytics.getInstance(c.applicationContext)

    fun asked(c: Context) = prefs(c).contains("stats")
    fun enabled(c: Context) = prefs(c).getBoolean("stats", false)

    /** Firebase remembers this across launches; the preference only records that the question was answered. */
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("stats", on).apply()
        fa(c).setAnalyticsCollectionEnabled(on)
        if (!on) fa(c).resetAnalyticsData() // and forget the install id it had
    }

    /**
     * Anonymous profile for slicing the stats: habits and settings in coarse buckets, nothing that names anyone.
     * Firebase caps user property names at 24 characters and values at 36.
     */
    fun profile(c: Context) {
        if (!enabled(c)) return
        fun bucket(n: Int, vararg edges: Int) = edges.lastOrNull { n >= it }?.let { if (it == edges.last()) "$it+" else "$it" } ?: "0"
        val night = (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val p = prefs(c)
        mapOf(
            "streak" to bucket(Store.streak(c), 0, 1, 3, 7, 14, 30),
            "goal" to p.getInt("goal", 10).toString(),
            "paper_mode" to p.getBoolean("paper", false).toString(),
            "theme" to if (night) "dark" else "light",
            "text_size" to "%.2f".format(c.resources.configuration.fontScale),
            "saved" to bucket(Store.saved(c).size, 0, 1, 5, 10, 25),
            "highlights" to bucket(Store.highlights(c).size, 0, 1, 5, 10, 25),
            "reminders" to c.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled().toString(),
        ).forEach { (k, v) -> fa(c).setUserProperty(k, v) }
    }

    /** Values are plain strings and numbers only; Firebase caps names at 40 characters and values at 100. */
    fun log(c: Context, name: String, vararg params: Pair<String, Any>) {
        if (!enabled(c)) return
        fa(c).logEvent(name, Bundle().apply {
            params.forEach { (k, v) ->
                when (v) {
                    is Int -> putLong(k, v.toLong())
                    is Long -> putLong(k, v)
                    is Number -> putDouble(k, v.toDouble())
                    else -> putString(k, v.toString().take(100))
                }
            }
        })
    }
}
