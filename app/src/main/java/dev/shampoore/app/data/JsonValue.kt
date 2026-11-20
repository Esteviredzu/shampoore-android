package dev.shampoore.app.data









internal sealed interface JsonValue {
    data class Obj(
        val values: Map<String, JsonValue>,
        val start: Int,
        val end: Int
    ) : JsonValue

    data class Arr(
        val values: List<JsonValue>,
        val start: Int,
        val end: Int
    ) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Num(val raw: String) : JsonValue {
        val value: Double get() = raw.toDoubleOrNull() ?: 0.0
    }

    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal fun JsonValue.Obj.string(key: String): String? =
    (values[key] as? JsonValue.Str)?.value

internal fun JsonValue.Obj.obj(key: String): JsonValue.Obj? =
    values[key] as? JsonValue.Obj

internal fun JsonValue.Obj.array(key: String): JsonValue.Arr? =
    values[key] as? JsonValue.Arr

internal fun JsonValue.Obj.long(key: String): Long? {
    val number = values[key] as? JsonValue.Num ?: return null
    return number.raw.toLongOrNull() ?: number.value.toLong()
}

internal fun JsonValue.Obj.boolean(key: String): Boolean? =
    (values[key] as? JsonValue.Bool)?.value

internal fun parseJson(raw: String): JsonValue? = JsonReader(raw).readDocument()

internal fun jsonQuote(value: String): String = buildString(value.length + 2) {
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
                append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", ch.code))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

private class JsonReader(private val text: String) {
    private var pos = 0
    private var depth = 0

    fun readDocument(): JsonValue? {
        if (text.startsWith('\uFEFF')) pos = 1
        val value = readValue() ?: return null
        skipWhitespace()
        return value.takeIf { pos == text.length }
    }

    private fun readValue(): JsonValue? {
        skipWhitespace()
        if (pos >= text.length) return null
        return when (text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()?.let { JsonValue.Str(it) }
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            '-', in '0'..'9' -> readNumber()
            else -> null
        }
    }

    private fun readObject(): JsonValue.Obj? {
        if (depth >= MAX_DEPTH) return null
        val start = pos
        pos++
        depth++
        val values = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == '}') {
            pos++
            depth--
            return JsonValue.Obj(values, start, pos)
        }
        while (true) {
            skipWhitespace()
            val key = readString() ?: return null
            skipWhitespace()
            if (pos >= text.length || text[pos] != ':') return null
            pos++
            val value = readValue() ?: return null
            values[key] = value
            skipWhitespace()
            if (pos >= text.length) return null
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (pos < text.length && text[pos] == '}') return null
                }
                '}' -> {
                    pos++
                    depth--
                    return JsonValue.Obj(values, start, pos)
                }
                else -> return null
            }
        }
    }


    private fun readArray(): JsonValue.Arr? {
        if (depth >= MAX_DEPTH) return null
        val start = pos
        pos++
        depth++
        val values = mutableListOf<JsonValue>()
        skipWhitespace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            depth--
            return JsonValue.Arr(values, start, pos)
        }
        while (true) {
            val value = readValue() ?: return null
            values.add(value)
            skipWhitespace()
            if (pos >= text.length) return null
            when (text[pos]) {
                ',' -> {
                    pos++
                    skipWhitespace()
                    if (pos < text.length && text[pos] == ']') return null
                }
                ']' -> {
                    pos++
                    depth--
                    return JsonValue.Arr(values, start, pos)
                }
                else -> return null
            }
        }
    }

    private fun readString(): String? {
        if (pos >= text.length || text[pos] != '"') return null
        pos++
        val builder = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos++]
            when (ch) {
                '"' -> return builder.toString()
                '\\' -> {
                    if (pos >= text.length) return null
                    when (val escaped = text[pos++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) return null
                            val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                            builder.append(code.toChar())
                            pos += 4
                        }
                        else -> return null
                    }
                }
                else -> {
                    if (ch < ' ') return null
                    builder.append(ch)
                }
            }
        }
        return null
    }


    private fun readNumber(): JsonValue.Num? {
        val start = pos
        if (text[pos] == '-') pos++
        var digits = 0
        while (pos < text.length && text[pos].isDigit()) {
            pos++
            digits++
        }
        if (digits == 0) return null
        if (pos < text.length && text[pos] == '.') {
            pos++
            var fraction = 0
            while (pos < text.length && text[pos].isDigit()) {
                pos++
                fraction++
            }
            if (fraction == 0) return null
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            var exponent = 0
            while (pos < text.length && text[pos].isDigit()) {
                pos++
                exponent++
            }
            if (exponent == 0) return null
        }
        val raw = text.substring(start, pos)
        return if (raw.toDoubleOrNull() == null) null else JsonValue.Num(raw)
    }

    private fun <T : JsonValue> readLiteral(literal: String, value: T): T? {
        if (!text.startsWith(literal, pos)) return null
        pos += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private companion object {
        private const val MAX_DEPTH = 64
    }
}

