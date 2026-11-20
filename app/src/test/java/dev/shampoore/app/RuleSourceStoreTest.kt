package dev.shampoore.app

import dev.shampoore.app.data.RuleSourceStore
import dev.shampoore.app.data.decodeRuleSourceMap
import dev.shampoore.app.data.encodeRuleSourceMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuleSourceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sourcesArePersistedAndReloadedFromDisk() {
        val file = File(tempFolder.root, "rule_sources.json")
        val ruUrl = "https://raw.githubusercontent.com/user/repo/main/ru.txt"
        val deUrl = "https://example.com/de.txt"

        val store = RuleSourceStore(file)
        store.put("ru", ruUrl)
        store.put("de", deUrl)

        assertTrue(file.exists())
        val reloaded = RuleSourceStore(file)
        assertEquals(ruUrl, reloaded.get("ru"))
        assertEquals(deUrl, reloaded.get("de"))
        assertEquals(2, reloaded.snapshot().size)
    }

    @Test
    fun removingOneSourceKeepsTheOthers() {
        val file = File(tempFolder.root, "rule_sources.json")
        RuleSourceStore(file).apply {
            put("ru", "https://example.com/ru.txt")
            put("de", "https://example.com/de.txt")
        }

        val store = RuleSourceStore(file)
        store.remove("ru")

        assertNull(store.get("ru"))
        assertEquals("https://example.com/de.txt", store.get("de"))
        assertEquals(setOf("de"), store.snapshot().keys)

        // And the removal is durable.
        assertNull(RuleSourceStore(file).get("ru"))
        assertEquals(1, RuleSourceStore(file).snapshot().size)
    }

    @Test
    fun removingUnknownSourceIsANoOp() {
        val file = File(tempFolder.root, "rule_sources.json")
        val store = RuleSourceStore(file)
        store.put("ru", "https://example.com/ru.txt")

        store.remove("missing")

        assertEquals(1, store.snapshot().size)
        assertEquals("https://example.com/ru.txt", store.get("ru"))
    }

    @Test
    fun removingTheLastSourceLeavesAnEmptyObject() {
        val file = File(tempFolder.root, "rule_sources.json")
        val store = RuleSourceStore(file)
        store.put("ru", "https://example.com/ru.txt")

        store.remove("ru")

        assertTrue(store.snapshot().isEmpty())
        assertTrue(file.readText().trim().startsWith("{"))
        assertTrue(RuleSourceStore(file).snapshot().isEmpty())
    }

    @Test
    fun codecRoundTripsSpecialCharacters() {
        val sources = linkedMapOf(
            "plain" to "https://example.com/plain.txt",
            "with \"quotes\"" to "https://example.com/a.txt?x=1&y=2",
            "back\\slash" to "https://example.com/back\\slash.txt",
            "кириллица" to "https://example.com/%D1%80%D1%83.txt",
            "control\nchar" to "https://example.com/tab\tvalue.txt"
        )

        assertEquals(sources, decodeRuleSourceMap(encodeRuleSourceMap(sources)))
    }

    @Test
    fun malformedMetadataDoesNotCrash() {
        val file = File(tempFolder.root, "broken.json")
        file.writeText("{not valid json")
        val store = RuleSourceStore(file)

        assertNull(store.get("ru"))
        assertTrue(store.snapshot().isEmpty())
    }
}
