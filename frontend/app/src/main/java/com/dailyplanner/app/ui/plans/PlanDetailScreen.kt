package com.dailyplanner.app.ui.plans

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.ui.reminders.TaskAlarmDialog
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.data.model.Plan
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.ErrorView
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.LoadingView
import com.dailyplanner.app.ui.components.PlannerPage
import com.dailyplanner.app.ui.components.appContainer
import com.dailyplanner.app.ui.components.appViewModel
import com.dailyplanner.app.ui.components.friendly
import kotlinx.coroutines.launch

class PlanDetailViewModel(private val repo: PlannerRepository, private val planId: String) : ViewModel() {
    var state by mutableStateOf<Load<Pair<Plan, Template>>>(Load.Loading)
        private set

    fun load() = viewModelScope.launch {
        if (state !is Load.Ready) state = Load.Loading
        state = runCatching {
            val p = repo.plan(planId)
            p to repo.template(p.templateId)
        }.fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(planId: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val vm = appViewModel(key = "plan-$planId") { PlanDetailViewModel(it.repository, planId) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Element.Text?>(null) }
    var alarmFor by remember { mutableStateOf<Element.Text?>(null) }

    // Re-load on every visit so returning from Edit shows the saved changes.
    LaunchedEffect(Unit) { vm.load() }

    val ready = (vm.state as? Load.Ready)?.value

    fun savePdf() {
        val (plan, template) = ready ?: return
        scope.launch {
            busy = true
            runCatching { c.pdf.saveToDownloads(plan.title, template, PlanState(plan.values, plan.checks)) }
                .onSuccess { snackbar.showSnackbar("Saved A4 PDF to $it") }
                .onFailure { snackbar.showSnackbar("Couldn't save PDF: ${it.message}") }
            busy = false
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) savePdf() else scope.launch { snackbar.showSnackbar("Storage permission is needed to save the PDF") }
    }

    val readyPair = ready
    if (task != null && readyPair != null) {
        val st = PlanState(readyPair.first.values, readyPair.first.checks)
        ModalBottomSheet(onDismissRequest = { task = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(st.textOf(task!!).replace('\n', ' '), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                ListItem(
                    headlineContent = { Text("Set alarm") },
                    supportingContent = { Text("Get notified for this task") },
                    leadingContent = { Icon(Icons.Outlined.Alarm, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { alarmFor = task; task = null },
                )
                ListItem(
                    headlineContent = { Text("Edit text, color & font") },
                    leadingContent = { Icon(Icons.Outlined.Edit, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { task = null; onEdit(planId) },
                )
            }
        }
    }
    if (alarmFor != null && readyPair != null) {
        TaskAlarmDialog(
            task = alarmFor!!,
            template = readyPair.second,
            state = PlanState(readyPair.first.values, readyPair.first.checks),
            planId = readyPair.first.id,
            planTitle = readyPair.first.title,
            onDismiss = { alarmFor = null },
            onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.first?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                actions = {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(22.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { onEdit(planId) }, enabled = ready != null && !busy) {
                        Icon(Icons.Outlined.Edit, "Edit")
                    }
                    IconButton(
                        enabled = ready != null && !busy,
                        onClick = {
                            val (plan, template) = ready ?: return@IconButton
                            scope.launch {
                                busy = true
                                runCatching { c.pdf.exportForShare(plan.title, template, PlanState(plan.values, plan.checks)) }
                                    .onSuccess { ctx.startActivity(c.pdf.shareIntent(it, plan.title)) }
                                    .onFailure { snackbar.showSnackbar("Couldn't create PDF: ${it.message}") }
                                busy = false
                            }
                        },
                    ) { Icon(Icons.Outlined.Share, "Share") }
                    IconButton(
                        enabled = ready != null && !busy,
                        onClick = {
                            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                            if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else savePdf()
                        },
                    ) { Icon(Icons.Outlined.SaveAlt, "Save as PDF") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = vm.state) {
            Load.Loading -> LoadingView()
            is Load.Failed -> ErrorView(s.message) { vm.load() }
            is Load.Ready -> PlannerPage(
                template = s.value.second,
                state = PlanState(s.value.first.values, s.value.first.checks),
                renderer = c.renderer,
                images = c.images,
                selectedId = task?.id,
                modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp),
                // Tap a task you wrote to get its options (alarm, edit), like PlanWiz.
                onTap = { e ->
                    val st = PlanState(s.value.first.values, s.value.first.checks)
                    task = (e as? Element.Text)?.takeIf { st.textOf(it).isNotBlank() }
                },
            )
        }
    }
}
