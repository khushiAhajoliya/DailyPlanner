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

    // ------------------------------------------------------------ undo / redo
    private val undoStack = ArrayDeque<PlanState>()
    private val redoStack = ArrayDeque<PlanState>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var lastEditKey: String? = null
    private var lastEditAt = 0L

    /** Snapshot before a change. Typing in the same text within 1.5 s counts as one step. */
    private fun record(key: String) {
        val now = System.currentTimeMillis()
        if (key != lastEditKey || now - lastEditAt > 1500) {
            undoStack.addLast(state)
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        lastEditKey = key; lastEditAt = now
        canUndo = undoStack.isNotEmpty(); canRedo = redoStack.isNotEmpty()
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(state); state = prev; dirty = true; lastEditKey = null
        canUndo = undoStack.isNotEmpty(); canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(state); state = next; dirty = true; lastEditKey = null
        canUndo = true; canRedo = redoStack.isNotEmpty()
    }

    /** The editable text directly below [e] in the same column (Enter / Next on a one-line row). */
    fun nextBelow(e: Element.Text): Element.Text? {
        val t = (template as? Load.Ready)?.value ?: return null
        return t.elements.filterIsInstance<Element.Text>()
            .filter { it.editable && it.id != e.id && it.rotation == 0f && it.box.y > e.box.y + e.box.h * 0.5f &&
                minOf(it.box.x + it.box.w, e.box.x + e.box.w) - maxOf(it.box.x, e.box.x) > minOf(it.box.w, e.box.w) * 0.5f }
            .minByOrNull { it.box.y }
    }

    fun onTap(e: Element?) {
        when (e) {
            is Element.Checkbox -> { record("check:${e.id}:${System.nanoTime()}"); state = state.toggle(e); dirty = true; selectedId = null }
            is Element.Text -> selectedId = e.id
            else -> selectedId = null
        }
    }

    fun setText(e: Element.Text, text: String) {
        if (text == state.textOf(e)) return
        edit(e, "text") { it.copy(text = text.takeIf { t -> t != e.text }) }
    }
    fun setColor(e: Element.Text, hex: String?) = edit(e, "color:$hex") { it.copy(color = hex?.takeIf { h -> !h.equals(e.style.color, true) }) }
    fun setFont(e: Element.Text, family: String?) = edit(e, "font:$family") { it.copy(fontFamily = family?.takeIf { f -> f != e.style.fontFamily }) }
    fun reset(e: Element.Text) = edit(e, "reset:${System.nanoTime()}") { TextOverride() }

    private fun edit(e: Element.Text, kind: String, change: (TextOverride) -> TextOverride) {
        val next = state.withOverride(e.id, change)
        if (next == state) return
        record("${e.id}:$kind")
        state = next
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
