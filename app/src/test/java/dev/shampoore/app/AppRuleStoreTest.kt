package dev.shampoore.app

import dev.shampoore.app.data.AppRuleStore
import dev.shampoore.app.data.CUSTOM_APP_RULE_NAME
import dev.shampoore.app.data.parsePackageList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AppRuleStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store() = AppRuleStore(File(tempFolder.root, "app_rules"))

    // ---------------------------------------------------------------- parser

    @Test
    fun parserIgnoresCommentsBlankLinesAndDuplicates() {
        val raw = """
            # banks
            // marketplaces

              com.example.bank
            com.example.shop
            com.example.bank
            not a package
            com..broken
            com.example.app # trailing comment
        """.trimIndent()

        assertEquals(
            linkedSetOf(
                "com.example.bank",
                "com.example.shop",
                "com.example.app"
            ),
            parsePackageList(raw)
        )
    }

    @Test
    fun parserAcceptsUnderscoresDigitsAndSingleLetterSegments() {
        val raw = "a.b\ncom.example_app.app_2\n123.456\ncom.example-app\ncom.example/app\n"

        assertEquals(
            linkedSetOf("a.b", "com.example_app.app_2", "123.456"),
            parsePackageList(raw)
        )
    }

    @Test
    fun parserHandlesBomAndCrlfLineEndings() {
        val raw = "\uFEFFcom.example.one\r\ncom.example.two\r\n\r\n"

        assertEquals(
            linkedSetOf("com.example.one", "com.example.two"),
            parsePackageList(raw)
        )
    }

    @Test
    fun parserSkipsLinesWithoutAValidPackageName() {
        val raw = """
            #
            //
            com
            .com
            com.
            com..example
            com.example.
            com example.app
        """.trimIndent()

        assertTrue(parsePackageList(raw).isEmpty())
    }

    // ------------------------------------------------------------ custom rule

    @Test
    fun customSelectionIsSavedUpdatedAndPersisted() = runBlocking {
        val store = store()

        val first = store.saveCustomSelectedPackages(
            setOf("com.example.bank", "com.example.shop")
        )
        assertTrue(first.success)
        assertEquals(CUSTOM_APP_RULE_NAME, first.ruleName)

        val saved = store.getAppRules().single { it.isCustomSelection }
        assertEquals(CUSTOM_APP_RULE_NAME, saved.name)
        assertTrue(saved.enabled)
        assertNull(saved.sourceUrl)
        assertEquals(setOf("com.example.bank", "com.example.shop"), saved.packages)

        // Updating the selection replaces the old package list.
        store.saveCustomSelectedPackages(setOf("com.example.bank"))

        val reloaded = store().getAppRules().single { it.isCustomSelection }
        assertEquals(setOf("com.example.bank"), reloaded.packages)
        assertEquals(1, reloaded.packageCount)
    }

    @Test
    fun ruleSizeBytesMatchesFilesOnDisk() = runBlocking {
        val store = store()
        store.saveCustomSelectedPackages(
            setOf("com.example.bank", "com.example.shop")
        )

        val dir = File(tempFolder.root, "app_rules")
        val customFile = File(dir, "$CUSTOM_APP_RULE_NAME.txt")
        assertTrue(customFile.exists())
        val custom = store.getAppRules().single { it.isCustomSelection }
        assertEquals(customFile.length(), custom.sizeBytes)

        val importedFile = File(dir, "market.txt")
        importedFile.writeText(
            "# imported list\ncom.example.shop\ncom.example.market\n",
            Charsets.UTF_8
        )
        val imported = store.getAppRules().single { it.name == "market" }
        assertEquals(importedFile.length(), imported.sizeBytes)
    }

    @Test
    fun customSwitchCanBeCreatedBeforeTheFirstSelection() = runBlocking {
        val store = store()

        assertNull(store.setRuleEnabled(CUSTOM_APP_RULE_NAME, false))
        val disabled = store.getAppRules().single { it.isCustomSelection }
        assertFalse(disabled.enabled)
        assertTrue(disabled.packages.isEmpty())

        assertNull(store.setRuleEnabled(CUSTOM_APP_RULE_NAME, true))
        assertTrue(store.getAppRules().single { it.isCustomSelection }.enabled)
    }

    @Test
    fun deletingTheCustomRuleRemovesItsPackages() = runBlocking {
        val store = store()
        store.saveCustomSelectedPackages(setOf("com.example.bank"))

        assertNull(store.deleteRule(CUSTOM_APP_RULE_NAME))

        assertTrue(store.getAppRules().none { it.isCustomSelection })
        assertTrue(store.getActiveBypassPackages().isEmpty())
    }

    @Test
    fun deletingAnUnknownRuleReportsAnError() = runBlocking {
        assertNotNull(store().deleteRule("missing"))
    }

    // ------------------------------------------------------------- bypass set

    @Test
    fun activePackagesAreMergedFromEnabledRulesOnly() = runBlocking {
        val store = store()
        store.saveCustomSelectedPackages(setOf("com.example.bank"))

        val dir = File(tempFolder.root, "app_rules").apply { mkdirs() }
        File(dir, "market.txt").writeText(
            "# imported list\ncom.example.shop\ncom.example.market\n",
            Charsets.UTF_8
        )

        assertEquals(
            setOf("com.example.bank", "com.example.shop", "com.example.market"),
            store.getActiveBypassPackages()
        )

        // A disabled rule contributes nothing, while the other enabled rule stays.
        assertNull(store.setRuleEnabled(CUSTOM_APP_RULE_NAME, false))
        assertEquals(
            setOf("com.example.shop", "com.example.market"),
            store.getActiveBypassPackages()
        )

        assertNull(store.setRuleEnabled(CUSTOM_APP_RULE_NAME, true))
        assertEquals(
            setOf("com.example.bank", "com.example.shop", "com.example.market"),
            store.getActiveBypassPackages()
        )
    }

    // ------------------------------------------------------------- URL rules

    @Test
    fun urlRuleIsImportedAndRefreshedAndKeepsOldPackagesOnFailure() = runBlocking {
        val body = AtomicReference("com.example.one\ncom.example.two\n")
        val status = AtomicReference("200 OK")

        withServer({ _, out ->
            respond(out, status.get(), body.get().toByteArray(), "Content-Type: text/plain\r\n")
        }) { port ->
            val store = store()
            val url = "http://127.0.0.1:$port/apps.txt"

            val imported = store.importFromUrl(url)
            assertNull(imported.error)
            assertEquals("apps", imported.ruleName)
            assertEquals(url, imported.sourceUrl)
            assertEquals(2, imported.packageCount)

            val rule = store.getAppRules().single { it.name == "apps" }
            assertFalse(rule.isCustomSelection)
            assertEquals("127.0.0.1", rule.sourceHost)
            assertEquals(setOf("com.example.one", "com.example.two"), rule.packages)
            assertTrue(rule.sizeBytes > 0)

            body.set("com.example.three\n")
            val refreshed = store.refreshRemoteRules("apps")
            assertNull(refreshed.error)
            assertEquals(listOf("apps"), refreshed.updated)
            assertTrue(refreshed.failures.isEmpty())
            assertEquals(
                setOf("com.example.three"),
                store.getAppRules().single { it.name == "apps" }.packages
            )

            // A failed refresh must not truncate the previous file.
            status.set("500 Internal Server Error")
            body.set("com.example.four\n")
            val failed = store.refreshRemoteRules("apps")
            assertTrue(failed.updated.isEmpty())
            assertEquals(1, failed.failures.size)
            assertEquals(
                setOf("com.example.three"),
                store.getAppRules().single { it.name == "apps" }.packages
            )

            assertNull(store.deleteRule("apps"))
            assertTrue(store.getAppRules().none { it.name == "apps" })
        }
    }

    @Test
    fun refreshOfAManualRuleReportsThatItHasNoSource() = runBlocking {
        val store = store()
        store.saveCustomSelectedPackages(setOf("com.example.bank"))

        val result = store.refreshRemoteRules(CUSTOM_APP_RULE_NAME)

        assertNotNull(result.error)
        assertTrue(result.updated.isEmpty())
    }

    // ---------------------------------------------------------------- helpers

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

    private suspend fun withServer(
        handler: (path: String, out: OutputStream) -> Unit,
        block: suspend (port: Int) -> Unit
    ) {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(isDaemon = true, name = "app-rule-test") {
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

