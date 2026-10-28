package com.ashusnapx.news

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Three nudges a day, each skipped when it has nothing worth saying: the morning brief, the afternoon quiz, the
 * evening "stories left". Never within two hours of the app being opened. Local alarms, no server; inexact, so
 * the system batches them with other wake-ups instead of draining the battery.
 */
class Reminder : BroadcastReceiver() {
    companion object {
        const val ACTION = "com.ashusnapx.news.REMIND"
        private const val CHANNEL = "daily"
        private val SLOTS = listOf("morning" to LocalTime.of(8, 0), "quiz" to LocalTime.of(14, 0), "evening" to LocalTime.of(20, 30))

        /** Idempotent: same request codes replace the previous alarms. Called on app open and after reboot. */
        fun schedule(c: Context) {
            val alarms = c.getSystemService(AlarmManager::class.java)
            SLOTS.forEachIndexed { code, (slot, at) ->
                var next = LocalDateTime.of(LocalDate.now(), at)
                if (next.isBefore(LocalDateTime.now())) next = next.plusDays(1)
                val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                alarms.setInexactRepeating(AlarmManager.RTC, millis, AlarmManager.INTERVAL_DAY, broadcast(c, code, slot))
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
        val done = goAsync() // the feed fetch happens off the main thread, inside the broadcast's time budget
        Thread {
            try {
                remind(c, slot)
            } finally {
                done.finish()
            }
        }.start()
    }

    private fun remind(c: Context, slot: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val prefs = c.getSharedPreferences("profile", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("lastOpen", 0) < 2 * 60 * 60 * 1000) return
        val feed = runCatching { Feed.fetch(c.cacheDir) }.getOrNull() ?: return
        val name = prefs.getString("name", null)?.ifBlank { null }
        val reads = Reads.all(c)
        val unread = feed.stories.filter { s -> s.none { it.link in reads } }

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
        nm.notify(
            SLOTS.indexOfFirst { it.first == slot },
            Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_mains)
                .setColor(c.getColor(R.color.accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }
}
