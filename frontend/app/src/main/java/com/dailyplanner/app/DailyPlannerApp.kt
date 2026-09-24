package com.dailyplanner.app

import android.app.Application
import com.dailyplanner.app.data.PlannerApi
import com.dailyplanner.app.data.PlannerRepository
import com.dailyplanner.app.reminders.ReminderScheduler
import com.dailyplanner.app.render.FontRegistry
import com.dailyplanner.app.render.ImageCache
import com.dailyplanner.app.render.PdfExporter
import com.dailyplanner.app.render.TemplateRenderer
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

class DailyPlannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduler.createChannel()
    }
}

/** Manual DI: one instance of each service for the whole app. */
class AppContainer(app: Application) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .cache(Cache(File(app.cacheDir, "http"), 50L * 1024 * 1024))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Optional: only when the build sets -PplannerBackendUrl=... (sync of plans/reminders). */
    private val api: PlannerApi? = BuildConfig.BACKEND_URL.takeIf { it.isNotBlank() }?.let { url ->
        Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PlannerApi::class.java)
    }

    val repository = PlannerRepository(app, api, json)
    val fonts = FontRegistry(app.assets)
    val images = ImageCache(app.assets, http, BuildConfig.BACKEND_URL)
    val renderer = TemplateRenderer(fonts, images)
    val pdf = PdfExporter(app, renderer, images)
    val scheduler = ReminderScheduler(app)
}
