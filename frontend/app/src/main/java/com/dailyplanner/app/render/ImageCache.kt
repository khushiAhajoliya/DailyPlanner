package com.dailyplanner.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.dailyplanner.app.data.model.Element
import com.dailyplanner.app.data.model.Template
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Template image layers (backgrounds, stickers, icons), bundled in the APK. [version] bumps whenever a bitmap
 * arrives so Compose canvases redraw.
 */
class ImageCache(
    private val assets: android.content.res.AssetManager,
    private val client: OkHttpClient,
    private val baseUrl: String,
) {
    private val memory = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    var version by mutableIntStateOf(0)
        private set

    /** Returns the bitmap if ready; otherwise starts loading and returns null. */
    fun peek(src: String): Bitmap? {
        memory.get(src)?.let { return it }
        load(src)
        return null
    }

    private fun load(src: String): Deferred<Bitmap?> = synchronized(inFlight) {
        inFlight[src]?.let { return it }
        scope.async {
            // Bundled in the APK first (standalone build); backend only as a fallback.
            val bmp = runCatching { assets.open("bundle" + src).use { BitmapFactory.decodeStream(it) } }
                .onFailure { android.util.Log.w("ImageCache", "bundled image $src: $it") }
                .getOrNull()
                ?: runCatching {
                    check(baseUrl.isNotBlank())
                    val url = baseUrl.trimEnd('/') + src
                    client.newCall(Request.Builder().url(url).build()).execute().use { res ->
                        check(res.isSuccessful) { "HTTP ${res.code} for $url" }
                        BitmapFactory.decodeStream(res.body!!.byteStream())
                    }
                }.getOrNull()
            if (bmp != null) {
                memory.put(src, bmp)
                // Bump on the main thread between frames so every canvas redraws with it.
                main.post { version++ }
            } else {
                synchronized(inFlight) { inFlight.remove(src) }
            }
            bmp
        }.also { inFlight[src] = it }
    }

    /** Wait until every image of the template is loaded (used before PDF export). */
    suspend fun preload(template: Template) {
        template.elements.flatMap {
            when (it) {
                is Element.Image -> listOf(it.src)
                is Element.Checkbox -> listOf(it.check.src)
                else -> emptyList()
            }
        }.distinct().filter { it.isNotEmpty() && memory.get(it) == null }.map { load(it) }.awaitAll()
    }
}
