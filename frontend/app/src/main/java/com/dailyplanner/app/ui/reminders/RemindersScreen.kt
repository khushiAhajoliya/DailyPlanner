package com.dailyplanner.app.ui.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dailyplanner.app.ui.components.appContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.data.model.Reminder
import com.dailyplanner.app.data.model.ReminderInput
import com.dailyplanner.app.reminders.ReminderScheduler
import com.dailyplanner.app.ui.components.EmptyView
import com.dailyplanner.app.ui.components.ErrorView
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.LoadingView
import com.dailyplanner.app.ui.components.appViewModel
import com.dailyplanner.app.ui.components.friendly
import com.dailyplanner.app.ui.theme.Line
import com.dailyplanner.app.ui.theme.PeachSoft
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class RemindersViewModel(private val repo: PlannerRepository, private val scheduler: ReminderScheduler) : ViewModel() {
    var state by mutableStateOf<Load<List<Reminder>>>(Load.Loading)
        private set
    var error by mutableStateOf<String?>(null)

    fun load() = viewModelScope.launch {
        if (state !is Load.Ready) state = Load.Loading
        state = runCatching { repo.reminders().also(scheduler::rescheduleAll) }
            .fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
    }

    fun create(input: ReminderInput) = viewModelScope.launch {
        runCatching { repo.saveReminder(input) }.onSuccess { scheduler.schedule(it); load() }.onFailure { error = it.friendly() }
    }

    fun toggle(r: Reminder, enabled: Boolean) = viewModelScope.launch {
        runCatching { repo.saveReminder(r.toInput().copy(enabled = enabled), r.id) }
            .onSuccess { scheduler.schedule(it); load() }.onFailure { error = it.friendly() }
    }

    fun delete(r: Reminder) = viewModelScope.launch {
        runCatching { repo.deleteReminder(r.id) }.onSuccess { scheduler.cancel(r); load() }.onFailure { error = it.friendly() }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

@Composable
fun RemindersScreen() {
    val vm = appViewModel { RemindersViewModel(it.repository, it.scheduler) }
    var adding by remember { mutableStateOf(false) }
    var askExact by remember { mutableStateOf(false) }
    val c = appContainer()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.load() }
    // Coming back from the settings screen: re-arm alarms so they use exact timing.
    LifecycleResumeEffect(Unit) { vm.load(); onPauseOrDispose { } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (c.scheduler.canRingOnTime()) adding = true else askExact = true
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = vm.state) {
            Load.Loading -> LoadingView()
            is Load.Failed -> ErrorView(s.message) { vm.load() }
            is Load.Ready -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(Modifier.padding(bottom = 4.dp)) {
                        Text("Reminders", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Get notified for the things on your plan.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (s.value.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp)) {
                        EmptyView("No reminders", "Tap “New reminder” to add one.")
                    }
                }
                items(s.value, key = { it.id }) { r -> ReminderCard(r, onToggle = { vm.toggle(r, it) }, onDelete = { vm.delete(r) }) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else adding = true
            },
            icon = { Icon(Icons.Outlined.Add, null) },
            text = { Text("New reminder") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (askExact) {
        AlertDialog(
            onDismissRequest = { askExact = false; adding = true },
            title = { Text("Ring reminders on time") },
            text = {
                Text(
                    "Android needs your permission for reminders to ring at the exact minute. " +
                        "Turn on \"Alarms & reminders\" for Daily Planner. On vivo phones, also set " +
                        "Battery to \"No restrictions\" in App info.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askExact = false
                    adding = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    askExact = false
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("Battery settings") }
            },
        )
    }
    if (adding) ReminderDialog(onDismiss = { adding = false }) { vm.create(it); adding = false }
    vm.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun ReminderCard(r: Reminder, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val at = runCatching { OffsetDateTime.parse(r.remindAt).atZoneSameInstant(ZoneId.systemDefault()) }.getOrNull()
    val past = at?.toInstant()?.isBefore(Instant.now()) == true
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = PeachSoft, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                if (at != null) Text(
                    "${at.format(dayFmt)} · ${at.format(timeFmt)}" + if (past) " · done" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = r.enabled && !past, onCheckedChange = onToggle, enabled = !past)
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Delete") }
        }
    }
}

/** New reminder / task alarm. [initialTitle] and [initialAt] prefill it (e.g. from a task on a plan). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(
    initialTitle: String = "",
    initialAt: LocalDateTime? = null,
    heading: String = "New reminder",
    extra: ReminderInput.() -> ReminderInput = { this },
    onDismiss: () -> Unit,
    onCreate: (ReminderInput) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var note by remember { mutableStateOf("") }
    val start = remember { initialAt ?: LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0) }
    var date by remember { mutableStateOf(start.toLocalDate()) }
    var time by remember { mutableStateOf(start.toLocalTime()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(120) }, label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, shape = RoundedCornerShape(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp)); Text(" " + date.format(dayFmt))
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)); Text(" " + time.format(timeFmt))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val at = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toOffsetDateTime()
                    onCreate(ReminderInput(title.trim(), note.trim(), at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).extra())
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickDate) {
        val s = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    s.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(s) }
    }
    if (pickTime) {
        val s = rememberTimePickerState(time.hour, time.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(s.hour, s.minute); pickTime = false }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(s) },
        )
    }
}
