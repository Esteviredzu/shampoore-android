package dev.shampoore.app.data

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.Locale

internal const val SUBSCRIPTION_DOWNLOAD_TIMEOUT_MS = 15_000
internal const val MAX_SUBSCRIPTION_BYTES = 5L * 1024L * 1024L

internal data class SubscriptionDownloadResult(
    val body: String? = null,
    val finalUrl: String = "",
    
    val serverTitle: String? = null,
    val error: String? = null
) {
    val success: Boolean get() = error == null && body != null
}







internal fun decodeProfileTitle(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val prefix = "base64:"
    if (!value.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
        return value
    }
    val payload = value.substring(prefix.length).trim()
    if (payload.isEmpty()) return null
    val decoded = decodeBase64Lenient(payload) ?: return null
    return String(decoded, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
}

private fun decodeBase64Lenient(payload: String): ByteArray? {
    for (decoder in listOf(Base64.getDecoder(), Base64.getMimeDecoder(), Base64.getUrlDecoder())) {
        runCatching { decoder.decode(payload) }.getOrNull()?.let { return it }
    }
    return null
}

internal fun isSupportedSubscriptionUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    return !uri.host.isNullOrBlank()
}

internal fun subscriptionHttpErrorMessage(code: Int, host: String?): String = when (code) {
    403, 404, 410 -> "Subscription is invalid or expired (HTTP $code)"
    else -> "HTTP $code from ${host?.takeIf { it.isNotBlank() } ?: "server"}"
}





internal object SubscriptionDownloader {
    private const val MAX_REDIRECTS = 5

    fun download(
        rawUrl: String,
        maxBytes: Long = MAX_SUBSCRIPTION_BYTES,
        timeoutMs: Int = SUBSCRIPTION_DOWNLOAD_TIMEOUT_MS
    ): SubscriptionDownloadResult {
        var current = runCatching { URI(rawUrl.trim()) }.getOrNull()
            ?: return SubscriptionDownloadResult(error = "Invalid URL")

        for (attempt in 0..MAX_REDIRECTS) {
            val scheme = current.scheme?.lowercase(Locale.ROOT)
            if ((scheme != "http" && scheme != "https") || current.host.isNullOrBlank()) {
                return SubscriptionDownloadResult(error = "Only http:// and https:// links are supported")
            }

            val connection = runCatching { current.toURL().openConnection() as HttpURLConnection }
                .getOrElse { return SubscriptionDownloadResult(error = it.message ?: "Cannot open $current") }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.setRequestProperty("User-Agent", "shampoore-android")
                connection.setRequestProperty("Accept", "application/json, text/plain, */*")

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: return SubscriptionDownloadResult(
                            error = "HTTP $code without a redirect location"
                        )
                    current = runCatching { current.resolve(URI(location)) }
                        .getOrElse {
                            return SubscriptionDownloadResult(error = "Invalid redirect from $current")
                        }
                    continue
                }
                if (code !in 200..299) {
                    return SubscriptionDownloadResult(
                        error = subscriptionHttpErrorMessage(code, current.host)
                    )
                }

                val serverTitle = decodeProfileTitle(connection.getHeaderField("Profile-Title"))

                val declared = connection.contentLengthLong
                if (declared > maxBytes) {
                    return SubscriptionDownloadResult(error = tooLargeMessage(maxBytes))
                }

                val bytes = try {
                    connection.inputStream.use { stream -> readLimited(stream, maxBytes) }
                } catch (e: IOException) {
                    return SubscriptionDownloadResult(error = e.message ?: "Download failed")
                }
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                return SubscriptionDownloadResult(
                    body = text,
                    finalUrl = current.toString(),
                    serverTitle = serverTitle
                )
            } catch (e: Exception) {
                return SubscriptionDownloadResult(error = e.message ?: "Download failed")
            } finally {
                connection.disconnect()
            }
        }
        return SubscriptionDownloadResult(error = "Too many redirects")
    }

    private fun readLimited(stream: InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
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
            "The subscription is too large (max $megabytes MB)"
        } else {
            "The subscription is too large (max $maxBytes bytes)"
        }
    }
}
