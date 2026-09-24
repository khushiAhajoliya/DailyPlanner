package com.dailyplanner.app.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.PlanInput
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.data.model.TextOverride
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.Load
import com.dailyplanner.app.ui.components.friendly
import kotlinx.coroutines.launch

class EditorViewModel(
    private val repo: PlannerRepository,
    private val templateId: String?,
    val planId: String?,
) : ViewModel() {
    var template by mutableStateOf<Load<Template>>(Load.Loading)
        private set
    var state by mutableStateOf(PlanState())
        private set
    var selectedId by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var dirty by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    init { load() }

    fun load() = viewModelScope.launch {
        template = Load.Loading
        template = runCatching {
            if (planId != null) {
                val plan = repo.plan(planId)
                state = PlanState(plan.values, plan.checks)
                title = plan.title
                repo.template(plan.templateId)
            } else {
                repo.template(templateId!!).also { title = it.name }
            }
        }.fold({ Load.Ready(it) }, { Load.Failed(it.friendly()) })
    }

    val selected: Element.Text?
        get() = (template as? Load.Ready)?.value?.elements?.firstOrNull { it.id == selectedId } as? Element.Text

    fun onTap(e: Element?) {
        when (e) {
            is Element.Checkbox -> { state = state.toggle(e); dirty = true; selectedId = null }
            is Element.Text -> selectedId = e.id
            else -> selectedId = null
        }
    }

    fun setText(e: Element.Text, text: String) = edit(e) { it.copy(text = text.takeIf { t -> t != e.text }) }
    fun setColor(e: Element.Text, hex: String?) = edit(e) { it.copy(color = hex?.takeIf { h -> !h.equals(e.style.color, true) }) }
    fun setFont(e: Element.Text, family: String?) = edit(e) { it.copy(fontFamily = family?.takeIf { f -> f != e.style.fontFamily }) }
    fun reset(e: Element.Text) = edit(e) { TextOverride() }

    private fun edit(e: Element.Text, change: (TextOverride) -> TextOverride) {
        state = state.withOverride(e.id, change)
        dirty = true
    }

    /** Creates or updates the plan; returns its id. */
    fun save(onSaved: (String) -> Unit) = viewModelScope.launch {
        val t = (template as? Load.Ready)?.value ?: return@launch
        saving = true
        runCatching {
            val input = PlanInput(t.id, title.trim().ifBlank { t.name }, state.values, state.checks)
            if (planId != null) repo.updatePlan(planId, input) else repo.createPlan(input)
        }.onSuccess { dirty = false; onSaved(it.id) }
            .onFailure { error = it.friendly() }
        saving = false
    }
}
