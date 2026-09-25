package com.dailyplanner.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SelectableDates
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.dailyplanner.app.render.DateFields
import com.dailyplanner.app.render.adaptRuns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.FontRegistry
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.ui.components.PageGeometry
import com.dailyplanner.app.ui.theme.Amber
import com.dailyplanner.app.ui.theme.Ink
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.roundToInt

// ------------------------------------------------------------------ time / date formats

private fun formatter(pattern: String, defaultYear: Boolean = false): DateTimeFormatter =
    DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).apply {
        if (defaultYear && !pattern.contains('y')) parseDefaulting(ChronoField.YEAR, LocalDate.now().year.toLong())
    }.toFormatter(Locale.US)

private fun Element.Text.valueOf(text: String) = text.removePrefix(prefix).trim()
private fun Element.Text.compose(value: String) = prefix + if (uppercase) value.uppercase(Locale.US) else value

private fun Element.Text.parseTime(text: String): LocalTime {
    val v = valueOf(text).uppercase(Locale.US)
    return listOfNotNull(format, "h:mm a", "h a", "h:mma", "ha").firstNotNullOfOrNull { p ->
        runCatching { LocalTime.parse(v, formatter(p)) }.getOrNull()
    } ?: LocalTime.of(9, 0)
}

/** Keeps the design's time style ("6 AM" vs "8:00 AM"), adding minutes only when needed. */
private fun Element.Text.formatTime(t: LocalTime): String {
    val p = format ?: "h:mm a"
    return t.format(formatter(if (t.minute != 0 && !p.contains("mm")) p.replace("h", "h:mm") else p))
}

// ------------------------------------------------------------------ inline text field

/**
 * A text field placed exactly over the element on the page, using the element's own
 * font, size, line height, colour and alignment — so typing happens "on the paper".
 */
@Composable
fun InlineTextField(
    element: Element.Text,
    state: PlanState,
    geometry: PageGeometry,
    fonts: FontRegistry,
    onText: (String) -> Unit,
    /** One-line rows: the keyboard's Next / Enter moves to the row below (null = Done). */
    onNext: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val k = geometry.k
    val st = element.style
    val family = state.familyOf(element)
    val baseFamily = family ?: st.fontFamily
    val face = remember(baseFamily, st.fontWeight, st.italic) { fonts.resolve(baseFamily, st.fontWeight, st.italic) }
    val pxToSp = { px: Float -> (px / (density.density * density.fontScale)).sp }
    val indent = if (element.list != "none") st.fontSize * 1.5f * k else 0f
    val singleLine = element.maxLines == 1

    val current = state.textOf(element)
    var value by remember(element.id) { mutableStateOf(TextFieldValue(current, TextRange(current.length))) }
    // Undo / Redo / Reset change the text from outside: show it.
    LaunchedEffect(current) { if (current != value.text) value = TextFieldValue(current, TextRange(current.length)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(element.id) { focus.requestFocus() }

    // Mixed styles ("Date:" medium + value semibold) look the same while typing as on the page.
    val styled = remember(element.id, family) {
        VisualTransformation { text ->
            val runs = adaptRuns(element.text, text.text, element.runs)
            val out = if (runs.isEmpty()) text else androidx.compose.ui.text.buildAnnotatedString {
                append(text.text)
                for (r in runs) {
                    if (r.start >= text.length) continue
                    val tf = fonts.resolve(family ?: r.fontFamily ?: st.fontFamily, r.fontWeight ?: st.fontWeight, r.italic ?: st.italic)
                    addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily(tf.typeface)), r.start, minOf(r.end, text.length))
                }
            }
            TransformedText(out, OffsetMapping.Identity)
        }
    }

    val topLeft = geometry.toScreen(element.box.x, element.box.y)
    val wDp = with(density) { (element.box.w * k).toDp() }
    val hDp = with(density) { (element.box.h * k).toDp() }

    BasicTextField(
        value = value,
        onValueChange = { v ->
            // Stay inside the design's lines: no more lines than the area holds.
            if (!singleLine && v.text.count { it == '\n' } + 1 > element.maxLines) return@BasicTextField
            value = v
            onText(v.text)
        },
        singleLine = singleLine,
        maxLines = element.maxLines,
        visualTransformation = styled,
        cursorBrush = SolidColor(Amber),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = if (singleLine) (if (onNext != null) ImeAction.Next else ImeAction.Done) else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }),
        textStyle = TextStyle(
            color = Color(android.graphics.Color.parseColor(state.colorOf(element))),
            fontFamily = FontFamily(face.typeface),
            fontStyle = if (face.fakeItalic) FontStyle.Italic else FontStyle.Normal,
            fontSize = pxToSp(st.fontSize * k),
            lineHeight = pxToSp(st.lineHeight * k),
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            textAlign = when (st.align) { "CENTER" -> TextAlign.Center; "RIGHT" -> TextAlign.End; else -> TextAlign.Start },
        ),
        modifier = Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .size(wDp, hDp)
            .graphicsLayer { rotationZ = element.rotation }
            .padding(start = with(density) { indent.toDp() })
            .focusRequester(focus),
    )
}

// ------------------------------------------------------------------ toolbar

private enum class Tool { None, Color, Font }

/** Compact PlanWiz-style toolbar that floats above the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditToolbar(
    element: Element.Text,
    template: Template,
    state: PlanState,
    fonts: FontRegistry,
    onText: (String) -> Unit,
    onColor: (String?) -> Unit,
    onFont: (String?) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    /** Set an alarm for this task (shown once the task has text). */
    onAlarm: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var tool by remember(element.id) { mutableStateOf(Tool.None) }
    var showTime by remember(element.id) { mutableStateOf(element.role == "time") }
    var showDate by remember(element.id) { mutableStateOf(element.role == "date") }
    val text = state.textOf(element)

    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (tool != Tool.None) {
            Surface(shape = RoundedCornerShape(18.dp), color = Ink, shadowElevation = 8.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                when (tool) {
                    Tool.Color -> LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(12.dp),
                    ) {
                        item { Swatch(Color(android.graphics.Color.parseColor(element.style.color)), state.values[element.id]?.color == null, original = true) { onColor(null) } }
                        items(template.editorOptions.colors.filterNot { it.hex.equals(element.style.color, true) }, key = { it.hex }) { c ->
                            Swatch(Color(android.graphics.Color.parseColor(c.hex)), state.colorOf(element).equals(c.hex, true)) { onColor(c.hex) }
                        }
                    }
                    Tool.Font -> {
                        val families = remember(template) { template.editorOptions.fonts.filter { fonts.has(it) } }
                        val current = state.familyOf(element) ?: element.style.fontFamily
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(10.dp)) {
                            items(families, key = { it }) { family ->
                                val ff = remember(family) { FontFamily(fonts.resolve(family, 400, false).typeface) }
                                val selected = family == current
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (selected) Amber else Color.White.copy(alpha = 0.08f))
                                        .clickable { onFont(if (family == element.style.fontFamily) null else family) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                ) { Text(family, fontFamily = ff, fontSize = 16.sp, color = Color.White) }
                            }
                        }
                    }
                    Tool.None -> Unit
                }
            }
        }

        Surface(shape = RoundedCornerShape(50), color = Ink, shadowElevation = 8.dp) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (element.role == "time") ToolButton(Icons.Outlined.Schedule, "Change time", false) { showTime = true }
                if (element.role == "date") ToolButton(Icons.Outlined.DateRange, "Change date", false) { showDate = true }
                ToolButton(Icons.Outlined.Palette, "Color", tool == Tool.Color) { tool = if (tool == Tool.Color) Tool.None else Tool.Color }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(if (tool == Tool.Font) Amber else Color.Transparent)
                        .clickable { tool = if (tool == Tool.Font) Tool.None else Tool.Font }
                        .size(48.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Aa", color = Color.White, fontSize = 18.sp) }
                if (onAlarm != null && text.isNotBlank()) ToolButton(Icons.Outlined.Alarm, "Alarm", false, onAlarm)
                ToolButton(Icons.Outlined.RestartAlt, "Reset", false, onReset)
                ToolButton(Icons.Outlined.Check, "Done", false, onDone)
            }
        }
    }

    if (showTime) {
        val initial = remember(text) { element.parseTime(text) }
        val tp = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = { onText(element.compose(element.formatTime(LocalTime.of(tp.hour, tp.minute)))); showTime = false }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(tp) },
        )
    }
    if (showDate) {
        val today = LocalDate.now()
        val initial = remember(text) { (DateFields.parse(element, text) ?: today).let { if (it.isBefore(today)) today else it } }
        val dp = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = FutureDates,
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dp.selectedDateMillis?.let {
                        onText(DateFields.format(element, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                    showDate = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) {
            // Calendar only: typing digits in the text mode made them shift (BUG-003).
            DatePicker(dp, showModeToggle = false)
        }
    }
}

/** Only today and later can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
object FutureDates : SelectableDates {
    private fun todayUtc() = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc()
    override fun isSelectableYear(year: Int) = year >= LocalDate.now().year
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.clip(CircleShape).background(if (active) Amber else Color.Transparent),
    ) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, original: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .border(2.dp, if (selected) Amber else Color.Transparent, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (original) Text("A", color = if (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue > 0.5f) Color.Black else Color.White, fontSize = 12.sp)
    }
}
