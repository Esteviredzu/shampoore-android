package dev.shampoore.app.data








data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    
    val updatedAt: Long = 0L,
    val lastError: String? = null,
    val isOfflineBundle: Boolean = false
) {
    val isNetworkSubscription: Boolean get() = !isOfflineBundle
}








data class SubscriptionRoute(
    val subscriptionId: String,
    val name: String,
    val summary: String,
    val source: String
)

data class SubscriptionRecord(
    val subscription: Subscription,
    val routes: List<SubscriptionRoute>
) {
    val routeCount: Int get() = routes.size
}

data class SubscriptionActionResult(
    val subscription: Subscription? = null,
    val updated: Int = 0,
    val error: String? = null
) {
    val success: Boolean get() = error == null
}


internal const val SUBSCRIPTION_STALE_MS = 60L * 60L * 1000L


internal fun SubscriptionRecord.toVpnConfigs(): List<VpnConfig> = routes.map { route ->
    VpnConfig(
        name = route.name,
        source = route.source,
        subscriptionId = subscription.id,
        subscriptionName = subscription.name,
        subtitleOverride = route.summary
    )
}
