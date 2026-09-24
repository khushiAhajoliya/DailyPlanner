package com.dailyplanner.app.data.model

import kotlinx.serialization.Serializable

/** What the user changed on a single text element. Null fields keep the template value. */
@Serializable
data class TextOverride(
    val text: String? = null,
    val color: String? = null,
    val fontFamily: String? = null,
)

@Serializable
data class PlanInput(
    val templateId: String,
    val title: String,
    val values: Map<String, TextOverride> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class Plan(
    val id: String,
    val templateId: String,
    val title: String,
    val values: Map<String, TextOverride> = emptyMap(),
    val checks: Map<String, Boolean> = emptyMap(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ReminderInput(
    val title: String,
    val note: String = "",
    /** ISO-8601 with offset, e.g. 2026-09-24T09:30:00+05:30 */
    val remindAt: String,
    val enabled: Boolean = true,
    val planId: String? = null,
    val elementId: String? = null,
)

@Serializable
data class Reminder(
    val id: String,
    val title: String,
    val note: String = "",
    val remindAt: String,
    val enabled: Boolean = true,
    val planId: String? = null,
    val elementId: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    fun toInput() = ReminderInput(title, note, remindAt, enabled, planId, elementId)
}
