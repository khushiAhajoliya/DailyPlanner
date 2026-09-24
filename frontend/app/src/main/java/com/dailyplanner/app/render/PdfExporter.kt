package com.dailyplanner.app.render

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.dailyplanner.app.data.model.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** A4 geometry: the design is scaled uniformly to fit the sheet and centered. */
object A4 {
    const val WIDTH_PT = 595
    const val HEIGHT_PT = 842

    data class Fit(val scale: Float, val dx: Float, val dy: Float)

    fun fit(designW: Float, designH: Float, sheetW: Float, sheetH: Float): Fit {
        val s = minOf(sheetW / designW, sheetH / designH)
        return Fit(s, (sheetW - designW * s) / 2f, (sheetH - designH * s) / 2f)
    }
}

class PdfExporter(
    private val context: Context,
    private val renderer: TemplateRenderer,
    private val images: ImageCache,
) {
    private suspend fun write(template: Template, state: PlanState, out: OutputStream) {
        images.preload(template)
        withContext(Dispatchers.Default) {
            val doc = PdfDocument()
            try {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4.WIDTH_PT, A4.HEIGHT_PT, 1).create())
                val c = page.canvas
                // Sheet margins use the template's own paper colour.
                c.drawColor(TemplateRenderer.parse(template.page.background))
                val fit = A4.fit(template.page.width, template.page.height, A4.WIDTH_PT.toFloat(), A4.HEIGHT_PT.toFloat())
                c.translate(fit.dx, fit.dy)
                c.scale(fit.scale, fit.scale)
                renderer.draw(c, template, state)
                doc.finishPage(page)
                doc.writeTo(out)
            } finally {
                doc.close()
            }
        }
    }

    private fun fileName(title: String) =
        title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifBlank { "plan" } + ".pdf"

    /** Writes the PDF to app cache and returns a shareable content:// URI. */
    suspend fun exportForShare(title: String, template: Template, state: PlanState): Uri {
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val file = File(dir, fileName(title))
        withContext(Dispatchers.IO) { file.outputStream().use { write(template, state, it) } }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareIntent(uri: Uri, title: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share plan",
        )

    /** Saves into Downloads/DailyPlanner. Returns a human-readable location. */
    suspend fun saveToDownloads(title: String, template: Template, state: PlanState): String = withContext(Dispatchers.IO) {
        val name = fileName(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DailyPlanner")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
            try {
                resolver.openOutputStream(uri)!!.use { write(template, state, it) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            "Downloads/DailyPlanner/$name"
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DailyPlanner").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { write(template, state, it) }
            file.absolutePath
        }
    }
}
