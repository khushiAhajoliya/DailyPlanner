package com.dailyplanner.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors backend/src/schema/template.ts. Coordinates are Figma design units.
 */
@Serializable
data class Template(
    val id: String,
    val name: String,
    val category: String,
    val version: Int,
    val page: Page,
    val editorOptions: EditorOptions,
    val elements: List<Element>,
)

@Serializable
data class Page(
    val width: Float,
    val height: Float,
    val background: String,
    val paper: Paper,
)

@Serializable
data class Paper(val name: String, val widthPt: Int, val heightPt: Int)

@Serializable
data class EditorOptions(val colors: List<NamedColor>, val fonts: List<String>)

@Serializable
data class NamedColor(val name: String, val hex: String)

@Serializable
data class Stroke(val color: String, val width: Float, val align: String)

@Serializable
data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

@Serializable
sealed interface Element {
    val id: String

    @Serializable
    @SerialName("image")
    data class Image(
        override val id: String,
        val name: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val src: String,
        val opacity: Float = 1f,
    ) : Element

    @Serializable
    @SerialName("rect")
    data class Rect(
        override val id: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val fill: String? = null,
        val fillOpacity: Float = 1f,
        val stroke: Stroke? = null,
        val radius: Float = 0f,
        /** Design's "selected day" marker, moved to the weekday of the page's date. */
        val weekdayHighlight: Boolean = false,
    ) : Element

    @Serializable
    @SerialName("ellipse")
    data class Ellipse(
        override val id: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val fill: String? = null,
        val fillOpacity: Float = 1f,
        val stroke: Stroke? = null,
        val radius: Float = 0f,
        /** Design's "selected day" marker, moved to the weekday of the page's date. */
        val weekdayHighlight: Boolean = false,
    ) : Element

    @Serializable
    @SerialName("line")
    data class Line(
        override val id: String,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val color: String,
        val width: Float,
    ) : Element

    @Serializable
    @SerialName("checkbox")
    data class Checkbox(
        override val id: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val fill: String? = null,
        val stroke: Stroke? = null,
        val checked: Boolean,
        val shape: String = "ellipse",
        val radius: Float = 0f,
        val check: CheckMark,
    ) : Element

    @Serializable
    @SerialName("text")
    data class Text(
        override val id: String,
        val name: String,
        val text: String,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val box: Box,
        val maxLines: Int,
        val style: TextStyle,
        val runs: List<TextRun> = emptyList(),
        val list: String = "none",
        val listStart: Int = 1,
        val rotation: Float = 0f,
        val role: String = "text",
        val format: String? = null,
        val prefix: String = "",
        val uppercase: Boolean = false,
        val editable: Boolean = true,
        /** Empty writing area generated from a ruled line or box. */
        val slot: Boolean = false,
        /** Weekday letter, 1 = Monday … 7 = Sunday. */
        val weekday: Int? = null,
    ) : Element
}

@Serializable
data class CheckMark(val src: String, val dx: Float, val dy: Float, val w: Float, val h: Float)

@Serializable
data class TextStyle(
    val fontFamily: String,
    val fontWeight: Int,
    val italic: Boolean,
    val fontSize: Float,
    val lineHeight: Float,
    val letterSpacing: Float,
    val color: String,
    val align: String,
)

@Serializable
data class TextRun(
    val start: Int,
    val end: Int,
    val fontWeight: Int? = null,
    val fontFamily: String? = null,
    val italic: Boolean? = null,
)
