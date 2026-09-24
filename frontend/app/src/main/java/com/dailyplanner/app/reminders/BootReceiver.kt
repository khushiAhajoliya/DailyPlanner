package com.dailyplanner.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dailyplanner.app.DailyPlannerApp

/** Alarms are cleared on reboot; re-arm them from the offline copy of reminders. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val c = (context.applicationContext as DailyPlannerApp).container
        runCatching { c.scheduler.rescheduleAll(c.repository.cachedReminders()) }
    }
}
