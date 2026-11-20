package dev.shampoore.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID







internal class SubscriptionRepository(
    private val store: SubscriptionStore,
    private val downloader: (String) -> SubscriptionDownloadResult = { url ->
        SubscriptionDownloader.download(url)
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        SubscriptionStore(File(context.filesDir, SUBSCRIPTIONS_FILE))
    )

    private val _subscriptions = MutableStateFlow<List<SubscriptionRecord>>(emptyList())
    val subscriptions: StateFlow<List<SubscriptionRecord>> = _subscriptions.asStateFlow()

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    
    suspend fun reload() = withContext(Dispatchers.IO) {
        _subscriptions.value = store.list()
    }

    suspend fun importSubscription(url: String): SubscriptionActionResult =
        withContext(Dispatchers.IO) {
            val normalized = url.trim()
            if (!isSupportedSubscriptionUrl(normalized)) {
                return@withContext SubscriptionActionResult(
                    error = "Enter a valid http:// or https:// link"
                )
            }
            if (store.list().any { it.subscription.url == normalized }) {
                return@withContext SubscriptionActionResult(
                    error = "This subscription is already added"
                )
            }

            val download = downloader(normalized)
            if (!download.success) {
                return@withContext SubscriptionActionResult(error = download.error ?: "Download failed")
            }
            val subscription = Subscription(
                id = UUID.randomUUID().toString(),
                name = resolveSubscriptionName(
                    download.serverTitle,
                    download.finalUrl.ifBlank { normalized }
                ),
                url = normalized,
                updatedAt = now(),
                lastError = null
            )
            val parsed = SubscriptionPayloadParser.parse(download.body.orEmpty(), subscription.id)
            if (!parsed.success) {
                return@withContext SubscriptionActionResult(error = parsed.error)
            }

            _subscriptions.value = store.add(SubscriptionRecord(subscription, parsed.routes))
            SubscriptionActionResult(subscription = subscription, updated = parsed.routes.size)
        }

    



    suspend fun importOfflineBundle(raw: String): SubscriptionActionResult =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val parsed = SubscriptionPayloadParser.parseOfflineBundle(raw, id)
            if (!parsed.success) {
                return@withContext SubscriptionActionResult(
                    error = parsed.error ?: "Invalid offline bundle"
                )
            }
            val subscription = Subscription(
                id = id,
                name = parsed.name,
                url = "",
                updatedAt = now(),
                lastError = null,
                isOfflineBundle = true
            )
            _subscriptions.value = store.add(SubscriptionRecord(subscription, parsed.routes))
            SubscriptionActionResult(subscription = subscription, updated = parsed.routes.size)
        }

    suspend fun refreshSubscription(id: String): SubscriptionActionResult =
        withContext(Dispatchers.IO) {
            val record = store.list().firstOrNull { it.subscription.id == id }
                ?: return@withContext SubscriptionActionResult(error = "Subscription not found")
            if (record.subscription.isOfflineBundle) {
                
                return@withContext SubscriptionActionResult(subscription = record.subscription)
            }
            if (id in _refreshing.value) return@withContext SubscriptionActionResult()
            withRefreshMarker(id) { refreshInternal(id) }
        }

    suspend fun refreshAll(): List<SubscriptionActionResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SubscriptionActionResult>()
        for (record in store.list()) {
            if (record.subscription.isOfflineBundle) continue
            val id = record.subscription.id
            if (id in _refreshing.value) continue
            results.add(withRefreshMarker(id) { refreshInternal(id) })
        }
        results
    }

    




    suspend fun refreshStale(
        maxAgeMs: Long = SUBSCRIPTION_STALE_MS
    ): List<SubscriptionActionResult> = withContext(Dispatchers.IO) {
        val reference = now()
        val results = mutableListOf<SubscriptionActionResult>()
        for (record in store.list()) {
            if (record.subscription.isOfflineBundle) continue
            val id = record.subscription.id
            val updatedAt = record.subscription.updatedAt
            val fresh = updatedAt > 0L && reference - updatedAt < maxAgeMs
            if (fresh || id in _refreshing.value) continue
            results.add(withRefreshMarker(id) { refreshInternal(id) })
        }
        results
    }

    suspend fun deleteSubscription(id: String): Boolean = withContext(Dispatchers.IO) {
        if (store.list().none { it.subscription.id == id }) return@withContext false
        _subscriptions.value = store.delete(id)
        true
    }

    private inline fun withRefreshMarker(
        id: String,
        block: () -> SubscriptionActionResult
    ): SubscriptionActionResult {
        _refreshing.update { it + id }
        return try {
            block()
        } finally {
            _refreshing.update { it - id }
        }
    }

    private fun refreshInternal(id: String): SubscriptionActionResult {
        val current = store.list().firstOrNull { it.subscription.id == id }
            ?: return SubscriptionActionResult(error = "Subscription not found")
        if (current.subscription.isOfflineBundle) {
            return SubscriptionActionResult(subscription = current.subscription)
        }

        val download = downloader(current.subscription.url)
        if (!download.success) {
            val message = download.error ?: "Download failed"
            
            _subscriptions.value = store.markError(id, message)
            return SubscriptionActionResult(subscription = current.subscription, error = message)
        }

        val parsed = SubscriptionPayloadParser.parse(download.body.orEmpty(), id)
        if (!parsed.success) {
            val message = parsed.error ?: "Invalid subscription response"
            _subscriptions.value = store.markError(id, message)
            return SubscriptionActionResult(subscription = current.subscription, error = message)
        }

        val updatedRecords = store.replaceRoutes(
            id = id,
            routes = parsed.routes,
            updatedAt = now(),
            name = resolveSubscriptionName(
                download.serverTitle,
                download.finalUrl.ifBlank { current.subscription.url }
            )
        )
        _subscriptions.value = updatedRecords
        val updatedSubscription = updatedRecords
            .firstOrNull { it.subscription.id == id }
            ?.subscription
            ?: current.subscription
        return SubscriptionActionResult(
            subscription = updatedSubscription,
            updated = parsed.routes.size
        )
    }

    private fun resolveSubscriptionName(serverTitle: String?, url: String): String {
        serverTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val host = runCatching { URI(url).host }.getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
        return host ?: "Subscription"
    }
}
