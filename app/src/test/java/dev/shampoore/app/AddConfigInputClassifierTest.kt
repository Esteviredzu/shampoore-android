package dev.shampoore.app

import dev.shampoore.app.data.AddConfigInput
import dev.shampoore.app.data.AddConfigInputClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddConfigInputClassifierTest {

    @Test
    fun classifiesHttpAndHttpsAsSubscriptionUrls() {
        val http = AddConfigInputClassifier.classify("http://example.com/sub/token")
        val https = AddConfigInputClassifier.classify("  https://example.com/sub/token  ")

        assertTrue(http is AddConfigInput.SubscriptionUrl)
        assertTrue(https is AddConfigInput.SubscriptionUrl)
        assertEquals(
            "https://example.com/sub/token",
            (https as AddConfigInput.SubscriptionUrl).url
        )
    }

    @Test
    fun classifiesJsonWithLinksAsOfflineBundle() {
        val raw = "{\"name\":\"Group\",\"links\":[\"shampoore://yandex?type=yandex#A\"]}"

        assertTrue(AddConfigInputClassifier.classify(raw) is AddConfigInput.OfflineBundle)
    }

    @Test
    fun classifiesMultilineShampooreLinksAsOfflineBundle() {
        val raw = "shampoore://yandex?type=yandex#A\nshampoore://silo?type=silo#B"

        val input = AddConfigInputClassifier.classify(raw)

        assertTrue(input is AddConfigInput.OfflineBundle)
        assertTrue((input as AddConfigInput.OfflineBundle).raw.contains("shampoore://silo"))
    }

    @Test
    fun classifiesSingleShampooreLinkAsLocalConfig() {
        val input = AddConfigInputClassifier.classify("  shampoore://yandex?type=yandex#A  ")

        assertTrue(input is AddConfigInput.SingleConfig)
        assertEquals(
            "shampoore://yandex?type=yandex#A",
            (input as AddConfigInput.SingleConfig).source
        )
    }

    @Test
    fun classifiesSingleJsonConfigAsLocalConfig() {
        val raw = "{\"name\":\"Route\",\"type\":\"yandex\"}"

        val input = AddConfigInputClassifier.classify(raw)

        assertTrue(input is AddConfigInput.SingleConfig)
        assertEquals(raw, (input as AddConfigInput.SingleConfig).source)
    }

    @Test
    fun rejectsEmptyAndUnrecognizedInput() {
        assertTrue(AddConfigInputClassifier.classify("   ") is AddConfigInput.Invalid)
        assertTrue(AddConfigInputClassifier.classify("hello") is AddConfigInput.Invalid)
        assertTrue(AddConfigInputClassifier.classify("{not json}") is AddConfigInput.Invalid)
    }

    @Test
    fun rejectsJsonBundleWithoutLinks() {
        val input = AddConfigInputClassifier.classify("{\"name\":\"Empty\",\"links\":[]}")

        assertTrue(input is AddConfigInput.Invalid)
    }

    @Test
    fun ignoresNonLinkLinesAroundBundle() {
        val raw = "Group\n\nshampoore://a?type=yandex#A\nshampoore://b?type=silo#B\n"

        val input = AddConfigInputClassifier.classify(raw)

        assertTrue(input is AddConfigInput.OfflineBundle)
        assertEquals(2, (input as AddConfigInput.OfflineBundle).raw.lines().size)
    }
}
