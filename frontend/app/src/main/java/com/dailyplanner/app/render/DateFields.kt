package com.dailyplanner.app.render

import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Template
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale

/** Reading and writing date fields in the format the design uses. */
object DateFields {
    fun formatter(pattern: String, defaultYear: Boolean = false): DateTimeFormatter =
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).apply {
            if (defaultYear && !pattern.contains('y')) parseDefaulting(ChronoField.YEAR, LocalDate.now().year.toLong())
        }.toFormatter(Locale.US).withResolverStyle(ResolverStyle.SMART)

    fun valueOf(e: Element.Text, text: String) = text.removePrefix(e.prefix).trim()

    fun parse(e: Element.Text, text: String): LocalDate? {
        val v = valueOf(e, text)
        if (v.isEmpty()) return null
        val pattern = e.format ?: "MMMM d, yyyy"
        return runCatching { LocalDate.parse(v, formatter(pattern, defaultYear = true)) }.getOrNull()
            // "Friday, 01 June" has no year: ignore the weekday name and use this year.
            ?: runCatching {
                LocalDate.parse(v.substringAfter(", "), formatter(pattern.substringAfter(", "), defaultYear = true))
            }.getOrNull()
    }

    fun format(e: Element.Text, d: LocalDate): String {
        val v = d.format(formatter(e.format ?: "MMMM d, yyyy"))
        return e.prefix + if (e.uppercase) v.uppercase(Locale.US) else v
    }

    /** The page's date: the first date field that holds a readable date. */
    fun pageDate(t: Template, state: PlanState): LocalDate? =
        t.elements.asSequence().filterIsInstance<Element.Text>().filter { it.role == "date" }
            .firstNotNullOfOrNull { parse(it, state.textOf(it)) }
}
