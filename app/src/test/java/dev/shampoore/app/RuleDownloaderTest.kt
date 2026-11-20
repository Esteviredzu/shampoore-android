package dev.shampoore.app

import dev.shampoore.app.data.RuleDownloader
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
import kotlin.concurrent.thread

class RuleDownloaderTest {

    @Test
    fun followsRedirectsAndReturnsTheFinalUrl() = withServer({ path, out ->
        if (path == "/start") {
            respond(out, "302 Found", ByteArray(0), "Location: /rules.txt\r\n")
        } else {
            respond(out, "200 OK", "1.2.3.0/24\n".toByteArray(), "Content-Type: text/plain\r\n")
        }
    }) { port ->
        val result = RuleDownloader.download("http://127.0.0.1:$port/start")

        assertNull(result.error)
        assertEquals("1.2.3.0/24\n", result.body)
        assertTrue(result.finalUrl.endsWith("/rules.txt"))
    }

    @Test
    fun rejectsOversizedBodies() = withServer({ _, out ->
        val body = "x".repeat(128).toByteArray()
        respond(out, "200 OK", body)
    }) { port ->
        val result = RuleDownloader.download("http://127.0.0.1:$port/big", maxBytes = 16)

        assertNotNull(result.error)
        assertNull(result.body)
    }

    @Test
    fun reportsHttpErrors() = withServer({ _, out ->
        respond(out, "404 Not Found", ByteArray(0))
    }) { port ->
        val result = RuleDownloader.download("http://127.0.0.1:$port/missing")

        assertNotNull(result.error)
        assertTrue(result.error.orEmpty().contains("404"))
    }

    private fun respond(
        out: OutputStream,
        status: String,
        body: ByteArray,
        extraHeaders: String = ""
    ) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            extraHeaders +
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
            val worker = thread(isDaemon = true, name = "rule-downloader-test") {
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
