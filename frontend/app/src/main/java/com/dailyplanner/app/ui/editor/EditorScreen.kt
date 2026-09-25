package com.dailyplanner.app.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.dailyplanner.app.ui.components.ErrorView
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.LoadingView
import com.dailyplanner.app.ui.components.PageGeometry
import com.dailyplanner.app.ui.components.PlannerPage
import com.dailyplanner.app.ui.components.appContainer
import com.dailyplanner.app.ui.components.appViewModel
import com.dailyplanner.app.ui.theme.PeachSoft

private val BottomStrip = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    templateId: String?,
    planId: String?,
    onBack: () -> Unit,
    onSaved: (planId: String, isNew: Boolean) -> Unit,
) {
    val c = appContainer()
    val vm = appViewModel(key = "editor-$templateId-$planId") { EditorViewModel(it.repository, templateId, planId) }
    var askTitle by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var alarmFor by remember { mutableStateOf<com.dailyplanner.app.data.model.Element.Text?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current

    LaunchedEffect(vm.error) { vm.error?.let { snackbar.showSnackbar(it); vm.error = null } }

    BackHandler {
        when {
            vm.selectedId != null -> vm.selectedId = null
            vm.dirty -> confirmDiscard = true
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (planId != null) vm.title else (vm.template as? Load.Ready)?.value?.name ?: "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (vm.dirty) confirmDiscard = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (vm.template is Load.Ready) {
                        // Undo / redo any change on the page (text, colour, font, ticks).
                        IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "Undo")
                        }
                        IconButton(onClick = vm::redo, enabled = vm.canRedo) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "Redo")
                        }
                        Button(
                            onClick = { vm.selectedId = null; if (planId == null) askTitle = true else vm.save { onSaved(it, false) } },
                            enabled = !vm.saving,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            if (vm.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (planId == null) "Create" else "Save")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val t = vm.template) {
            Load.Loading -> LoadingView()
            is Load.Failed -> ErrorView(t.message) { vm.load() }
            is Load.Ready -> Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                val sel = vm.selected
                val typing = sel != null && sel.role == "text"
                var areaW by remember { mutableIntStateOf(0) }
                var areaH by remember { mutableIntStateOf(0) }
                var toolbarH by remember { mutableIntStateOf(0) }
                val imeBottom = WindowInsets.ime.getBottom(density)
                val navBottom = WindowInsets.navigationBars.getBottom(density)
                val keyboardPx = (imeBottom - navBottom).coerceAtLeast(0)

                // A fixed strip at the bottom holds the hint / toolbar, so they never cover the page.
                val stripPx = with(density) { BottomStrip.toPx() }
                Box(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = BottomStrip).onSizeChanged { areaW = it.width; areaH = it.height }) {
                    // The page never zooms or scrolls. It only slides up when the keyboard
                    // would cover the line being typed, and only by the amount needed.
                    var lift = 0f
                    if (sel != null && areaH > 0) {
                        val g = PageGeometry.of(t.value, areaW.toFloat(), areaH.toFloat())
                        run {
                            val bottom = g.toScreen(0f, sel.box.y + sel.box.h).y
                            val top = g.toScreen(0f, sel.box.y).y
                            val visible = areaH + stripPx - keyboardPx - toolbarH - with(density) { 12.dp.toPx() }
                            lift = (bottom - visible).coerceIn(0f, (top - with(density) { 8.dp.toPx() }).coerceAtLeast(0f))
                        }
                    }
                    PlannerPage(
                        template = t.value,
                        state = vm.state,
                        renderer = c.renderer,
                        images = c.images,
                        selectedId = vm.selectedId,
                        hiddenTextId = if (typing) vm.selectedId else null,
                        liftPx = lift,
                        onTap = vm::onTap,
                        overlay = { g ->
                            if (typing) {
                                val next = vm.nextBelow(sel!!)
                                InlineTextField(
                                    sel, vm.state, g, c.fonts,
                                    onText = { vm.setText(sel, it) },
                                    onNext = next?.let { n -> { vm.selectedId = n.id } },
                                )
                            }
                        },
                    )
                }

                if (sel != null) {
                    EditToolbar(
                        element = sel,
                        template = t.value,
                        state = vm.state,
                        fonts = c.fonts,
                        onText = { vm.setText(sel, it) },
                        onColor = { vm.setColor(sel, it) },
                        onFont = { vm.setFont(sel, it) },
                        onReset = { vm.reset(sel) },
                        onDone = { vm.selectedId = null },
                        onAlarm = { alarmFor = sel },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .imePadding()
                            .onSizeChanged { toolbarH = it.height },
                    )
                } else {
                    Surface(
                        color = PeachSoft,
                        shape = RoundedCornerShape(50),
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    ) {
                        Text(
                            "Tap any text to write on the page",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    val tpl = (vm.template as? Load.Ready)?.value
    if (alarmFor != null && tpl != null) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        com.dailyplanner.app.ui.reminders.TaskAlarmDialog(
            task = alarmFor!!,
            template = tpl,
            state = vm.state,
            planId = vm.planId,
            planTitle = vm.title.ifBlank { tpl.name },
            onDismiss = { alarmFor = null },
            onResult = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
        )
    }

    if (askTitle) {
        AlertDialog(
            onDismissRequest = { askTitle = false },
            title = { Text("Save to My Plans") },
            text = {
                OutlinedTextField(
                    value = vm.title,
                    onValueChange = { vm.title = it.take(120) },
                    label = { Text("Plan name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            },
            confirmButton = { TextButton(onClick = { askTitle = false; vm.save { onSaved(it, true) } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { askTitle = false }) { Text("Cancel") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this plan haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}
