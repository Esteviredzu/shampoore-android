package dev.shampoore.app.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal const val MAX_RULE_DOWNLOAD_BYTES = 20L * 1024L * 1024L
internal const val RULE_DOWNLOAD_TIMEOUT_MS = 15_000

internal data class DownloadResult(
    val body: String? = null,
    val finalUrl: String = "",
    val contentType: String? = null,
    val error: String? = null
) {
    val success: Boolean get() = error == null && body != null
}





internal object RuleDownloader {
    private const val MAX_REDIRECTS = 5

    fun download(
        rawUrl: String,
        maxBytes: Long = MAX_RULE_DOWNLOAD_BYTES,
        connectTimeoutMs: Int = RULE_DOWNLOAD_TIMEOUT_MS,
        readTimeoutMs: Int = RULE_DOWNLOAD_TIMEOUT_MS
    ): DownloadResult {
        var current = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: return DownloadResult(error = "Invalid URL")

        for (attempt in 0..MAX_REDIRECTS) {
            val scheme = current.scheme?.lowercase(Locale.ROOT)
            if ((scheme != "http" && scheme != "https") || current.host.isNullOrBlank()) {
                return DownloadResult(error = "Only http:// and https:// links are supported")
            }

            val connection = runCatching { current.toURL().openConnection() as HttpURLConnection }
                .getOrElse { return DownloadResult(error = it.message ?: "Cannot open $current") }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("User-Agent", "shampoore-android")
                connection.setRequestProperty("Accept", "text/plain, application/json, */*")

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return DownloadResult(error = "HTTP $code without a redirect location")
                    current = runCatching { current.resolve(URI(location)) }
                        .getOrElse { return DownloadResult(error = "Invalid redirect from $current") }
                    continue
                }
                if (code !in 200..299) {
                    return DownloadResult(error = "HTTP $code from ${current.host}")
                }

                val declared = connection.contentLengthLong
                if (declared > maxBytes) {
                    return DownloadResult(error = tooLargeMessage(maxBytes))
                }

                val bytes = try {
                    connection.inputStream.use { stream -> readLimited(stream, maxBytes) }
                } catch (e: IOException) {
                    return DownloadResult(error = e.message ?: "Download failed")
                }
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                return DownloadResult(
                    body = text,
                    finalUrl = current.toString(),
                    contentType = connection.contentType
                )
            } catch (e: Exception) {
                return DownloadResult(error = e.message ?: "Download failed")
            } finally {
                connection.disconnect()
            }
        }
        return DownloadResult(error = "Too many redirects")
    }

    private fun readLimited(stream: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > maxBytes) {
                throw IOException(tooLargeMessage(maxBytes))
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun tooLargeMessage(maxBytes: Long): String {
        val megabytes = maxBytes / (1024L * 1024L)
        return if (megabytes >= 1L) {
            "The file is too large (max $megabytes MB)"
        } else {
            "The file is too large (max $maxBytes bytes)"
        }
    }
}
