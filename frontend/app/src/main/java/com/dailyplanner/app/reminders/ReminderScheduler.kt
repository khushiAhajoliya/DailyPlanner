package com.dailyplanner.app.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dailyplanner.app.data.model.Reminder
import java.time.OffsetDateTime

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("reminders") // v1 channel had a normal notification sound
        val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        val channel = NotificationChannel(CHANNEL_ID, "Task alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarms for tasks and reminders in your plans"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            setSound(
                alarmSound,
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun pending(id: String, title: String, note: String, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_ID, id)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_NOTE, note)
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(r: Reminder) {
        cancel(r)
        val at = runCatching { OffsetDateTime.parse(r.remindAt).toInstant().toEpochMilli() }.getOrNull() ?: return
        if (!r.enabled) return
        if (at <= System.currentTimeMillis()) {
            android.util.Log.w("DailyPlannerAlarm", "not scheduled, time already passed: ${r.remindAt}")
            return
        }
        scheduleAt(r.id, r.title, r.note, at)
    }

    /** Alarm-clock alarms: exact, exempt from battery savers, shown with the alarm icon. */
    private fun scheduleAt(id: String, title: String, note: String, at: Long) {
        val pi = pending(id, title, note, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, com.dailyplanner.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        if (canRingOnTime()) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi) // at most ~1 min late
        android.util.Log.i("DailyPlannerAlarm", "armed $id for ${java.time.Instant.ofEpochMilli(at)} exact=${canRingOnTime()}")
    }

    fun snooze(id: String, title: String, note: String, minutes: Int) =
        scheduleAt(id, title, note, System.currentTimeMillis() + minutes * 60_000L)

    fun cancel(r: Reminder) {
        pending(r.id, r.title, r.note, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
    }

    /** Android 12+ needs the user's OK ("Alarms & reminders") for on-the-minute reminders. */
    fun canRingOnTime(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun rescheduleAll(list: List<Reminder>) = list.forEach(::schedule)

    companion object {
        const val CHANNEL_ID = "task_alarms"
    }
}
