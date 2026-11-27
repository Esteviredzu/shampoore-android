package dev.shampoore.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

const val SHAMPOORE_ANDROID_REPO = "Esteviredzu/shampoore-android"

internal const val UPDATE_CHECK_TIMEOUT_MS = 15_000
private const val MAX_RELEASE_BYTES = 256 * 1024

sealed interface UpdateCheckResult {
    data class Available(val version: String, val downloadUrl: String) : UpdateCheckResult
    data class UpToDate(val version: String) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

internal fun isNewerVersion(latest: String, current: String): Boolean {
    val latestParts = versionParts(latest)
    val currentParts = versionParts(current)
    if (latestParts.isEmpty() || currentParts.isEmpty()) return false
    for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
        val latestPart = latestParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (latestPart != currentPart) return latestPart > currentPart
    }
    return false
}

private fun versionParts(raw: String): List<Int> =
    raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '+')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

object UpdateChecker {
    fun latestReleaseUrl(repo: String = SHAMPOORE_ANDROID_REPO): String =
        "https://github.com/$repo/releases/latest"

    suspend fun check(
        currentVersion: String,
        repo: String = SHAMPOORE_ANDROID_REPO,
        timeoutMs: Int = UPDATE_CHECK_TIMEOUT_MS
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = fetchLatestRelease(repo, timeoutMs)
            if (isNewerVersion(release.version, currentVersion)) {
                UpdateCheckResult.Available(release.version, release.downloadUrl)
            } else {
                UpdateCheckResult.UpToDate(release.version)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: "Update check failed")
        }
    }

    private data class Release(val version: String, val downloadUrl: String)

    private fun fetchLatestRelease(repo: String, timeoutMs: Int): Release {
        val url = URI("https://api.github.com/repos/$repo/releases/latest")
        val connection = url.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", "shampoore-android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val code = connection.responseCode
            when {
                code == 404 -> throw IllegalStateException("No releases published yet")
                code == 403 -> throw IllegalStateException("GitHub rate limit reached, try again later")
                code !in 200..299 -> throw IllegalStateException("HTTP $code while checking for updates")
            }

            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RELEASE_BYTES) break
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }

            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) {
                throw IllegalStateException("Latest release has no version tag")
            }
            val pageUrl = json.optString("html_url").trim()
                .takeIf { it.startsWith("https://") }
                ?: latestReleaseUrl(repo)
            return Release(version = tag, downloadUrl = pageUrl)
        } finally {
            connection.disconnect()
        }
    }
}
