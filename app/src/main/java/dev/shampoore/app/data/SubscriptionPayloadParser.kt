package dev.shampoore.app.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class SubscriptionPayloadResult(
    val routes: List<SubscriptionRoute> = emptyList(),
    val error: String? = null
) {
    val success: Boolean get() = error == null
}

internal const val SHAMPOORE_LINK_PREFIX = "shampoore://"
internal const val DEFAULT_OFFLINE_BUNDLE_NAME = "Imported group"

internal data class OfflineBundleResult(
    val name: String = DEFAULT_OFFLINE_BUNDLE_NAME,
    val routes: List<SubscriptionRoute> = emptyList(),
    val error: String? = null
) {
    val success: Boolean get() = error == null
}







internal object SubscriptionPayloadParser {

    fun parse(raw: String, subscriptionId: String): SubscriptionPayloadResult {
        val trimmed = raw.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) {
            return SubscriptionPayloadResult(error = "The subscription response is empty")
        }
        val root = parseJson(trimmed)
            ?: return SubscriptionPayloadResult(error = "Invalid JSON in the subscription response")
        val array = root as? JsonValue.Arr
            ?: return SubscriptionPayloadResult(error = "The subscription response is not a JSON array of configs")

        val routes = buildList {
            array.values.forEachIndexed { index, element ->
                val obj = element as? JsonValue.Obj ?: return@forEachIndexed
                add(routeFromObject(obj, index, subscriptionId, trimmed))
            }
        }
        if (routes.isEmpty()) {
            return SubscriptionPayloadResult(error = "The subscription contains no configurations")
        }
        return SubscriptionPayloadResult(routes = routes)
    }

    




    fun parseOfflineBundle(raw: String, subscriptionId: String): OfflineBundleResult {
        val trimmed = raw.removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) {
            return OfflineBundleResult(error = "The bundle is empty")
        }
        if (trimmed.startsWith("{")) {
            val root = parseJson(trimmed)
                ?: return OfflineBundleResult(error = "Invalid JSON in the bundle")
            val obj = root as? JsonValue.Obj
                ?: return OfflineBundleResult(error = "The bundle is not a JSON object")
            val name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_OFFLINE_BUNDLE_NAME
            val links = obj.array("links")?.values
                ?.mapNotNull { (it as? JsonValue.Str)?.value?.trim()?.takeIf(String::isNotEmpty) }
                .orEmpty()
            if (links.isEmpty()) {
                return OfflineBundleResult(name = name, error = "The bundle contains no links")
            }
            return OfflineBundleResult(
                name = name,
                routes = routesFromLinks(links, subscriptionId)
            )
        }
        val links = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(SHAMPOORE_LINK_PREFIX) }
            .toList()
        if (links.isEmpty()) {
            return OfflineBundleResult(error = "The bundle contains no shampoore:// links")
        }
        return OfflineBundleResult(
            name = DEFAULT_OFFLINE_BUNDLE_NAME,
            routes = routesFromLinks(links, subscriptionId)
        )
    }

    private fun routesFromLinks(
        links: List<String>,
        subscriptionId: String
    ): List<SubscriptionRoute> = links.mapIndexed { index, link ->
        SubscriptionRoute(
            subscriptionId = subscriptionId,
            name = routeNameFromLink(link) ?: "Route ${index + 1}",
            summary = routeSummaryFromLink(link),
            source = link
        )
    }

    private fun routeFromObject(
        obj: JsonValue.Obj,
        index: Int,
        subscriptionId: String,
        text: String
    ): SubscriptionRoute {
        val type = obj.string("type")?.trim()?.takeIf { it.isNotEmpty() }
            ?: obj.string("transport_kind")?.trim()?.takeIf { it.isNotEmpty() }
        val name = obj.string("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: type
            ?: "Route ${index + 1}"
        return SubscriptionRoute(
            subscriptionId = subscriptionId,
            name = name,
            summary = summarize(obj, type),
            source = text.substring(obj.start, obj.end)
        )
    }

    private fun summarize(obj: JsonValue.Obj, type: String?): String {
        val parts = mutableListOf(typeLabel(type))
        val transport = obj.obj("transport")
        if (type == "multi") {
            val strategy = transport?.string("strategy")?.trim()?.takeIf { it.isNotEmpty() }
            val count = transport?.array("transports")?.values?.size ?: 0
            if (strategy != null) parts.add(strategy)
            if (count > 0) {
                parts.add(if (count == 1) "1 transport" else "$count transports")
            }
        } else if (transport != null) {
            val transportType = transport.string("type")?.trim()?.takeIf { it.isNotEmpty() }
                ?: transport.string("kind")?.trim()?.takeIf { it.isNotEmpty() }
            if (transportType != null && !transportType.equals(type, ignoreCase = true)) {
                parts.add(transportType)
            }
        }
        val encrypted = !obj.string("encryption_password").isNullOrBlank() ||
            !obj.obj("advanced")?.string("encryption_password").isNullOrBlank()
        if (encrypted) parts.add("Encrypted")
        return parts.filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Unknown" }
    }

    private fun typeLabel(type: String?): String = transportLabel(type)
}


internal fun routeTransportLabel(source: String): String =
    transportLabel(transportTypeFromSource(source))





internal fun routeNameFromLink(link: String): String? {
    val trimmed = link.trim()
    if (!trimmed.startsWith(SHAMPOORE_LINK_PREFIX)) return null
    val fragment = trimmed.substringAfter('#', "")
    if (fragment.isNotEmpty()) {
        decodeUrlComponent(fragment).takeIf { it.isNotEmpty() }?.let { return it }
    }
    val params = parseQueryParams(trimmed.substringAfter('?', "").substringBefore('#'))
    params["name"]?.takeIf { it.isNotEmpty() }?.let { return it }
    params["type"]?.takeIf { it.isNotEmpty() }?.let { return it }
    return runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotEmpty() }
}

private fun routeSummaryFromLink(link: String): String {
    val params = parseQueryParams(link.substringAfter('?', "").substringBefore('#'))
    val encrypted = !params["pw"].isNullOrEmpty() ||
        !params["encryption_password"].isNullOrEmpty()
    val label = routeTransportLabel(link)
    return if (encrypted) "$label · Encrypted" else label
}

private fun transportTypeFromSource(source: String): String? {
    val trimmed = source.trim()
    if (trimmed.startsWith("{")) {
        val obj = runCatching { parseJson(trimmed) as? JsonValue.Obj }.getOrNull() ?: return null
        val direct = obj.string("type") ?: obj.string("transport_kind")
        if (!direct.isNullOrBlank()) return direct.trim()
        val transport = obj.obj("transport") ?: return null
        return (transport.string("type") ?: transport.string("kind"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
    if (trimmed.startsWith(SHAMPOORE_LINK_PREFIX)) {
        val params = parseQueryParams(trimmed.substringAfter('?', "").substringBefore('#'))
        params["type"]?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    return null
}

internal fun transportLabel(type: String?): String {
    val raw = type?.trim()?.takeIf { it.isNotEmpty() } ?: return "Unknown"
    val normalized = raw.lowercase(Locale.ROOT).replace('-', '_')
    return when {
        normalized.contains("livekit") -> "LiveKit"
        normalized.contains("silo") -> "Silo"
        normalized == "raw_ws" || normalized == "rawws" ||
            normalized == "websocket" || normalized == "ws" -> "RawWS"
        normalized == "yandex" -> "Yandex"
        normalized == "yandex_telemost_raw" -> "Telemost Raw"
        normalized == "yandex_telemost_squares" -> "Telemost Squares"
        normalized == "multi" -> "Multi"
        else -> raw
    }
}

private fun parseQueryParams(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val out = mutableMapOf<String, String>()
    for (pair in raw.split("&")) {
        val idx = pair.indexOf('=')
        if (idx <= 0) continue
        val key = decodeUrlComponent(pair.substring(0, idx))
        if (key.isEmpty()) continue
        out[key] = decodeUrlComponent(pair.substring(idx + 1))
    }
    return out
}

private fun decodeUrlComponent(raw: String): String =
    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
