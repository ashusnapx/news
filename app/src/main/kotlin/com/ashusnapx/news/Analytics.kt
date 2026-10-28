package com.ashusnapx.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Anonymous usage stats, sent only after the reader says yes. GA4 Measurement Protocol over plain HTTPS, so no
 * Google SDK. Events carry what was used, never who: no name, no highlighted text, no device identifiers; the id
 * is a random UUID made on this install. Off entirely until GA_ID and GA_SECRET are set at build time.
 */
object Analytics {
    private val io = Executors.newSingleThreadExecutor()
    private val queue = mutableListOf<JSONObject>()
    private val session = System.currentTimeMillis() / 1000

    private fun prefs(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val configured get() = BuildConfig.GA_ID.isNotEmpty() && BuildConfig.GA_SECRET.isNotEmpty()

    fun asked(c: Context) = prefs(c).contains("stats")
    fun enabled(c: Context) = configured && prefs(c).getBoolean("stats", false)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("stats", on).apply()
        if (!on) synchronized(queue) { queue.clear() }
    }

    private fun id(c: Context): String = prefs(c).getString("statsId", null)
        ?: UUID.randomUUID().toString().also { prefs(c).edit().putString("statsId", it).apply() }

    /** Queue an event; values are plain strings and numbers only. */
    fun log(c: Context, name: String, vararg params: Pair<String, Any>) {
        if (!enabled(c)) return
        val p = JSONObject().put("session_id", session).put("engagement_time_msec", 100)
        params.forEach { (k, v) -> p.put(k, if (v is Number) v else v.toString().take(100)) }
        synchronized(queue) {
            queue += JSONObject().put("name", name).put("params", p)
            if (queue.size >= 20) flush(c)
        }
    }

    /** Send what is queued, 25 events a request (the protocol's cap). Called when screens stop. */
    fun flush(c: Context) {
        if (!enabled(c)) return
        val batch = synchronized(queue) { queue.toList().also { queue.clear() } }
        if (batch.isEmpty()) return
        val client = id(c)
        io.execute {
            batch.chunked(25).forEach { events ->
                runCatching {
                    val url = URL("https://www.google-analytics.com/mp/collect?measurement_id=${BuildConfig.GA_ID}&api_secret=${BuildConfig.GA_SECRET}")
                    (url.openConnection() as HttpURLConnection).run {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(JSONObject().put("client_id", client).put("events", JSONArray(events)).toString().toByteArray()) }
                        responseCode // fire and forget: lost stats never cost the reader anything
                        disconnect()
                    }
                }
            }
        }
    }
}
