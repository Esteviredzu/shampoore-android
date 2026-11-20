package dev.shampoore.app.data

import java.io.File
import java.net.URI
import java.util.Locale





internal const val RULE_SOURCES_FILE = "rule_sources.json"

private const val GITHUB_HOST = "github.com"
private const val RAW_GITHUB_HOST = "raw.githubusercontent.com"










internal fun normalizeRuleUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return trimmed
    if (scheme != "http" && scheme != "https") return trimmed

    val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return trimmed
    if (host == RAW_GITHUB_HOST) return trimmed
    if (host != GITHUB_HOST) return trimmed

    val segments = uri.rawPath
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotEmpty() }
        ?: return trimmed
    if (segments.size < 5) return trimmed

    val marker = segments[2].lowercase(Locale.ROOT)
    if (marker != "blob" && marker != "raw") return trimmed

    val user = segments[0]
    val repo = segments[1]
    val rest = segments.drop(3).joinToString("/")
    return "https://$RAW_GITHUB_HOST/$user/$repo/$rest"
}

internal fun isSupportedRuleUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    return !uri.host.isNullOrBlank()
}


internal fun ruleFileNameFromUrl(raw: String): String? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    return uri.path
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
}


internal fun ruleSourceHost(raw: String): String? =
    runCatching { URI(raw.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }


internal fun remoteRuleContentError(content: String, contentType: String? = null): String? {
    if (content.isBlank()) return "The downloaded file is empty"
    if (contentType?.trim()?.lowercase(Locale.ROOT)?.startsWith("text/html") == true) {
        return "The link points to an HTML page, not a rule file"
    }
    val head = content.trimStart().take(64).lowercase(Locale.ROOT)
    if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
        return "The link points to an HTML page, not a rule file"
    }
    return null
}







internal class RuleSourceStore(private val file: File) {

    @Synchronized
    fun snapshot(): Map<String, String> = readAll()

    @Synchronized
    fun get(name: String): String? = readAll()[name]

    @Synchronized
    fun put(name: String, url: String) {
        val current = readAll()
        if (current[name] == url) return
        current[name] = url
        writeAll(current)
    }

    @Synchronized
    fun remove(name: String) {
        val current = readAll()
        if (current.remove(name) == null) return
        writeAll(current)
    }

    private fun readAll(): MutableMap<String, String> {
        if (!file.exists()) return LinkedHashMap()
        return runCatching { decodeRuleSourceMap(file.readText(Charsets.UTF_8)).toMutableMap() }
            .getOrElse { LinkedHashMap() }
    }

    private fun writeAll(sources: Map<String, String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(encodeRuleSourceMap(sources), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

internal fun encodeRuleSourceMap(sources: Map<String, String>): String {
    if (sources.isEmpty()) return "{}\n"
    return buildString {
        append("{\n")
        var first = true
        for ((name, url) in sources) {
            if (!first) append(",\n")
            first = false
            append("  ").append(quoteJson(name)).append(": ").append(quoteJson(url))
        }
        append("\n}\n")
    }
}

internal fun decodeRuleSourceMap(raw: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    var index = 0

    fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) index++
    }

    fun readString(): String? {
        skipWhitespace()
        if (index >= raw.length || raw[index] != '"') return null
        index++
        val builder = StringBuilder()
        while (index < raw.length) {
            val ch = raw[index++]
            when (ch) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (index >= raw.length) return null
                    when (val escaped = raw[index++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > raw.length) return null
                            val code = raw.substring(index, index + 4).toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                }
                else -> builder.append(ch)
            }
        }
        return null
    }

    skipWhitespace()
    if (index >= raw.length || raw[index] != '{') return emptyMap()
    index++
    skipWhitespace()
    if (index < raw.length && raw[index] == '}') return emptyMap()

    while (index < raw.length) {
        skipWhitespace()
        val name = readString() ?: return emptyMap()
        skipWhitespace()
        if (index >= raw.length || raw[index] != ':') return emptyMap()
        index++
        val url = readString() ?: return emptyMap()
        out[name] = url
        skipWhitespace()
        if (index >= raw.length) return emptyMap()
        when (raw[index]) {
            ',' -> index++
            '}' -> return out
            else -> return emptyMap()
        }
    }
    return emptyMap()
}

private fun quoteJson(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') {
                append("\\u").append(String.format(Locale.ROOT, "%04x", ch.code))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}
