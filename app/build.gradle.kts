import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// =========================================================================
// shampoore AAR: auto-download from GitHub Releases
//
// Pin a version in gradle.properties:
//   shampoore.version=latest     (default)
//   shampoore.version=1.0        (tag v1.0)
//
// Local AAR rules:
//   - If app/libs/shampoore.aar exists, it is used as-is.
//   - If it does not exist, it is downloaded from GitHub Releases.
//   - To force a fresh download even when a local copy exists:
//       ./gradlew downloadShampooreAar --rerun-tasks
//     or add -Pshampoore.forceDownload=true
// =========================================================================

val shampooreVersion: String =
    (project.findProperty("shampoore.version") as String?)?.trim().takeUnless { it.isNullOrEmpty() }
        ?: "latest"

val forceShampooreDownload: Boolean =
    (project.findProperty("shampoore.forceDownload") as String?)?.toBoolean() ?: false

val shampooreAarDir: File = file("libs")
val shampooreAar: File = File(shampooreAarDir, "shampoore.aar")
val shampooreAarVersionFile: File = File(shampooreAarDir, "shampoore.aar.version")
val shampooreAarTmp: File = File(shampooreAarDir, "shampoore.aar.tmp")

val shampooreAarUrl: String =
    if (shampooreVersion == "latest") {
        "https://github.com/Esteviredzu/shampoore/releases/latest/download/shampoore.aar"
    } else {
        "https://github.com/Esteviredzu/shampoore/releases/download/v$shampooreVersion/shampoore.aar"
    }

fun httpDownload(url: String, dest: File) {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.instanceFollowRedirects = true
    conn.connectTimeout = 30_000
    conn.readTimeout = 120_000
    conn.setRequestProperty("User-Agent", "shampoore-gradle")
    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IOException("HTTP $code from $url")
        }
        val total = conn.contentLengthLong
        if (total > 0) logger.lifecycle("shampoore.aar: ${total / 1024} KiB to download")
        conn.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    } finally {
        conn.disconnect()
    }
}

val downloadShampooreAar: TaskProvider<Task> = tasks.register("downloadShampooreAar") {
    description = "Downloads shampoore.aar from GitHub Releases"
    group = "shampoore"

    inputs.property("version", shampooreVersion)
    inputs.property("forceDownload", forceShampooreDownload)
    outputs.file(shampooreAar)

    doLast {
        shampooreAarDir.mkdirs()

        val cachedVersion =
            if (shampooreAarVersionFile.exists())
                shampooreAarVersionFile.readText().trim()
            else ""

        val force = forceShampooreDownload || gradle.startParameter.isRerunTasks
        if (shampooreAar.exists() && !force) {
            if (shampooreVersion == "latest") {
                logger.lifecycle(
                    "Using cached shampoore.aar (${shampooreAar.length() / 1024} KiB). " +
                            "Run ./gradlew downloadShampooreAar --rerun-tasks to refresh."
                )
                return@doLast
            }
            if (cachedVersion == shampooreVersion) {
                logger.lifecycle("shampoore.aar for v$shampooreVersion already cached, skipping")
                return@doLast
            }
        }

        val hadLocalCopy = shampooreAar.exists()
        try {
            logger.lifecycle("Downloading shampoore.aar from $shampooreAarUrl")
            if (shampooreAarTmp.exists()) shampooreAarTmp.delete()
            httpDownload(shampooreAarUrl, shampooreAarTmp)
            if (shampooreAar.exists()) shampooreAar.delete()
            if (!shampooreAarTmp.renameTo(shampooreAar)) {
                shampooreAarTmp.copyTo(shampooreAar, overwrite = true)
                shampooreAarTmp.delete()
            }
            shampooreAarVersionFile.writeText(shampooreVersion)
            logger.lifecycle("Downloaded shampoore.aar (${shampooreAar.length() / 1024} KiB)")
        } catch (e: Exception) {
            if (hadLocalCopy && shampooreAar.exists()) {
                logger.warn(
                    "Download of shampoore.aar failed (${e.message}); " +
                            "using cached ${shampooreAar.length() / 1024} KiB copy"
                )
                return@doLast
            }
            throw GradleException(
                "Failed to download shampoore.aar from $shampooreAarUrl: ${e.message}\n" +
                        "Options:\n" +
                        "  - check network access to github.com\n" +
                        "  - set shampoore.version in gradle.properties (e.g. 1.0)\n" +
                        "  - or drop shampoore.aar manually into app/libs/",
                e
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadShampooreAar) }
// =========================================================================

android {
    namespace = "dev.shampoore.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.shampoore.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // SettingsScreen reads BuildConfig.VERSION_NAME / VERSION_CODE
        buildConfig = true
    }
}

dependencies {
    implementation(files(shampooreAar))

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.2")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}