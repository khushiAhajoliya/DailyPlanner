package com.dailyplanner.app.ui.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.ErrorView
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.LoadingView
import com.dailyplanner.app.ui.components.PlannerThumbnail
import com.dailyplanner.app.ui.components.appContainer
import com.dailyplanner.app.ui.components.appViewModel
import com.dailyplanner.app.ui.components.friendly
import com.dailyplanner.app.ui.theme.Line
import kotlinx.coroutines.launch

class TemplatesViewModel(private val repo: PlannerRepository) : ViewModel() {
    var state by mutableStateOf<Load<List<Template>>>(Load.Loading)
        private set

    fun load() = viewModelScope.launch {
        if (state !is Load.Ready) state = Load.Loading
        state = runCatching { repo.templates() }.fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
    }
}

@Composable
fun TemplatesScreen(onOpen: (Template) -> Unit) {
    val vm = appViewModel { TemplatesViewModel(it.repository) }
    val c = appContainer()
    LaunchedEffect(Unit) { vm.load() }

    when (val s = vm.state) {
        Load.Loading -> LoadingView()
        is Load.Failed -> ErrorView(s.message) { vm.load() }
        is Load.Ready -> LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 4.dp)) {
                    Text("Templates", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Pick a template, fill it in and save it to My Plans.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(s.value, key = { it.id }) { t ->
                Column(Modifier.clickable { onOpen(t) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Line),
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PlannerThumbnail(t, PlanState(), c.renderer, c.images, Modifier.fillMaxWidth())
                    }
                    Text(t.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${t.category} · ${t.page.paper.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
