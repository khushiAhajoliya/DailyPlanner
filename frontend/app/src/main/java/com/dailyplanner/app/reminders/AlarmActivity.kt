package com.dailyplanner.app.reminders

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dailyplanner.app.DailyPlannerApp
import com.dailyplanner.app.ui.theme.AppAssets
import com.dailyplanner.app.ui.theme.DailyPlannerTheme
import com.dailyplanner.app.ui.theme.PeachSoft
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Full-screen alarm shown when a reminder fires (also over the lock screen).
 * It plays the phone's alarm sound on the alarm stream itself, so it rings even when the
 * phone's notification settings are quiet (vivo/Oppo/Xiaomi default new apps to silent).
 */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppAssets.manager = assets
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(ReminderReceiver.EXTRA_ID).orEmpty()
        val title = intent.getStringExtra(ReminderReceiver.EXTRA_TITLE).orEmpty().ifBlank { "Reminder" }
        val note = intent.getStringExtra(ReminderReceiver.EXTRA_NOTE).orEmpty()
        startRinging()

        setContent {
            DailyPlannerTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp)) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(120.dp).background(PeachSoft, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Alarm, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        if (note.isNotBlank()) {
                            Text(note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { finishAlarm(id) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Text("Dismiss", style = MaterialTheme.typography.titleMedium) }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { snooze(id, title, note); finishAlarm(id) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                        ) { Text("Snooze 5 min", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        // Stop by itself after 2 minutes, like a clock alarm.
        window.decorView.postDelayed({ stopRinging() }, 120_000)
    }

    private fun stopRinging() {
        player?.runCatching { stop(); release() }
        player = null
        vibrator?.cancel()
    }

    private fun finishAlarm(id: String) {
        stopRinging()
        getSystemService(NotificationManager::class.java).cancel(id.hashCode())
        finish()
    }

    private fun snooze(id: String, title: String, note: String) {
        val c = (application as DailyPlannerApp).container
        c.scheduler.snooze(id, title, note, minutes = 5)
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, id: String, title: String?, note: String?) =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_ID, id)
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
                putExtra(ReminderReceiver.EXTRA_NOTE, note)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            }
    }
}
