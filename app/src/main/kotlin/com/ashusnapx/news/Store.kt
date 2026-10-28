package com.ashusnapx.news

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Everything the app remembers between days, on the phone only: a preferences file and three small JSON files.
 * No database, nothing sent anywhere, excluded from backup and device transfer.
 */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE)

    // --- Reading days: "day:2026-10-28" -> stories opened that day, kept for the calendar's 12 weeks. ---

    fun countRead(c: Context) {
        val p = prefs(c)
        val key = "day:${LocalDate.now()}"
        val cutoff = LocalDate.now().minusDays(120).toString()
        p.edit().putInt(key, p.getInt(key, 0) + 1).apply {
            p.all.keys.filter { it.startsWith("day:") && it.substring(4) < cutoff }.forEach(::remove)
        }.apply()
    }

    fun readOn(c: Context, day: LocalDate) = prefs(c).getInt("day:$day", 0)

    /** Consecutive days with at least one story read, ending today, or yesterday if today has not started. */
    fun streak(c: Context): Int {
        var day = LocalDate.now()
        if (readOn(c, day) == 0) day = day.minusDays(1)
        var n = 0
        while (readOn(c, day) > 0) {
            n++
            day = day.minusDays(1)
        }
        return n
    }

    var Context.goal: Int
        get() = prefs(this).getInt("goal", 10)
        set(v) = prefs(this).edit().putInt("goal", v).apply()

    // --- Saved stories: the full item, because the feed forgets it after 24 hours. ---

    private fun savedDir(c: Context) = File(c.filesDir, "saved").apply { mkdirs() }
    private fun savedFile(c: Context, link: String) = File(savedDir(c), "${link.hashCode().toUInt()}.json")

    fun isSaved(c: Context, link: String) = savedFile(c, link).exists()

    /** Returns true when the story is now saved, false when it was removed. */
    fun toggleSave(c: Context, item: Item): Boolean {
        val f = savedFile(c, item.link)
        if (f.exists()) {
            f.delete()
            return false
        }
        f.writeText(item.json().toString())
        return true
    }

    fun clearSaved(c: Context) = savedDir(c).listFiles().orEmpty().forEach { it.delete() }

    fun saved(c: Context): List<Item> = savedDir(c).listFiles().orEmpty().sortedByDescending { it.lastModified() }
        .mapNotNull { runCatching { Item.from(JSONObject(it.readText())) }.getOrNull() }

    // --- Kept articles: the full story behind a highlight, so it opens after the feed forgets it. ---

    private fun keptDir(c: Context) = File(c.filesDir, "kept").apply { mkdirs() }
    private fun keptFile(c: Context, link: String) = File(keptDir(c), "${link.hashCode().toUInt()}.json")

    /** A story the phone holds: saved, or kept for its highlights. */
    fun article(c: Context, link: String): Item? = listOf(savedFile(c, link), keptFile(c, link)).firstOrNull { it.exists() }
        ?.let { runCatching { Item.from(JSONObject(it.readText())) }.getOrNull() }

    /** Kept copies whose last highlight is gone. Run when leaving the You tab, after its undo window. */
    fun pruneKept(c: Context) {
        val live = highlights(c).mapTo(HashSet()) { keptFile(c, it.link).name }
        keptDir(c).listFiles().orEmpty().filter { it.name !in live }.forEach { it.delete() }
    }

    // --- Highlights: the passage, and which story it came from. ---

    class Highlight(val text: String, val title: String, val link: String)

    private fun highlightsFile(c: Context) = File(c.filesDir, "highlights.json")

    fun highlights(c: Context): List<Highlight> = read(highlightsFile(c)).let { a ->
        List(a.length()) { i -> a.getJSONObject(i).let { Highlight(it.getString("text"), it.getString("title"), it.getString("link")) } }
    }

    fun highlight(c: Context, text: String, item: Item) {
        keptFile(c, item.link).writeText(item.json().toString())
        val a = read(highlightsFile(c))
        a.put(JSONObject().put("text", text.trim().take(1000)).put("title", item.title).put("link", item.link))
        highlightsFile(c).writeText(a.toString())
    }

    fun clearHighlights(c: Context) {
        highlightsFile(c).delete()
        keptDir(c).listFiles().orEmpty().forEach { it.delete() }
    }

    fun restoreHighlight(c: Context, h: Highlight) {
        val a = read(highlightsFile(c))
        a.put(JSONObject().put("text", h.text).put("title", h.title).put("link", h.link))
        highlightsFile(c).writeText(a.toString())
    }

    fun removeHighlight(c: Context, h: Highlight) {
        val kept = JSONArray(highlights(c).filterNot { it.text == h.text && it.link == h.link }
            .map { JSONObject().put("text", it.text).put("title", it.title).put("link", it.link) })
        highlightsFile(c).writeText(kept.toString())
    }

    // --- Revision deck: questions answered wrong come back after 1, 3, then 7 days; right on the third, retired. ---

    private val STEPS = longArrayOf(1, 3, 7)
    private fun deckFile(c: Context) = File(c.filesDir, "revise.json")
    private fun key(q: Question) = q.q.hashCode()

    private fun deck(c: Context) = read(deckFile(c))

    fun missed(c: Context, q: Question) = reschedule(c, q, step = 0)

    fun reviewed(c: Context, q: Question, right: Boolean) {
        val step = (0 until deck(c).length()).map { deck(c).getJSONObject(it) }
            .firstOrNull { it.getInt("key") == key(q) }?.getInt("step") ?: 0
        if (!right) return reschedule(c, q, 0)
        if (step + 1 >= STEPS.size) return remove(c, q)
        reschedule(c, q, step + 1)
    }

    private fun reschedule(c: Context, q: Question, step: Int) {
        val rest = deck(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getInt("key") != key(q) } }
        val entry = JSONObject().put("key", key(q)).put("step", step)
            .put("due", LocalDate.now().plusDays(STEPS[step]).toString()).put("q", q.json())
        deckFile(c).writeText(JSONArray(rest + entry).toString())
    }

    private fun remove(c: Context, q: Question) {
        val rest = deck(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getInt("key") != key(q) } }
        deckFile(c).writeText(JSONArray(rest).toString())
    }

    fun deckSize(c: Context) = deck(c).length()

    fun due(c: Context): List<Question> {
        val today = LocalDate.now().toString()
        val a = deck(c)
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("due") <= today }
            .mapNotNull { runCatching { Question.from(it.getJSONObject("q")) }.getOrNull() }
    }

    private fun read(f: File) = runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }
}
