package com.ashusnapx.news

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Three nudges a day, each skipped when it has nothing worth saying: the morning brief, the afternoon quiz, the
 * evening "stories left". Never within two hours of the app being opened. Local alarms, no server: one-shot,
 * within a 30-minute window the system may batch, re-armed after each fire. (Inexact *repeating* alarms got an
 * 18-hour window on a daily interval, so "14:00" could land the next morning.)
 */
class Reminder : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.ashusnapx.news.REMIND"
        private const val CHANNEL = "daily"
        private val SLOTS = listOf("morning" to LocalTime.of(8, 0), "quiz" to LocalTime.of(14, 0), "evening" to LocalTime.of(20, 30))

        /** Idempotent: same request codes replace the previous alarms. Called on app open, after reboot, and after each fire. */
        fun schedule(c: Context) {
            val alarms = c.getSystemService(AlarmManager::class.java)
            SLOTS.forEachIndexed { code, (slot, at) ->
                var next = LocalDateTime.of(LocalDate.now(), at)
                if (next.isBefore(LocalDateTime.now())) next = next.plusDays(1)
                val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                alarms.setWindow(AlarmManager.RTC, millis, 30 * 60_000L, broadcast(c, code, slot))
            }
        }

        private fun broadcast(c: Context, code: Int, slot: String) = PendingIntent.getBroadcast(
            c, code, Intent(c, Reminder::class.java).setAction(ACTION).putExtra("slot", slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun opened(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putLong("lastOpen", System.currentTimeMillis()).apply()

        fun quizDone(c: Context) = c.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            .putString("quizDone", LocalDate.now().toString()).apply()
    }

    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) return schedule(c) // alarms do not survive a reboot
        if (intent.action != ACTION) return
        val slot = intent.getStringExtra("slot") ?: return
        val force = intent.getBooleanExtra("force", false) // skips the recently-opened rule; only the system and this app can send it
        val done = goAsync() // the feed fetch happens off the main thread, inside the broadcast's time budget
        Thread {
            try {
                remind(c, slot, force)
            } finally {
                schedule(c) // next one: tomorrow, same time
                done.finish()
            }
        }.start()
    }

    private fun remind(c: Context, slot: String, force: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val prefs = c.getSharedPreferences("profile", Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("lastOpen", 0) < 2 * 60 * 60 * 1000) return
        val feed = runCatching { Feed.fetch(c.cacheDir) }.getOrNull() ?: return
        val name = prefs.getString("name", null)?.ifBlank { null }
        val reads = Reads.all(c)
        val unread = feed.stories.filter { s -> s.none { it.link in reads } }

        // Morning: the top stories; evening: the top unread ones. Their photos make the carousel.
        val showcase = (if (slot == "evening") unread else feed.stories).filter { it.lead.image != null }.take(3)
        val (title, text) = when (slot) {
            "morning" -> {
                if (feed.stories.isEmpty()) return
                val hello = if (name != null) c.getString(R.string.notify_morning_named, name) else c.getString(R.string.notify_morning)
                hello to c.getString(R.string.notify_morning_text, feed.stories.size, feed.stories[0].lead.title)
            }
            "quiz" -> {
                if (feed.quiz.isEmpty() || prefs.getString("quizDone", null) == LocalDate.now().toString()) return
                c.getString(R.string.notify_quiz) to c.getString(R.string.notify_quiz_text, feed.quiz.size)
            }
            else -> {
                if (unread.isEmpty()) return
                c.getResources().getQuantityString(R.plurals.notify_left, unread.size, unread.size) to
                    c.getString(R.string.notify_left_text, unread[0].lead.title)
            }
        }

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = c.getString(R.string.notify_channel_desc) }
        )
        // Same filter as the launcher, so the strict intent matching the manifest opts into still lets it through.
        val main = Intent(c, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val tap = if (slot == "quiz") {
            PendingIntent.getActivities(c, 10, arrayOf(main, Intent(c, QuizActivity::class.java)), PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(c, 11, main, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_mains)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
        val slides = if (slot == "quiz") emptyList() else showcase.mapNotNull { s -> thumb(s.lead.image!!)?.let { s.lead.title to it } }
        when {
            slides.size > 1 -> {
                // Collapsed: just the words. Expanded: one slide per story, crossfaded by the system. No large icon,
                // which would repeat the first photo as a thumbnail beside the carousel.
                val small = RemoteViews(c.packageName, R.layout.notif_small).apply {
                    setTextViewText(R.id.title, title)
                    setTextViewText(R.id.text, text)
                }
                val carousel = RemoteViews(c.packageName, R.layout.notif_carousel)
                slides.forEachIndexed { i, (headline, bitmap) ->
                    carousel.addView(R.id.flipper, RemoteViews(c.packageName, R.layout.notif_slide).apply {
                        setImageViewBitmap(R.id.image, bitmap)
                        setTextViewText(R.id.title, headline)
                        setTextViewText(R.id.count, "${i + 1} / ${slides.size}")
                    })
                }
                builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(small).setCustomBigContentView(carousel)
            }
            // One photo: a thumbnail while collapsed, the full picture when expanded, never both at once.
            slides.size == 1 -> builder.setStyle(
                Notification.BigPictureStyle().bigPicture(slides[0].second).showBigPictureWhenCollapsed(true).setSummaryText(text)
            )
            else -> builder.setStyle(Notification.BigTextStyle().bigText(text))
        }
        nm.notify(SLOTS.indexOfFirst { it.first == slot }, builder.build())
    }

    /**
     * A 480x240 centre crop in RGB_565: three of them stay far under the notification's parcel size limit,
     * and the system scales them to the shade anyway.
     */
    private fun thumb(url: String): Bitmap? {
        val src = runCatching { Img.decode(url, 960, 960) }.getOrNull() ?: return null
        val out = Bitmap.createBitmap(480, 240, Bitmap.Config.RGB_565)
        val scale = maxOf(480f / src.width, 240f / src.height)
        val w = 480 / scale
        val h = 240 / scale
        val left = (src.width - w) / 2
        val top = (src.height - h) / 2
        Canvas(out).drawBitmap(src, Rect(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt()), Rect(0, 0, 480, 240), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }
}
