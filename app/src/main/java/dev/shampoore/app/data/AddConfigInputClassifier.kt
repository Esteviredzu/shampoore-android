package dev.shampoore.app.data

internal sealed interface AddConfigInput {
    data class SubscriptionUrl(val url: String) : AddConfigInput
    data class OfflineBundle(val raw: String) : AddConfigInput
    data class SingleConfig(val source: String) : AddConfigInput
    data class Invalid(val message: String) : AddConfigInput
}

internal object AddConfigInputClassifier {
    private const val EMPTY_MESSAGE =
        "Paste a subscription link, a JSON bundle or a shampoore:// link"
    private const val UNRECOGNIZED_MESSAGE =
        "Unrecognized input: expected a subscription link, JSON or shampoore:// link"

    fun classify(raw: String): AddConfigInput {
        val trimmed = raw.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return AddConfigInput.Invalid(EMPTY_MESSAGE)

        if (isSupportedSubscriptionUrl(trimmed)) {
            return AddConfigInput.SubscriptionUrl(trimmed)
        }

        if (trimmed.startsWith("{")) {
            val root = parseJson(trimmed)
                ?: return AddConfigInput.Invalid("Invalid JSON")
            val obj = root as? JsonValue.Obj
                ?: return AddConfigInput.Invalid("The JSON config must be an object")
            val links = obj.array("links")
            if (links != null) {
                val hasLinks = links.values.any {
                    it is JsonValue.Str && it.value.trim().isNotEmpty()
                }
                return if (hasLinks) {
                    AddConfigInput.OfflineBundle(trimmed)
                } else {
                    AddConfigInput.Invalid("The bundle contains no links")
                }
            }
            return AddConfigInput.SingleConfig(trimmed)
        }

        val links = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(SHAMPOORE_LINK_PREFIX) }
            .toList()
        return when {
            links.isEmpty() -> AddConfigInput.Invalid(UNRECOGNIZED_MESSAGE)
            links.size == 1 -> AddConfigInput.SingleConfig(links.single())
            else -> AddConfigInput.OfflineBundle(links.joinToString("\n"))
        }
    }
}