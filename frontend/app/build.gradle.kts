import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dailyplanner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dailyplanner.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Optional sync backend. Empty = fully standalone (templates bundled, data on the phone).
        val backendUrl = (project.findProperty("plannerBackendUrl") as String?) ?: ""
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }

    // Release signing from frontend/keystore.properties (kept out of git).
    val signingProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Installs next to any other "com.dailyplanner.app" already on the device.
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += "ttf" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/bundle"))
}

// Bundle the Figma-generated templates + images from ../backend into the APK, so the app
// works with no server. Re-run `npm run sync:figma` in backend/, then rebuild the app.
val bundleTemplates by tasks.registering(Copy::class) {
    val backend = rootProject.file("../backend")
    into(layout.buildDirectory.dir("generated/bundle/bundle"))
    from(File(backend, "data/templates")) { into("templates"); include("*.json") }
    from(File(backend, "data/figma-sources.json")) { into("templates") }
    from(File(backend, "public/assets")) { into("assets") }
}
tasks.named("preBuild") { dependsOn(bundleTemplates) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
