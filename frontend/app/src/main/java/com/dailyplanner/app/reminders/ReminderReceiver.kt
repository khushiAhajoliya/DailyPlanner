package com.dailyplanner.app.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dailyplanner.app.DailyPlannerApp
import com.dailyplanner.app.R

/** Fires when a reminder is due: rings via a full-screen alarm and posts a notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)
        val note = intent.getStringExtra(EXTRA_NOTE)

        when (intent.action) {
            ACTION_DISMISS -> {
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                return
            }
            ACTION_SNOOZE -> {
                context.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
                (context.applicationContext as DailyPlannerApp).container.scheduler.snooze(id, title.orEmpty(), note.orEmpty(), 5)
                return
            }
        }
        android.util.Log.i("DailyPlannerAlarm", "fired $id at ${java.time.LocalTime.now()}")

        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val alarmScreen = PendingIntent.getActivity(context, id.hashCode(), AlarmActivity.intent(context, id, title, note), flags)
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(
            context, id.hashCode() + req,
            Intent(context, ReminderReceiver::class.java).setAction(a)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_NOTE, note),
            flags,
        )

        if (granted) {
            val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(note.orEmpty().ifBlank { "Daily Planner reminder" })
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setColor(0xFFDD8D00.toInt())
                .setShowWhen(true)
                // Opens the ringing alarm screen (over the lock screen too).
                .setFullScreenIntent(alarmScreen, true)
                .setContentIntent(alarmScreen)
                .addAction(0, "Snooze 5 min", action(ACTION_SNOOZE, 1))
                .addAction(0, "Dismiss", action(ACTION_DISMISS, 2))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id.hashCode(), n)
        }
        // If the full-screen intent is suppressed (phone in use, or the OS blocks it), still
        // try to open the alarm screen directly; alarm-clock broadcasts are allowed to.
        runCatching { context.startActivity(AlarmActivity.intent(context, id, title, note)) }
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_NOTE = "note"
        const val ACTION_SNOOZE = "com.dailyplanner.app.SNOOZE"
        const val ACTION_DISMISS = "com.dailyplanner.app.DISMISS"
    }
}
