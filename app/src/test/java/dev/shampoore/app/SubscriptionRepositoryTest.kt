package dev.shampoore.app

import dev.shampoore.app.data.Subscription
import dev.shampoore.app.data.SubscriptionDownloadResult
import dev.shampoore.app.data.SubscriptionRecord
import dev.shampoore.app.data.SubscriptionRepository
import dev.shampoore.app.data.SubscriptionRoute
import dev.shampoore.app.data.SubscriptionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubscriptionRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val payload = """[{"name":"Amsterdam","type":"yandex","user_id":"u1",""" +
        """"exit_node_id":"n1","token_url":"https://example.com/token",""" +
        """"token_secret":"secret","encryption_password":"pw"}]"""
    private val updatedPayload = """[{"name":"Amsterdam","type":"yandex"},""" +
        """{"name":"Berlin","type":"yandex"}]"""
    private val offlineBundle = "{\"name\":\"Offline group\",\"links\":[" +
        "\"shampoore://yandex?type=yandex&pw=pw#Amsterdam\"," +
        "\"shampoore://silo?type=silo#Berlin\"]}"

    private class FakeDownloader(var result: SubscriptionDownloadResult) {
        var calls = 0
        fun download(url: String): SubscriptionDownloadResult {
            calls++
            return result
        }
    }

    private fun store() = SubscriptionStore(File(tempFolder.root, "subscriptions.json"))

    private fun record(id: String, updatedAt: Long) = SubscriptionRecord(
        subscription = Subscription(
            id = id,
            name = "Sub $id",
            url = "https://example.com/sub/$id",
            updatedAt = updatedAt
        ),
        routes = listOf(
            SubscriptionRoute(
                subscriptionId = id,
                name = "route-$id",
                summary = "Yandex",
                source = """{"name":"route-$id","type":"yandex"}"""
            )
        )
    )

    @Test
    fun importStoresSubscriptionAndRoutes() = runBlocking {
        val fake = FakeDownloader(
            SubscriptionDownloadResult(
                body = payload,
                finalUrl = "https://example.com/sub/token",
                serverTitle = "My sub"
            )
        )
        val repository = SubscriptionRepository(store(), fake::download) { 42L }

        val result = repository.importSubscription(
            " https://example.com/sub/token "
        )

        assertNull(result.error)
        assertEquals(1, result.updated)
        val rec = repository.subscriptions.value.single()
        assertEquals("My sub", rec.subscription.name)
        assertEquals("https://example.com/sub/token", rec.subscription.url)
        assertEquals(42L, rec.subscription.updatedAt)
        assertTrue(rec.routes.single().source.contains("\"token_secret\":\"secret\""))
        // Durable on disk too.
        assertEquals(1, store().list().size)
        assertEquals(1, store().list().single().routes.size)
    }

    @Test
    fun importFailureDoesNotPersistAnything() = runBlocking {
        val fake = FakeDownloader(
            SubscriptionDownloadResult(error = "Subscription is invalid or expired (HTTP 404)")
        )
        val repository = SubscriptionRepository(store(), fake::download) { 1L }

        val result = repository.importSubscription("https://example.com/sub/token")

        assertNotNull(result.error)
        assertTrue(repository.subscriptions.value.isEmpty())
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun importRejectsInvalidUrlWithoutDownloading() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }

        val result = repository.importSubscription("ftp://example.com/sub/token")

        assertNotNull(result.error)
        assertEquals(0, fake.calls)
        assertTrue(repository.subscriptions.value.isEmpty())
    }

    @Test
    fun importRejectsInvalidPayload() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = """{"not":"an array"}"""))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }

        val result = repository.importSubscription("https://example.com/sub/token")

        assertNotNull(result.error)
        assertTrue(store().list().isEmpty())
    }
    @Test
    fun refreshFailureKeepsPreviouslySavedRoutes() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 100L }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id

        fake.result = SubscriptionDownloadResult(
            error = "Subscription is invalid or expired (HTTP 404)"
        )
        val result = repository.refreshSubscription(id)

        assertEquals("Subscription is invalid or expired (HTTP 404)", result.error)
        val rec = repository.subscriptions.value.single()
        assertEquals(1, rec.routes.size)
        assertEquals(100L, rec.subscription.updatedAt)
        assertEquals("Subscription is invalid or expired (HTTP 404)", rec.subscription.lastError)
        // And the old routes survive on disk.
        assertEquals(1, store().list().single().routes.size)
    }

    @Test
    fun refreshSuccessReplacesRoutesAndTimestamp() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        var clock = 100L
        val repository = SubscriptionRepository(store(), fake::download) { clock }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id

        fake.result = SubscriptionDownloadResult(body = updatedPayload)
        clock = 200L
        val result = repository.refreshSubscription(id)

        assertNull(result.error)
        assertEquals(2, result.updated)
        val rec = repository.subscriptions.value.single()
        assertEquals(2, rec.routes.size)
        assertEquals("Amsterdam", rec.routes[0].name)
        assertEquals("Berlin", rec.routes[1].name)
        assertEquals(200L, rec.subscription.updatedAt)
        assertNull(rec.subscription.lastError)
    }

    @Test
    fun refreshWithInvalidPayloadKeepsRoutes() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 100L }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id

        fake.result = SubscriptionDownloadResult(body = "[]")
        val result = repository.refreshSubscription(id)

        assertNotNull(result.error)
        val rec = repository.subscriptions.value.single()
        assertEquals(1, rec.routes.size)
        assertNotNull(rec.subscription.lastError)
    }

    @Test
    fun staleRefreshSkipsFreshSubscriptions() = runBlocking {
        val reference = 10_000_000L
        val subscriptionStore = store()
        subscriptionStore.add(record("fresh", updatedAt = reference - 60_000L))
        subscriptionStore.add(record("stale", updatedAt = reference - 2L * 60L * 60L * 1000L))
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(subscriptionStore, fake::download) { reference }

        val results = repository.refreshStale(60L * 60L * 1000L)

        assertEquals(1, results.size)
        assertEquals("stale", results.single().subscription?.id)
        assertEquals(1, fake.calls)
    }

    @Test
    fun staleRefreshUpdatesSubscriptionThatWasNeverUpdated() = runBlocking {
        val subscriptionStore = store()
        subscriptionStore.add(record("never", updatedAt = 0L))
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(subscriptionStore, fake::download) { 500L }

        val results = repository.refreshStale(60L * 60L * 1000L)

        assertEquals(1, results.size)
        assertEquals(1, results.single().updated)
        assertEquals(500L, repository.subscriptions.value.single().subscription.updatedAt)
    }

    @Test
    fun duplicateUrlIsRejected() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }
        repository.importSubscription("https://example.com/sub/token")

        val second = repository.importSubscription("https://example.com/sub/token")

        assertNotNull(second.error)
        assertEquals(1, fake.calls)
        assertEquals(1, store().list().size)
    }

    @Test
    fun deleteRemovesSubscriptionAndItsRoutes() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id

        assertTrue(repository.deleteSubscription(id))

        assertTrue(repository.subscriptions.value.isEmpty())
        assertTrue(store().list().isEmpty())
    }

    @Test
    fun importFallsBackToDomainWhenServerSendsNoTitle() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }

        val result = repository.importSubscription("https://Example.COM/sub/token")

        assertNull(result.error)
        assertEquals(
            "example.com",
            repository.subscriptions.value.single().subscription.name
        )
    }

    @Test
    fun refreshUsesServerTitleToRenameSubscription() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 100L }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id
        assertEquals("example.com", repository.subscriptions.value.single().subscription.name)

        fake.result = SubscriptionDownloadResult(
            body = updatedPayload,
            serverTitle = "Server name"
        )
        val result = repository.refreshSubscription(id)

        assertNull(result.error)
        assertEquals("Server name", result.subscription?.name)
        assertEquals("Server name", repository.subscriptions.value.single().subscription.name)
        assertEquals("Server name", store().list().single().subscription.name)
    }

    @Test
    fun refreshFallsBackToDomainWhenServerSendsNoTitle() = runBlocking {
        val fake = FakeDownloader(
            SubscriptionDownloadResult(body = payload, serverTitle = "Initial title")
        )
        val repository = SubscriptionRepository(store(), fake::download) { 10L }
        val id = repository.importSubscription("https://example.com/sub/token")
            .subscription!!.id
        assertEquals("Initial title", repository.subscriptions.value.single().subscription.name)

        fake.result = SubscriptionDownloadResult(body = updatedPayload)
        val result = repository.refreshSubscription(id)

        assertNull(result.error)
        assertEquals("example.com", repository.subscriptions.value.single().subscription.name)
    }

    @Test
    fun importOfflineBundleCreatesLocalGroup() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 7L }

        val result = repository.importOfflineBundle(offlineBundle)

        assertNull(result.error)
        assertEquals(2, result.updated)
        assertEquals(0, fake.calls)
        val rec = repository.subscriptions.value.single()
        assertEquals("Offline group", rec.subscription.name)
        assertEquals("", rec.subscription.url)
        assertTrue(rec.subscription.isOfflineBundle)
        assertEquals(7L, rec.subscription.updatedAt)
        assertEquals(listOf("Amsterdam", "Berlin"), rec.routes.map { it.name })
        assertTrue(rec.routes[0].source.startsWith("shampoore://yandex"))
        assertTrue(rec.routes[0].summary.contains("Encrypted"))
        assertTrue(store().list().single().subscription.isOfflineBundle)
    }

    @Test
    fun offlineBundleIsNeverRefreshedOverNetwork() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }
        val id = repository.importOfflineBundle(offlineBundle).subscription!!.id
        assertEquals(0, fake.calls)

        val result = repository.refreshSubscription(id)

        assertNull(result.error)
        assertEquals(0, fake.calls)
        assertEquals(2, repository.subscriptions.value.single().routes.size)
    }

    @Test
    fun refreshStaleSkipsOfflineBundles() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }
        repository.importOfflineBundle(offlineBundle)

        val results = repository.refreshStale(0L)

        assertTrue(results.isEmpty())
        assertEquals(0, fake.calls)
    }

    @Test
    fun importOfflineBundleRejectsBundleWithoutLinks() = runBlocking {
        val fake = FakeDownloader(SubscriptionDownloadResult(body = payload))
        val repository = SubscriptionRepository(store(), fake::download) { 1L }

        val result = repository.importOfflineBundle("""{"name":"Empty","links":[]}""")

        assertNotNull(result.error)
        assertTrue(repository.subscriptions.value.isEmpty())
    }
}
