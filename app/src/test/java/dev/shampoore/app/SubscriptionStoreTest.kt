package dev.shampoore.app

import dev.shampoore.app.data.Subscription
import dev.shampoore.app.data.SubscriptionRecord
import dev.shampoore.app.data.SubscriptionRoute
import dev.shampoore.app.data.SubscriptionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SubscriptionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun file() = File(tempFolder.root, "subscriptions.json")

    private fun store() = SubscriptionStore(file())

    private fun route(id: String, name: String) = SubscriptionRoute(
        subscriptionId = id,
        name = name,
        summary = "Yandex · Encrypted",
        source = """{"name":"$name","type":"yandex","user_id":"u-$name"}"""
    )

    private fun record(
        id: String,
        updatedAt: Long = 1000L,
        error: String? = null
    ) = SubscriptionRecord(
        subscription = Subscription(
            id = id,
            name = "Sub $id",
            url = "https://example.com/sub/$id",
            updatedAt = updatedAt,
            lastError = error
        ),
        routes = listOf(route(id, "route-a"), route(id, "route-b"))
    )

    @Test
    fun addedSubscriptionIsPersistedWithRoutes() {
        store().add(record("s1"))

        val reloaded = store().list()
        assertEquals(1, reloaded.size)
        val rec = reloaded.single()
        assertEquals("Sub s1", rec.subscription.name)
        assertEquals("https://example.com/sub/s1", rec.subscription.url)
        assertEquals(1000L, rec.subscription.updatedAt)
        assertEquals(2, rec.routes.size)
        assertEquals("route-a", rec.routes[0].name)
        assertTrue(rec.routes[0].source.contains("\"user_id\":\"u-route-a\""))
    }

    @Test
    fun replaceRoutesUpdatesTimestampAndClearsError() {
        val store = store()
        store.add(record("s1", error = "old error"))

        store.replaceRoutes("s1", listOf(route("s1", "new-route")), 5000L)

        val rec = store().list().single()
        assertEquals(1, rec.routes.size)
        assertEquals("new-route", rec.routes.single().name)
        assertEquals(5000L, rec.subscription.updatedAt)
        assertNull(rec.subscription.lastError)
    }

    @Test
    fun networkErrorKeepsSavedRoutes() {
        val store = store()
        store.add(record("s1"))

        store.markError("s1", "Subscription is invalid or expired (HTTP 404)")

        val rec = store().list().single()
        assertEquals(2, rec.routes.size)
        assertEquals("route-a", rec.routes[0].name)
        assertEquals("route-b", rec.routes[1].name)
        assertEquals(1000L, rec.subscription.updatedAt)
        assertEquals("Subscription is invalid or expired (HTTP 404)", rec.subscription.lastError)
    }

    @Test
    fun deleteRemovesSubscriptionAndItsRoutes() {
        val store = store()
        store.add(record("s1"))
        store.add(record("s2"))

        store.delete("s1")

        val remaining = store().list()
        assertEquals(1, remaining.size)
        assertEquals("s2", remaining.single().subscription.id)
        assertEquals(2, remaining.single().routes.size)
    }

    @Test
    fun deletingUnknownSubscriptionKeepsOthers() {
        val store = store()
        store.add(record("s1"))

        store.delete("missing")

        assertEquals(1, store().list().size)
    }

    @Test
    fun malformedFileIsIgnored() {
        file().writeText("{ this is not json")

        assertTrue(store().list().isEmpty())
    }

    @Test
    fun codecRoundTripsQuotesAndUnicode() {
        val name = "Маршрут \"A\" \\ test"
        val store = store()
        store.add(
            SubscriptionRecord(
                Subscription("s1", name, "https://example.com/sub/a?x=1&y=2", 10L, null),
                listOf(
                    SubscriptionRoute(
                        subscriptionId = "s1",
                        name = name,
                        summary = "Yandex · Encrypted",
                        source = """{"name":"$name","type":"yandex","encryption_password":"pw"}"""
                    )
                )
            )
        )

        val rec = store().list().single()
        assertEquals(name, rec.subscription.name)
        assertEquals("https://example.com/sub/a?x=1&y=2", rec.subscription.url)
        assertEquals(name, rec.routes.single().name)
        assertTrue(rec.routes.single().source.contains("Маршрут"))
    }

    @Test
    fun offlineBundleFlagRoundTrips() {
        store().add(
            SubscriptionRecord(
                subscription = Subscription(
                    id = "off1",
                    name = "Offline group",
                    url = "",
                    updatedAt = 5L,
                    isOfflineBundle = true
                ),
                routes = listOf(route("off1", "route"))
            )
        )

        val rec = store().list().single()
        assertTrue(rec.subscription.isOfflineBundle)
        assertEquals("", rec.subscription.url)
        assertEquals("Offline group", rec.subscription.name)
        assertEquals(1, rec.routes.size)
    }

    @Test
    fun oldRecordsWithoutOfflineFlagAreNetworkSubscriptions() {
        file().writeText(
            "[{\"id\":\"s1\",\"name\":\"Sub\",\"url\":\"https://example.com/sub\"," +
                "\"updated_at\":1,\"last_error\":null,\"routes\":[]}]"
        )

        assertFalse(store().list().single().subscription.isOfflineBundle)
    }

    @Test
    fun replaceRoutesCanRenameSubscription() {
        val store = store()
        store.add(record("s1"))

        store.replaceRoutes("s1", emptyList(), 9L, "Server title")

        val subscription = store().list().single().subscription
        assertEquals("Server title", subscription.name)
        assertEquals(9L, subscription.updatedAt)
        assertTrue(store().list().single().routes.isEmpty())
    }
}
