package dev.shampoore.app.data

import java.util.Locale


internal val RULE_EXTENSIONS = listOf(".txt", ".json")

private const val FORBIDDEN_FILE_CHARS = "\\/:*?\"<>|"





internal fun sanitizeRuleBaseName(raw: String?): String {
    val candidate = raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()

    val cleaned = buildString(candidate.length) {
        candidate.forEach { ch ->
            when {
                ch.isISOControl() -> append('_')
                ch in FORBIDDEN_FILE_CHARS -> append('_')
                else -> append(ch)
            }
        }
    }.trim().trim('.', ' ')

    return cleaned.ifEmpty { "imported_${System.currentTimeMillis()}" }
}


internal fun splitRuleFileName(displayName: String?): Pair<String, String?> {
    val raw = displayName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    val dot = raw.lastIndexOf('.')
    if (dot <= 0) return raw to null
    return raw.substring(0, dot) to raw.substring(dot).lowercase(Locale.ROOT)
}


internal fun ruleBaseName(displayName: String?): String {
    val (base, _) = splitRuleFileName(displayName)
    return sanitizeRuleBaseName(base)
}





internal fun detectRuleExtension(displayName: String?, content: String): String {
    val (_, ext) = splitRuleFileName(displayName)
    if (ext != null && ext in RULE_EXTENSIONS) return ext
    val first = content.firstOrNull { !it.isWhitespace() }
    return if (first == '{' || first == '[') ".json" else ".txt"
}
