package dev.shampoore.app

import dev.shampoore.app.data.SubscriptionPayloadParser
import dev.shampoore.app.data.routeNameFromLink
import dev.shampoore.app.data.routeTransportLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPayloadParserTest {

    private val first = """{"name":"Amsterdam","type":"yandex","user_id":"user-1",""" +
        """"exit_node_id":"node-7","token_url":"https://example.com/token",""" +
        """"token_secret":"secret-1","encryption_password":"pass-1","transport":{"board":"main"}}"""
    private val second = """{"name":"Multi","type":"multi","transport":""" +
        """{"strategy":"flowmux","transports":[{"type":"yandex"},{"type":"wireguard"}]}}"""
    private val payload = "[$first,\n $second]"

    @Test
    fun parsesArrayAndPreservesRawObjects() {
        val result = SubscriptionPayloadParser.parse(payload, "sub-1")

        assertNull(result.error)
        assertEquals(2, result.routes.size)
        assertEquals("sub-1", result.routes[0].subscriptionId)
        assertEquals("Amsterdam", result.routes[0].name)
        assertEquals(first, result.routes[0].source)
        assertEquals(second, result.routes[1].source)
        assertTrue(result.routes[0].source.contains("\"user_id\":\"user-1\""))
        assertTrue(result.routes[0].source.contains("\"exit_node_id\":\"node-7\""))
        assertTrue(result.routes[0].source.contains("\"token_url\":\"https://example.com/token\""))
        assertTrue(result.routes[0].source.contains("\"token_secret\":\"secret-1\""))
        assertTrue(result.routes[0].source.contains("\"encryption_password\":\"pass-1\""))
    }

    @Test
    fun buildsRouteSummaryWithTransportInfo() {
        val result = SubscriptionPayloadParser.parse(payload, "sub-1")

        assertTrue(result.routes[0].summary.contains("Yandex"))
        assertTrue(result.routes[0].summary.contains("Encrypted"))
        assertTrue(result.routes[1].summary.contains("Multi"))
        assertTrue(result.routes[1].summary.contains("flowmux"))
        assertTrue(result.routes[1].summary.contains("2 transports"))
    }

    @Test
    fun fallsBackToTypeThenRouteNumberForMissingNames() {
        val result = SubscriptionPayloadParser.parse(
            """[{"type":"yandex"},{"transport":{}}]""",
            "sub-1"
        )

        assertNull(result.error)
        assertEquals("yandex", result.routes[0].name)
        assertEquals("Route 2", result.routes[1].name)
    }

    @Test
    fun decodesEscapedNames() {
        val result = SubscriptionPayloadParser.parse(
            """[{"name":"Амстердам \"A\"","type":"yandex"}]""",
            "sub-1"
        )

        assertNull(result.error)
        assertEquals("Амстердам \"A\"", result.routes.single().name)
    }

    @Test
    fun rejectsInvalidJson() {
        val result = SubscriptionPayloadParser.parse("[{]", "sub-1")

        assertNotNull(result.error)
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun rejectsNonArrayRoot() {
        val result = SubscriptionPayloadParser.parse("""{"name":"Amsterdam"}""", "sub-1")

        assertNotNull(result.error)
        assertTrue(result.error.orEmpty().contains("JSON array"))
    }

    @Test
    fun rejectsEmptyArray() {
        val result = SubscriptionPayloadParser.parse("[]", "sub-1")

        assertNotNull(result.error)
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun rejectsArrayWithoutConfigObjects() {
        val result = SubscriptionPayloadParser.parse("""[1, "text", null]""", "sub-1")

        assertNotNull(result.error)
        assertTrue(result.routes.isEmpty())
    }

    @Test
    fun ignoresNonObjectElementsWhenValidConfigsExist() {
        val result = SubscriptionPayloadParser.parse(
            """[1, {"name":"Kept","type":"yandex"}]""",
            "sub-1"
        )

        assertNull(result.error)
        assertEquals(1, result.routes.size)
        assertEquals("Kept", result.routes.single().name)
    }

    @Test
    fun parsesOfflineJsonBundle() {
        val bundle = "{\"name\":\"Group A\",\"links\":[" +
            "\"shampoore://yandex?type=yandex&pw=pw#Amsterdam\"," +
            "\"shampoore://silo?type=silo#Berlin\"]}"

        val result = SubscriptionPayloadParser.parseOfflineBundle(bundle, "off-1")

        assertNull(result.error)
        assertEquals("Group A", result.name)
        assertEquals(2, result.routes.size)
        assertEquals("off-1", result.routes[0].subscriptionId)
        assertEquals("Amsterdam", result.routes[0].name)
        assertEquals("Berlin", result.routes[1].name)
        assertEquals(
            "shampoore://yandex?type=yandex&pw=pw#Amsterdam",
            result.routes[0].source
        )
        assertTrue(result.routes[0].summary.startsWith("Yandex"))
        assertTrue(result.routes[0].summary.contains("Encrypted"))
        assertTrue(result.routes[1].summary.startsWith("Silo"))
    }

    @Test
    fun parsesMultilineOfflineLinks() {
        val raw = "\n shampoore://livekit?type=livekit_datachannel#First \n" +
            "shampoore://raw?type=raw_ws#Second\n"

        val result = SubscriptionPayloadParser.parseOfflineBundle(raw, "off-2")

        assertNull(result.error)
        assertEquals("Imported group", result.name)
        assertEquals(listOf("First", "Second"), result.routes.map { it.name })
        assertEquals("LiveKit", routeTransportLabel(result.routes[0].source))
        assertEquals("RawWS", routeTransportLabel(result.routes[1].source))
    }

    @Test
    fun offlineRouteNameFallsBackToType() {
        val result = SubscriptionPayloadParser.parseOfflineBundle(
            "shampoore://yandex?type=yandex",
            "off-3"
        )

        assertNull(result.error)
        assertEquals("yandex", result.routes.single().name)
    }

    @Test
    fun decodesRouteNameFromFragment() {
        assertEquals(
            "Мой маршрут",
            routeNameFromLink(
                "shampoore://yandex?type=yandex#%D0%9C%D0%BE%D0%B9%20%D0%BC%D0%B0%D1%80%D1%88%D1%80%D1%83%D1%82"
            )
        )
    }

    @Test
    fun rejectsOfflineBundleWithoutLinks() {
        val json = SubscriptionPayloadParser.parseOfflineBundle(
            """{"name":"Empty","links":[]}""",
            "x"
        )
        assertNotNull(json.error)
        assertTrue(json.routes.isEmpty())

        val text = SubscriptionPayloadParser.parseOfflineBundle("not a link", "x")
        assertNotNull(text.error)
        assertTrue(text.routes.isEmpty())
    }

    @Test
    fun transportLabelsCoverKnownKinds() {
        assertEquals("LiveKit", routeTransportLabel("""{"type":"livekit_datachannel"}"""))
        assertEquals("Silo", routeTransportLabel("""{"type":"silo"}"""))
        assertEquals("RawWS", routeTransportLabel("""{"type":"raw_ws"}"""))
        assertEquals("Yandex", routeTransportLabel("""{"type":"yandex"}"""))
    }
}
