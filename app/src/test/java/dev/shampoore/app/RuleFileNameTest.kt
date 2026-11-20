package dev.shampoore.app

import dev.shampoore.app.data.detectRuleExtension
import dev.shampoore.app.data.ruleBaseName
import dev.shampoore.app.data.sanitizeRuleBaseName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleFileNameTest {

    @Test
    fun baseNameDropsOnlyTheExtension() {
        assertEquals("ru", ruleBaseName("ru.txt"))
        assertEquals("ru", ruleBaseName("ru.json"))
        assertEquals("my.rules", ruleBaseName("my.rules.txt"))
        assertEquals("ru", ruleBaseName("content://docs/ru.txt"))
    }

    @Test
    fun forbiddenCharactersAreReplaced() {
        assertEquals("file", sanitizeRuleBaseName("my/file"))
        assertEquals("geo_rules", sanitizeRuleBaseName("geo:rules"))
        assertEquals("what_s_next", sanitizeRuleBaseName("what?s:next"))
        assertEquals("rules", sanitizeRuleBaseName("rules. ."))
    }

    @Test
    fun emptyNameFallsBackToTimestampedName() {
        val name = sanitizeRuleBaseName("   ")
        assertTrue(name.startsWith("imported_"))
    }

    @Test
    fun extensionIsDetectedFromNameOrContent() {
        assertEquals(".json", detectRuleExtension("ru.json", "1.2.3.0/24"))
        assertEquals(".txt", detectRuleExtension("ru.txt", "{\"cidrs\":[]}"))
        assertEquals(".json", detectRuleExtension("ru", "{\"cidrs\":[]}"))
        assertEquals(".txt", detectRuleExtension("ru.csv", "1.2.3.0/24"))
        assertEquals(".txt", detectRuleExtension(null, "1.2.3.0/24"))
    }
}
