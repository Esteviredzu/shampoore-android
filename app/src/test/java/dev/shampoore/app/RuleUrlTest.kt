package dev.shampoore.app

import dev.shampoore.app.data.isSupportedRuleUrl
import dev.shampoore.app.data.normalizeRuleUrl
import dev.shampoore.app.data.remoteRuleContentError
import dev.shampoore.app.data.ruleFileNameFromUrl
import dev.shampoore.app.data.ruleSourceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleUrlTest {

    @Test
    fun githubBlobLinksAreConvertedToGithubUserContent() {
        assertEquals(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/ipv4.txt",
            normalizeRuleUrl("https://github.com/StevenBlack/hosts/blob/master/ipv4.txt")
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/rules/ru.txt",
            normalizeRuleUrl("https://github.com/user/repo/blob/main/rules/ru.txt")
        )
    }

    @Test
    fun githubRawMarkerAndBranchSlashesAreSupported() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/ru.txt",
            normalizeRuleUrl("https://github.com/user/repo/raw/main/ru.txt")
        )
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/feature/geo/ru.txt",
            normalizeRuleUrl("https://github.com/user/repo/blob/feature/geo/ru.txt")
        )
        // http:// is upgraded because raw content is only served over https.
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/ru.txt",
            normalizeRuleUrl("http://www.github.com/user/repo/blob/main/ru.txt")
        )
    }

    @Test
    fun nonGithubAndRawLinksStayUntouched() {
        assertEquals(
            "https://raw.githubusercontent.com/user/repo/main/ru.txt",
            normalizeRuleUrl("https://raw.githubusercontent.com/user/repo/main/ru.txt")
        )
        assertEquals(
            "https://example.com/lists/ru.txt?raw=1",
            normalizeRuleUrl("https://example.com/lists/ru.txt?raw=1")
        )
        assertEquals("not a link", normalizeRuleUrl("  not a link  "))
        assertEquals("https://github.com/user/repo", normalizeRuleUrl("https://github.com/user/repo"))
    }

    @Test
    fun onlyHttpAndHttpsLinksAreAccepted() {
        assertTrue(isSupportedRuleUrl("https://example.com/ru.txt"))
        assertTrue(isSupportedRuleUrl("http://example.com/ru.txt"))
        assertFalse(isSupportedRuleUrl("ftp://example.com/ru.txt"))
        assertFalse(isSupportedRuleUrl("example.com/ru.txt"))
        assertFalse(isSupportedRuleUrl(""))
    }

    @Test
    fun fileNameIsTakenFromTheLastPathSegment() {
        assertEquals(
            "ipv4-aggregated.txt",
            ruleFileNameFromUrl("https://example.com/lists/ipv4-aggregated.txt?raw=true")
        )
        assertEquals(
            "ru rules.txt",
            ruleFileNameFromUrl("https://example.com/lists/ru%20rules.txt")
        )
        assertNull(ruleFileNameFromUrl("https://example.com/"))
    }

    @Test
    fun htmlAndEmptyDownloadsAreRejected() {
        assertNull(remoteRuleContentError("1.2.3.0/24\n5.6.7.0/24\n"))
        assertNull(remoteRuleContentError("{\"cidrs\":[\"1.2.3.0/24\"]}", "application/json"))
        assertEquals(
            "The downloaded file is empty",
            remoteRuleContentError("   ")
        )
        assertEquals(
            "The link points to an HTML page, not a rule file",
            remoteRuleContentError("<!DOCTYPE html><html></html>")
        )
        assertEquals(
            "The link points to an HTML page, not a rule file",
            remoteRuleContentError("1.2.3.0/24", "text/html; charset=utf-8")
        )
    }

    @Test
    fun sourceHostIsParsedForCards() {
        assertEquals(
            "raw.githubusercontent.com",
            ruleSourceHost("https://raw.githubusercontent.com/user/repo/main/ru.txt")
        )
        assertNull(ruleSourceHost("not a link"))
    }
}
