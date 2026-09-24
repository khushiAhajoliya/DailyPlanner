package com.dailyplanner.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Template
import com.dailyplanner.app.render.A4
import com.dailyplanner.app.render.ImageCache
import com.dailyplanner.app.render.PlanState
import com.dailyplanner.app.render.TemplateRenderer
import com.dailyplanner.app.ui.theme.Line

/**
 * Where the design sits on screen: screen px = origin + design units * k.
 * The whole A4 sheet is always fitted inside the available area — never zoomed or scrolled.
 */
data class PageGeometry(
    val originX: Float, val originY: Float, val k: Float,
    val sheetLeft: Float, val sheetTop: Float, val sheetW: Float, val sheetH: Float,
) {
    fun toScreen(x: Float, y: Float) = Offset(originX + x * k, originY + y * k)
    fun toDesign(p: Offset) = Offset((p.x - originX) / k, (p.y - originY) / k)

    companion object {
        fun of(template: Template, w: Float, h: Float): PageGeometry {
            // A4-shaped designs sit on an A4 sheet; other designs (e.g. 1080x2424 phone frames)
            // fill the screen in their own shape. PDF export is always A4.
            val (pw, ph) = paperSize(template)
            val sheet = A4.fit(pw, ph, w, h)
            val sheetW = pw * sheet.scale
            val sheetH = ph * sheet.scale
            val design = A4.fit(template.page.width, template.page.height, sheetW, sheetH)
            return PageGeometry(sheet.dx + design.dx, sheet.dy + design.dy, design.scale, sheet.dx, sheet.dy, sheetW, sheetH)
        }
    }
}

private fun isA4(t: Template) = kotlin.math.abs(t.page.height / t.page.width - A4.HEIGHT_PT / A4.WIDTH_PT.toFloat()) < 0.03f

/** On-screen paper size (in any unit — only the ratio matters). */
private fun paperSize(t: Template): Pair<Float, Float> =
    if (isA4(t)) A4.WIDTH_PT.toFloat() to A4.HEIGHT_PT.toFloat() else t.page.width to t.page.height

private val paperPaint = android.graphics.Paint()
private val edgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    style = android.graphics.Paint.Style.STROKE; color = Line.toArgb(); strokeWidth = 1.5f
}

private fun DrawScope.drawSheet(
    template: Template,
    state: PlanState,
    renderer: TemplateRenderer,
    selectedId: String?,
    hiddenTextId: String?,
) {
    val g = PageGeometry.of(template, size.width, size.height)
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        paperPaint.color = TemplateRenderer.parse(template.page.background)
        nc.drawRect(g.sheetLeft, g.sheetTop, g.sheetLeft + g.sheetW, g.sheetTop + g.sheetH, paperPaint)
        nc.drawRect(g.sheetLeft, g.sheetTop, g.sheetLeft + g.sheetW, g.sheetTop + g.sheetH, edgePaint)
        nc.save()
        nc.translate(g.originX, g.originY)
        nc.scale(g.k, g.k)
        renderer.draw(nc, template, state, selectedId, hiddenTextId)
        nc.restore()
    }
}

/** Static A4 preview, used for thumbnails. */
@Composable
fun PlannerThumbnail(
    template: Template,
    state: PlanState,
    renderer: TemplateRenderer,
    images: ImageCache,
    modifier: Modifier = Modifier,
) {
    // Thumbnails keep a uniform A4 card so the grid lines up; the design is fitted inside.
    Canvas(modifier.aspectRatio(A4.WIDTH_PT / A4.HEIGHT_PT.toFloat())) {
        images.version // redraw when images arrive
        drawSheet(template, state, renderer, null, null)
    }
}

/**
 * Fixed A4 page. Tap a text or checkbox to edit it. [overlay] is laid over the page with
 * its geometry so an inline editor can sit exactly on the text being edited.
 * [liftPx] moves the page up — only used when the keyboard would cover the edited line.
 */
@Composable
fun PlannerPage(
    template: Template,
    state: PlanState,
    renderer: TemplateRenderer,
    images: ImageCache,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    hiddenTextId: String? = null,
    liftPx: Float = 0f,
    onTap: ((Element?) -> Unit)? = null,
    overlay: (@Composable (PageGeometry) -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val geometry = PageGeometry.of(template, w, h)
        val currentTap by rememberUpdatedState(onTap)
        val currentGeometry by rememberUpdatedState(geometry)
        val currentTemplate by rememberUpdatedState(template)

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = -liftPx }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val cb = currentTap ?: return@detectTapGestures
                        val d = currentGeometry.toDesign(pos)
                        cb(renderer.hitTest(currentTemplate, d.x, d.y))
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                images.version
                drawSheet(template, state, renderer, selectedId, hiddenTextId)
            }
            overlay?.invoke(geometry)
        }
    }
}
