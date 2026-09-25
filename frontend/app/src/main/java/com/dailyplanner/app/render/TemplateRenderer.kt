package com.dailyplanner.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import android.util.LruCache
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Stroke
import com.dailyplanner.app.data.model.Template

/**
 * Draws a template (plus the user's edits) onto an android.graphics.Canvas in design
 * units. The same code paints the editor, the thumbnails and the A4 PDF, so what the
 * user sees is exactly what gets exported.
 */
class TemplateRenderer(private val fonts: FontRegistry, private val images: ImageCache) {

    // ---------------------------------------------------------------- shapes
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#DD8D00")
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val highlightFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 221, 141, 0) }
    private val rect = RectF()
    private val tick = android.graphics.Path()
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    @Synchronized
    /** [hiddenTextId]: text being typed in place — its glyphs are drawn by the inline editor instead. */
    fun draw(canvas: Canvas, template: Template, state: PlanState, selectedId: String? = null, hiddenTextId: String? = null) {
        val page = template.page
        canvas.save()
        canvas.clipRect(0f, 0f, page.width, page.height)
        fill.color = parse(page.background)
        fill.alpha = 255
        canvas.drawRect(0f, 0f, page.width, page.height, fill)

        // Weekday row (M T W T F S S): highlight the day of the page's date.
        val selectedDay = DateFields.pageDate(template, state)?.dayOfWeek?.value
        val dayLetter = selectedDay?.let { d -> template.elements.firstOrNull { it is Element.Text && it.weekday == d } as? Element.Text }
        val hasMarker = template.elements.any { (it is Element.Rect && it.weekdayHighlight) || (it is Element.Ellipse && it.weekdayHighlight) }

        for (e in template.elements) when (e) {
            is Element.Image -> images.peek(e.src)?.let { bmp ->
                bitmapPaint.alpha = (e.opacity * 255).toInt()
                rect.set(e.x, e.y, e.x + e.w, e.y + e.h)
                canvas.drawBitmap(bmp, null, rect, bitmapPaint)
            }
            is Element.Rect -> {
                val (x, y) = markerPos(e.weekdayHighlight, e.x, e.y, e.w, e.h, dayLetter)
                shape(canvas, x, y, e.w, e.h, e.fill, e.fillOpacity, e.stroke, e.radius, oval = false)
            }
            is Element.Ellipse -> {
                val (x, y) = markerPos(e.weekdayHighlight, e.x, e.y, e.w, e.h, dayLetter)
                shape(canvas, x, y, e.w, e.h, e.fill, e.fillOpacity, e.stroke, 0f, oval = true)
            }
            is Element.Line -> {
                stroke.color = parse(e.color)
                stroke.strokeWidth = e.width
                stroke.strokeCap = Paint.Cap.BUTT
                canvas.drawLine(e.x1, e.y1, e.x2, e.y2, stroke)
            }
            is Element.Checkbox -> {
                shape(canvas, e.x, e.y, e.w, e.h, e.fill, 1f, e.stroke, e.radius, oval = e.shape == "ellipse")
                if (state.isChecked(e)) {
                    val cx = e.x + e.check.dx; val cy = e.y + e.check.dy
                    if (e.check.src.isEmpty()) {
                        // No check-mark layer in Figma: draw a tick in the circle's stroke colour.
                        tick.reset()
                        tick.moveTo(cx + e.check.w * 0.06f, cy + e.check.h * 0.52f)
                        tick.lineTo(cx + e.check.w * 0.36f, cy + e.check.h * 0.92f)
                        tick.lineTo(cx + e.check.w * 0.96f, cy + e.check.h * 0.08f)
                        tickPaint.color = parse(e.stroke?.color ?: "#000000")
                        tickPaint.strokeWidth = e.check.h * 0.16f
                        canvas.drawPath(tick, tickPaint)
                    } else images.peek(e.check.src)?.let { bmp ->
                        bitmapPaint.alpha = 255
                        rect.set(cx, cy, cx + e.check.w, cy + e.check.h)
                        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
                    }
                }
            }
            is Element.Text -> {
                if (!hasMarker && e === dayLetter) {
                    // No marker in the design: a soft circle in the letter's own colour.
                    fill.color = parse(state.colorOf(e)); fill.alpha = 45
                    val r = maxOf(e.w, e.h) * 0.62f
                    canvas.drawCircle(e.x + e.w / 2f, e.y + e.h / 2f, r, fill)
                }
                text(canvas, e, state, markersOnly = e.id == hiddenTextId)
            }
        }

        selectedId?.let { id -> template.elements.firstOrNull { it.id == id } }?.let { sel ->
            hitBounds(sel)?.let { b ->
                canvas.save()
                if (sel is Element.Text && sel.rotation != 0f) canvas.rotate(sel.rotation, b.centerX(), b.centerY())
                val r = if (sel is Element.Text && sel.rotation != 0f) unrotated(sel) else b
                r.inset(-6f, -4f)
                canvas.drawRoundRect(r, 10f, 10f, highlightFill)
                canvas.drawRoundRect(r, 10f, 10f, highlight)
                canvas.restore()
            }
        }
        canvas.restore()
    }

    /** A weekday marker moves (centred) onto the selected letter; any other shape stays put. */
    private fun markerPos(marker: Boolean, x: Float, y: Float, w: Float, h: Float, letter: Element.Text?): Pair<Float, Float> =
        if (!marker || letter == null) x to y
        else (letter.x + letter.w / 2f - w / 2f) to (letter.y + letter.h / 2f - h / 2f)

    private fun shape(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
        fillHex: String?, fillOpacity: Float, s: Stroke?, radius: Float, oval: Boolean,
    ) {
        if (fillHex != null) {
            fill.color = parse(fillHex)
            fill.alpha = (fillOpacity * 255).toInt()
            rect.set(x, y, x + w, y + h)
            if (oval) canvas.drawOval(rect, fill) else canvas.drawRoundRect(rect, radius, radius, fill)
        }
        if (s != null) {
            // Figma stroke alignment: INSIDE keeps the stroke within the shape bounds.
            val inset = when (s.align) {
                "INSIDE" -> s.width / 2f
                "OUTSIDE" -> -s.width / 2f
                else -> 0f
            }
            stroke.color = parse(s.color)
            stroke.strokeWidth = s.width
            rect.set(x + inset, y + inset, x + w - inset, y + h - inset)
            val r = (radius - inset).coerceAtLeast(0f)
            if (oval) canvas.drawOval(rect, stroke) else canvas.drawRoundRect(rect, r, r, stroke)
        }
    }

    // ------------------------------------------------------------------ text

    private class Op(val text: CharSequence, val x: Float, val y: Float, val paint: TextPaint, val marker: Boolean = false)

    private data class TextKey(val id: String, val text: String, val color: String, val family: String?)

    private val layoutCache = LruCache<TextKey, List<Op>>(600)

    private fun text(canvas: Canvas, e: Element.Text, state: PlanState, markersOnly: Boolean = false) {
        val key = TextKey(e.id, state.textOf(e), state.colorOf(e), state.familyOf(e))
        val ops = layoutCache.get(key) ?: layout(e, key).also { layoutCache.put(key, it) }
        if (e.rotation != 0f) {
            canvas.save()
            canvas.rotate(e.rotation, e.box.x + e.box.w / 2f, e.box.y + e.box.h / 2f)
        }
        for (op in ops) if (!markersOnly || op.marker) canvas.drawText(op.text, 0, op.text.length, op.x, op.y, op.paint)
        if (e.rotation != 0f) canvas.restore()
    }

    private data class PaintKey(val family: String, val weight: Int, val italic: Boolean, val size: Float, val color: Int, val spacing: Float)

    private val paints = HashMap<PaintKey, TextPaint>()

    private fun paint(family: String, weight: Int, italic: Boolean, size: Float, color: Int, spacing: Float): TextPaint =
        paints.getOrPut(PaintKey(family, weight, italic, size, color, spacing)) {
            val face = fonts.resolve(family, weight, italic)
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
                typeface = face.typeface
                isFakeBoldText = face.fakeBold
                textSkewX = if (face.fakeItalic) -0.25f else 0f
                textSize = size
                this.color = color
                letterSpacing = if (size > 0) spacing / size else 0f
            }
        }

    private class FaceSpan(private val p: TextPaint) : MetricAffectingSpan() {
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) {
            tp.typeface = p.typeface
            tp.isFakeBoldText = p.isFakeBoldText
            tp.textSkewX = p.textSkewX
        }
    }

    /** Lays the text out once: wraps inside the writable box, keeps Figma's line height and list markers. */
    private fun layout(e: Element.Text, key: TextKey): List<Op> {
        val st = e.style
        val text = key.text
        val color = parse(key.color)
        val runs = adaptRuns(e.text, text, e.runs)

        var size = st.fontSize
        fun paintAt(i: Int): TextPaint {
            val r = runs.firstOrNull { i >= it.start && i < it.end }
            val family = key.family ?: r?.fontFamily ?: st.fontFamily
            return paint(family, r?.fontWeight ?: st.fontWeight, r?.italic ?: st.italic, size, color, st.letterSpacing)
        }

        val isList = e.list != "none"
        val indent = if (isList) st.fontSize * 1.5f else 0f
        // Small slack so the untouched Figma text never wraps because of shaping differences.
        val width = maxOf(e.box.w, e.w * 1.04f) - indent
        // A time/date that got longer ("6 AM" -> "10:30 AM") shrinks to fit its cell instead of being cut off.
        if (e.maxLines == 1 && e.role != "text" && !isList) {
            val full = paint(key.family ?: st.fontFamily, st.fontWeight, st.italic, st.fontSize, color, st.letterSpacing).measureText(text)
            if (full > width) size = st.fontSize * maxOf(0.6f, width / full)
        }
        val base = paint(key.family ?: st.fontFamily, st.fontWeight, st.italic, size, color, st.letterSpacing)
        val fm = base.fontMetrics
        // Figma centres the font's ascent+descent inside each line box.
        fun baseline(line: Int) = e.box.y + line * st.lineHeight + (st.lineHeight - (fm.descent - fm.ascent)) / 2f - fm.ascent

        val paragraphs = if (isList) text.split('\n') else listOf(text)

        val ops = ArrayList<Op>()
        var line = 0
        var offset = 0
        outer@ for ((pi, para) in paragraphs.withIndex()) {
            if (line >= e.maxLines) break
            val paraOffset = offset
            offset += para.length + 1

            val content = SpannableString(para.replace(' ', '\n'))
            var i = 0
            while (i < content.length) {
                val p = paintAt(paraOffset + i)
                var j = i + 1
                while (j < content.length && paintAt(paraOffset + j) === p) j++
                if (p !== base) content.setSpan(FaceSpan(p), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = j
            }
            val sl = StaticLayout.Builder.obtain(content, 0, content.length, base, width.toInt().coerceAtLeast(1))
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            if (isList && para.isNotBlank()) {
                val mp = paintAt(paraOffset)
                if (e.list == "ordered") {
                    val m = "${e.listStart + pi}."
                    ops += Op(m, e.box.x + indent * 0.8f - mp.measureText(m), baseline(line), mp, marker = true)
                } else {
                    ops += Op("•", e.box.x + indent * 0.55f - mp.measureText("•") / 2f, baseline(line), mp, marker = true)
                }
            }

            for (li in 0 until sl.lineCount) {
                if (line >= e.maxLines) break@outer
                val s = sl.getLineStart(li)
                var end = sl.getLineEnd(li)
                while (end > s && (content[end - 1] == '\n' || content[end - 1] == ' ')) end--
                val hasMore = li < sl.lineCount - 1 || pi < paragraphs.lastIndex
                val y = baseline(line)

                if (line == e.maxLines - 1 && hasMore) {
                    // Last line that fits: ellipsize everything that is left.
                    val rest = buildString {
                        append(content.subSequence(s, content.length).toString().replace('\n', ' '))
                        paragraphs.drop(pi + 1).forEach { append(' ').append(it) }
                    }.trim()
                    val p = paintAt(paraOffset + s)
                    val shown = TextUtils.ellipsize(rest, p, width, TextUtils.TruncateAt.END)
                    ops += Op(shown, alignX(e, indent, width, p.measureText(shown, 0, shown.length)), y, p)
                } else {
                    // Draw the line in style segments so mixed weights stay intact.
                    val segs = ArrayList<Pair<IntRange, TextPaint>>()
                    var a = s
                    while (a < end) {
                        val p = paintAt(paraOffset + a)
                        var b = a + 1
                        while (b < end && paintAt(paraOffset + b) === p) b++
                        segs += (a until b) to p
                        a = b
                    }
                    val lineW = segs.sumOf { (r, p) -> p.measureText(content, r.first, r.last + 1).toDouble() }.toFloat()
                    var x = alignX(e, indent, width, lineW)
                    for ((r, p) in segs) {
                        val piece = content.subSequence(r.first, r.last + 1).toString()
                        ops += Op(piece, x, y, p)
                        x += p.measureText(piece)
                    }
                }
                line++
            }
        }
        return ops
    }

    private fun alignX(e: Element.Text, indent: Float, width: Float, lineW: Float): Float = when (e.style.align) {
        "CENTER" -> e.box.x + indent + (e.box.w - indent - lineW) / 2f
        "RIGHT" -> e.box.x + e.box.w - lineW
        else -> e.box.x + indent
    }.let { if (e.style.align == "CENTER" && lineW > width) e.box.x + indent else it }

    // ------------------------------------------------------------- hit test

    private fun unrotated(e: Element.Text) = RectF(e.box.x, e.box.y, e.box.x + e.box.w, e.box.y + e.box.h)

    /** Axis-aligned bounds of an interactive element, or null if it is not interactive. */
    fun hitBounds(e: Element): RectF? = when (e) {
        is Element.Checkbox -> RectF(e.x, e.y, e.x + e.w, e.y + e.h)
        is Element.Text -> {
            val r = unrotated(e)
            if (e.rotation % 180f != 0f) {
                val cx = r.centerX(); val cy = r.centerY()
                RectF(cx - r.height() / 2, cy - r.width() / 2, cx + r.height() / 2, cy + r.width() / 2)
            } else r
        }
        else -> null
    }

    /** Topmost editable text or checkbox under a point in design units. */
    fun hitTest(template: Template, x: Float, y: Float): Element? {
        val slop = 10f
        return template.elements.asReversed().firstOrNull { e ->
            val b = hitBounds(e) ?: return@firstOrNull false
            // Writing areas on ruled lines are thin: accept taps on the whole line gap above the rule.
            val up = if (e is Element.Text && e.slot && e.maxLines == 1) e.style.lineHeight * 0.6f else 0f
            (e !is Element.Text || e.editable) && x >= b.left - slop && x <= b.right + slop && y >= b.top - slop - up && y <= b.bottom + slop
        }
    }

    companion object {
        private val colors = HashMap<String, Int>()
        fun parse(hex: String): Int = colors.getOrPut(hex) { Color.parseColor(hex) }
    }
}
