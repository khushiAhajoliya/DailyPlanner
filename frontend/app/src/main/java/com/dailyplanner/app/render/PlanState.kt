package com.dailyplanner.app.render

import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.TextOverride
import com.dailyplanner.app.data.model.TextRun

/** User edits layered over a template. Immutable so Compose can diff it cheaply. */
data class PlanState(
    val values: Map<String, TextOverride> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
) {
    fun textOf(e: Element.Text): String = values[e.id]?.text ?: e.text
    fun colorOf(e: Element.Text): String = values[e.id]?.color ?: e.style.color
    fun familyOf(e: Element.Text): String? = values[e.id]?.fontFamily
    fun isChecked(e: Element.Checkbox): Boolean = checks[e.id] ?: e.checked

    fun withOverride(id: String, change: (TextOverride) -> TextOverride): PlanState {
        val next = change(values[id] ?: TextOverride())
        val cleaned = if (next == TextOverride()) values - id else values + (id to next)
        return copy(values = cleaned)
    }

    fun toggle(e: Element.Checkbox) = copy(checks = checks + (e.id to !isChecked(e)))
}

/**
 * Keep the design's mixed styling (e.g. "Date:" medium + "JANUARY 5, 2026" semibold)
 * after the text is edited: runs inside the unchanged prefix survive, and the rest of
 * the new text takes the style that followed the prefix in the original.
 */
fun adaptRuns(original: String, edited: String, runs: List<TextRun>): List<TextRun> {
    if (runs.isEmpty() || original == edited) return runs
    var p = 0
    while (p < original.length && p < edited.length && original[p] == edited[p]) p++
    if (p == 0) {
        // Whole text rewritten: keep the style that covered most of the original text.
        val main = runs.maxBy { it.end - it.start }
        return if ((main.end - main.start) * 2 >= original.length && edited.isNotEmpty()) listOf(main.copy(start = 0, end = edited.length))
        else emptyList()
    }
    val kept = runs.mapNotNull { r ->
        when {
            r.end <= p -> r
            r.start < p -> r.copy(end = p)
            else -> null
        }
    }
    // Appended text continues the style of the last original character.
    val styleAt = if (p < original.length) p else original.length - 1
    val tail = runs.firstOrNull { styleAt >= it.start && styleAt < it.end }
    return if (tail != null && p < edited.length) kept + tail.copy(start = p, end = edited.length) else kept
}
