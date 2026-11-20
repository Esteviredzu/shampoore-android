package dev.shampoore.app

import dev.shampoore.app.data.SubscriptionDownloader
import dev.shampoore.app.data.decodeProfileTitle
import dev.shampoore.app.data.subscriptionHttpErrorMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

class SubscriptionDownloaderTest {

    @Test
    fun forbiddenAndMissingMapToExpiredMessage() {
        assertEquals(
            "Subscription is invalid or expired (HTTP 403)",
            subscriptionHttpErrorMessage(403, "example.com")
        )
        assertEquals(
            "Subscription is invalid or expired (HTTP 404)",
            subscriptionHttpErrorMessage(404, "example.com")
        )
        assertTrue(subscriptionHttpErrorMessage(500, "example.com").contains("500"))
    }

    @Test
    fun rejectsNonHttpUrls() {
        val result = SubscriptionDownloader.download("ftp://example.com/sub/token")

        assertNotNull(result.error)
        assertNull(result.body)
    }

    @Test
    fun reportsForbiddenAsExpiredSubscription() = withServer({ _, out ->
        respond(out, "403 Forbidden", ByteArray(0))
    }) { port ->
        val result = SubscriptionDownloader.download("http://127.0.0.1:$port/sub/token")

        assertNotNull(result.error)
        assertTrue(result.error.orEmpty().contains("invalid or expired"))
    }

    @Test
    fun rejectsOversizedBodies() = withServer({ _, out ->
        respond(out, "200 OK", "x".repeat(128).toByteArray())
    }) { port ->
        val result = SubscriptionDownloader.download(
            "http://127.0.0.1:$port/sub/token",
            maxBytes = 16
        )

        assertNotNull(result.error)
        assertNull(result.body)
    }

    @Test
    fun returnsBodyForSuccessfulRequest() = withServer({ _, out ->
        respond(out, "200 OK", """[{"name":"route"}]""".toByteArray())
    }) { port ->
        val result = SubscriptionDownloader.download("http://127.0.0.1:$port/sub/token")

        assertNull(result.error)
        assertEquals("""[{"name":"route"}]""", result.body)
    }

    @Test
    fun decodesPlainProfileTitle() {
        assertEquals("My subscription", decodeProfileTitle("My subscription"))
        assertNull(decodeProfileTitle("   "))
        assertNull(decodeProfileTitle(null))
    }

    @Test
    fun decodesBase64ProfileTitle() {
        val title = "Моя подписка"
        val encoded = Base64.getEncoder().encodeToString(title.toByteArray(Charsets.UTF_8))

        assertEquals(title, decodeProfileTitle("base64:$encoded"))
    }

    @Test
    fun brokenBase64ProfileTitleIsIgnored() {
        assertNull(decodeProfileTitle("base64:!!!not-base64!!!"))
        assertNull(decodeProfileTitle("base64:"))
    }

    @Test
    fun readsBase64ProfileTitleHeader() = withServer({ _, out ->
        val encoded = Base64.getEncoder()
            .encodeToString("Server title".toByteArray(Charsets.UTF_8))
        respond(
            out,
            "200 OK",
            """[{"name":"route"}]""".toByteArray(),
            mapOf("Profile-Title" to "base64:$encoded")
        )
    }) { port ->
        val result = SubscriptionDownloader.download("http://127.0.0.1:$port/sub/token")

        assertNull(result.error)
        assertEquals("Server title", result.serverTitle)
    }

    @Test
    fun readsPlainProfileTitleHeader() = withServer({ _, out ->
        respond(
            out,
            "200 OK",
            """[{"name":"route"}]""".toByteArray(),
            mapOf("Profile-Title" to "Plain title")
        )
    }) { port ->
        val result = SubscriptionDownloader.download("http://127.0.0.1:$port/sub/token")

        assertNull(result.error)
        assertEquals("Plain title", result.serverTitle)
    }

    private fun respond(
        out: OutputStream,
        status: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extraHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
            "Connection: close\r\n" +
            "\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun withServer(
        handler: (path: String, out: OutputStream) -> Unit,
        block: (port: Int) -> Unit
    ) {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true, name = "subscription-downloader-test") {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    serve(socket, handler)
                }
            }
            try {
                block(server.localPort)
            } finally {
                server.close()
                worker.join(1000)
            }
        }
    }

    private fun serve(socket: Socket, handler: (path: String, out: OutputStream) -> Unit) {
        socket.use { client ->
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            handler(path, client.getOutputStream())
        }
    }
}
