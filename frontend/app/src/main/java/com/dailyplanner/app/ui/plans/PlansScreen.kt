package com.dailyplanner.app.ui.plans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.data.model.Plan
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.EmptyView
import com.dailyplanner.app.ui.components.ErrorView
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.LoadingView
import com.dailyplanner.app.ui.components.PlannerThumbnail
import com.dailyplanner.app.ui.components.appContainer
import com.dailyplanner.app.ui.components.appViewModel
import com.dailyplanner.app.ui.components.friendly
import com.dailyplanner.app.ui.theme.Line
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PlansViewModel(private val repo: PlannerRepository) : ViewModel() {
    data class Item(val plan: Plan, val template: Template)

    var state by mutableStateOf<Load<List<Item>>>(Load.Loading)
        private set

    fun load() = viewModelScope.launch {
        if (state !is Load.Ready) state = Load.Loading
        state = runCatching {
            val templates = repo.templates().associateBy { it.id }
            repo.plans().mapNotNull { p -> templates[p.templateId]?.let { Item(p, it) } }
        }.fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { repo.deletePlan(id) }
        load()
    }
}

internal fun formatUpdated(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault()))
}.getOrDefault("")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlansScreen(onOpen: (Plan) -> Unit, onBrowseTemplates: () -> Unit) {
    val vm = appViewModel { PlansViewModel(it.repository) }
    val c = appContainer()
    var toDelete by remember { mutableStateOf<Plan?>(null) }
    LaunchedEffect(Unit) { vm.load() }

    when (val s = vm.state) {
        Load.Loading -> LoadingView()
        is Load.Failed -> ErrorView(s.message) { vm.load() }
        is Load.Ready -> if (s.value.isEmpty()) {
            EmptyView("No plans yet", "Choose a template, fill it in and tap Create. Your plans show up here.") {
                TextButton(onClick = onBrowseTemplates) { Text("Browse templates") }
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("My Plans", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Tap to view, share or save as PDF. Long-press to delete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(s.value, key = { it.plan.id }) { item ->
                Column(
                    Modifier.combinedClickable(onClick = { onOpen(item.plan) }, onLongClick = { toDelete = item.plan }),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Line),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PlannerThumbnail(item.template, PlanState(item.plan.values, item.plan.checks), c.renderer, c.images, Modifier.fillMaxWidth())
                    }
                    Text(item.plan.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatUpdated(item.plan.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete plan?") },
            text = { Text("\"${p.title}\" will be removed from My Plans.") },
            confirmButton = { TextButton(onClick = { vm.delete(p.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
