package com.dailyplanner.app.ui.reminders

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.appContainer
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

private val timeParsers = listOf("h:mm a", "h a", "h:mma", "ha", "H:mm").map {
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.US)
}

private fun parseTime(s: String): LocalTime? {
    val v = s.trim().uppercase(Locale.US)
    return timeParsers.firstNotNullOfOrNull { f -> runCatching { LocalTime.parse(v, f) }.getOrNull() }
}

/**
 * The time label of the task's own row ("8:00 AM | Morning Walk"): same line, directly to the
 * left of the task. Times in other columns (e.g. a schedule on the other side of the page)
 * are ignored.
 */
private fun rowTime(task: Element.Text, template: Template, state: PlanState): LocalTime? {
    val top = task.box.y; val bottom = task.box.y + task.box.h
    return template.elements.filterIsInstance<Element.Text>()
        .filter { t ->
            t.id != task.id && t.role == "time" &&
                t.y + t.h / 2 in (top - 8)..(bottom + 8) &&          // same line
                t.x < task.box.x && task.box.x - (t.x + t.w) < 160    // right next to the task, on its left
        }
        .maxByOrNull { it.x }
        ?.let { parseTime(state.textOf(it)) }
}

/**
 * "Alarm" for a task written on a plan: prefilled with the task text and the row's time,
 * saved on the phone and scheduled with AlarmManager.
 */
@Composable
fun TaskAlarmDialog(
    task: Element.Text,
    template: Template,
    state: PlanState,
    planId: String?,
    planTitle: String,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onResult("Allow notifications so the alarm can ring")
    }
    LaunchedEffect(Unit) {
        val need = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (need) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val initialAt = remember {
        val t = rowTime(task, template, state)
        if (t == null) null else {
            val today = LocalDateTime.of(LocalDate.now(), t)
            if (today.isAfter(LocalDateTime.now())) today else today.plusDays(1)
        }
    }
    val text = state.textOf(task).replace('\n', ' ').replace(' ', ' ').trim()

    ReminderDialog(
        initialTitle = text,
        initialAt = initialAt,
        heading = "Set alarm",
        extra = { copy(note = planTitle, planId = planId, elementId = task.id) },
        onDismiss = onDismiss,
    ) { input ->
        scope.launch {
            runCatching { c.repository.saveReminder(input) }
                .onSuccess {
                    c.scheduler.schedule(it)
                    val at = java.time.OffsetDateTime.parse(it.remindAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    onResult("Alarm set for ${at.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault()))}")
                }
                .onFailure { onResult("Couldn't set alarm: ${it.message}") }
            onDismiss()
        }
    }
}
