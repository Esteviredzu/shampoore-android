package dev.shampoore.app.data

import java.io.File

internal const val SUBSCRIPTIONS_FILE = "subscriptions.json"








internal class SubscriptionStore(private val file: File) {
    private val lock = Any()

    fun list(): List<SubscriptionRecord> = synchronized(lock) { readLocked() }

    
    fun add(record: SubscriptionRecord): List<SubscriptionRecord> = synchronized(lock) {
        val current = readLocked().filterNot { it.subscription.id == record.subscription.id }
        val updated = current + record
        writeLocked(updated)
        updated
    }

    




    fun replaceRoutes(
        id: String,
        routes: List<SubscriptionRoute>,
        updatedAt: Long,
        name: String? = null
    ): List<SubscriptionRecord> = synchronized(lock) {
        val newName = name?.trim()?.takeIf { it.isNotEmpty() }
        val updated = readLocked().map { record ->
            if (record.subscription.id != id) {
                record
            } else {
                SubscriptionRecord(
                    subscription = record.subscription.copy(
                        name = newName ?: record.subscription.name,
                        updatedAt = updatedAt,
                        lastError = null
                    ),
                    routes = routes
                )
            }
        }
        writeLocked(updated)
        updated
    }

    



    fun markError(id: String, message: String): List<SubscriptionRecord> = synchronized(lock) {
        val updated = readLocked().map { record ->
            if (record.subscription.id != id) {
                record
            } else {
                SubscriptionRecord(
                    subscription = record.subscription.copy(lastError = message),
                    routes = record.routes
                )
            }
        }
        writeLocked(updated)
        updated
    }

    
    fun delete(id: String): List<SubscriptionRecord> = synchronized(lock) {
        val updated = readLocked().filterNot { it.subscription.id == id }
        writeLocked(updated)
        updated
    }

    private fun readLocked(): List<SubscriptionRecord> {
        if (!file.exists()) return emptyList()
        return runCatching { decodeSubscriptions(file.readText()) }.getOrDefault(emptyList())
    }

internal fun encodeSubscriptions(records: List<SubscriptionRecord>): String = buildString {
    if (records.isEmpty()) {
        append("[]\n")
        return@buildString
    }
    append("[\n")
    records.forEachIndexed { index, record ->
        if (index > 0) append(",\n")
        val subscription = record.subscription
        append("  {\n")
        append("    \"id\": ").append(jsonQuote(subscription.id)).append(",\n")
        append("    \"name\": ").append(jsonQuote(subscription.name)).append(",\n")
        append("    \"url\": ").append(jsonQuote(subscription.url)).append(",\n")
        append("    \"updated_at\": ").append(subscription.updatedAt).append(",\n")
        append("    \"is_offline_bundle\": ")
            .append(if (subscription.isOfflineBundle) "true" else "false")
            .append(",\n")
        append("    \"last_error\": ")
            .append(subscription.lastError?.let(::jsonQuote) ?: "null")
            .append(",\n")
        append("    \"routes\": [")
        if (record.routes.isEmpty()) {
            append("]\n")
        } else {
            append("\n")
            record.routes.forEachIndexed { routeIndex, route ->
                if (routeIndex > 0) append(",\n")
                append("      {\"name\": ").append(jsonQuote(route.name))
                append(", \"summary\": ").append(jsonQuote(route.summary))
                append(", \"source\": ").append(jsonQuote(route.source))
                append("}")
            }
            append("\n    ]\n")
        }
        append("  }")
    }
    append("\n]\n")
}


    private fun writeLocked(records: List<SubscriptionRecord>) {
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeText(encodeSubscriptions(records), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

internal fun decodeSubscriptions(raw: String): List<SubscriptionRecord> {
    val root = parseJson(raw.removePrefix("\uFEFF").trim()) as? JsonValue.Arr ?: return emptyList()
    val out = mutableListOf<SubscriptionRecord>()
    for (element in root.values) {
        val obj = element as? JsonValue.Obj ?: continue
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: continue
        val name = obj.string("name").orEmpty()
        val url = obj.string("url").orEmpty()
        val updatedAt = obj.long("updated_at") ?: 0L
        val isOfflineBundle = obj.boolean("is_offline_bundle") ?: false
        val lastError = obj.string("last_error")?.takeIf { it.isNotBlank() }
        val routes = obj.array("routes")?.values?.mapNotNull { routeElement ->
            val routeObj = routeElement as? JsonValue.Obj ?: return@mapNotNull null
            val source = routeObj.string("source")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SubscriptionRoute(
                subscriptionId = id,
                name = routeObj.string("name").orEmpty().ifBlank { "Route" },
                summary = routeObj.string("summary").orEmpty().ifBlank { "Unknown" },
                source = source
            )
        }.orEmpty()
        out.add(
            SubscriptionRecord(
                subscription = Subscription(
                    id = id,
                    name = name,
                    url = url,
                    updatedAt = updatedAt,
                    lastError = lastError,
                    isOfflineBundle = isOfflineBundle
                ),
                routes = routes
            )
        )
    }
    return out
}

