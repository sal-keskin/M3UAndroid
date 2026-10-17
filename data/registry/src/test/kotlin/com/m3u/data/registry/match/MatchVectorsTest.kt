package com.m3u.data.registry.match

import com.m3u.data.registry.model.RegistryChannelSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Runs the vectors shared with the registry repository's Python rules
 * (registry-template/tests/match_vectors.json).
 */
class MatchVectorsTest {
    private val vectors: JsonObject = run {
        val path = System.getProperty("registry.matchVectors")
            ?: error("system property registry.matchVectors is not set")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private val registry: List<RegistryChannelSpec> = vectors.getValue("registry").jsonArray.mapIndexed { index, element ->
        val channel = element.jsonObject
        RegistryChannelSpec(
            id = channel.string("id")!!,
            rank = index + 1,
            name = channel.string("name")!!,
            aliases = channel.strings("aliases"),
            exclude = channel.strings("exclude"),
        )
    }

    @Test
    fun `normalization vectors`() {
        val failures = vectors.getValue("normalize").jsonArray.mapNotNull { element ->
            val case = element.jsonObject
            val input = case.string("input")!!
            val expected = case.string("expected")!!
            val actual = NameNormalizer.normalize(input)
            if (actual == expected) null else "normalize(\"$input\") = \"$actual\", expected \"$expected\""
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `match vectors`() {
        val matcher = ChannelMatcher(registry)
        val failures = vectors.getValue("match").jsonArray.mapNotNull { element ->
            val case = element.jsonObject
            val name = case.string("name")!!
            val tvgId = case.string("tvgId")?.takeIf(String::isNotEmpty)
            val result = matcher.match(name, tvgId)
            val expected = case.string("expected")
            val expectedTier = case.string("tier")
            val notExpected = case.string("notExpected")
            when {
                result?.channelId != expected ->
                    "match(\"$name\", $tvgId) = ${result?.channelId}, expected $expected"

                expectedTier != null && result?.tier?.name != expectedTier ->
                    "match(\"$name\", $tvgId) tier = ${result?.tier}, expected $expectedTier"

                notExpected != null && result?.channelId == notExpected ->
                    "match(\"$name\", $tvgId) must not be $notExpected"

                else -> null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `required normalization examples`() {
        assertEquals("trt cocuk", NameNormalizer.normalize("TRT Çocuk (1440p)"))
        assertEquals("izmir", NameNormalizer.normalize("İZMİR"))
    }

    @Test
    fun `required must match and must not match examples`() {
        val matcher = ChannelMatcher(registry)
        assertEquals("A2TV.tr", matcher.match("A2TV")?.channelId)
        assertEquals("HaberturkTV.tr", matcher.match("Habertürk TV")?.channelId)
        assertEquals("TV8.tr", matcher.match("TV 8")?.channelId)
        assertEquals("NOWTV.tr", matcher.match("NOW TV")?.channelId)
        assertEquals("UlkeTV.tr", matcher.match("Ulke TV")?.channelId)
        assertEquals("Tele1.tr", matcher.match("Tele 1")?.channelId)

        assertNotEquals("ShowTV.tr", matcher.match("Show Turk")?.channelId)
        assertNotEquals("ATV.tr", matcher.match("ATV Avrupa")?.channelId)
        assertNotEquals("Kanal7.tr", matcher.match("Kanal 7 Avrupa")?.channelId)
        assertNotEquals("KanalD.tr", matcher.match("Kanal D Drama")?.channelId)
        assertNotEquals("TV85.tr", matcher.match("TV 8")?.channelId)
        assertNotEquals("BiKanal.tr", matcher.match("Bir TV")?.channelId)
        assertNotEquals("FlashTV.tr", matcher.match("Flash Haber TV")?.channelId)
        assertNotEquals("TRTSpor.tr", matcher.match("TRT Spor Yildiz")?.channelId)
        assertNotEquals("tv2.tr", matcher.match("TV 264")?.channelId)
    }

    @Test
    fun `exclude list applies to exact ids too`() {
        val matcher = ChannelMatcher(registry)
        assertEquals(null, matcher.match("ATV Avrupa", "ATV.tr@SD"))
        assertEquals(MatchTier.EXACT_ID, matcher.match("ATV (1080p)", "ATV.tr@SD")?.tier)
    }

    @Test
    fun `fuzzy needs a strong score and identical numbers`() {
        assertEquals(0.0, ChannelMatcher.fuzzyScore(listOf("kanal", "d"), listOf("kanal", "7")))
        assertEquals(0.0, ChannelMatcher.fuzzyScore(listOf("tv", "8"), listOf("tv", "8", "5")))
        assertEquals(0.0, ChannelMatcher.fuzzyScore(listOf("show", "turk"), listOf("show")))
        assertTrue(ChannelMatcher.fuzzyScore(listOf("haber", "glbal"), listOf("haber", "global")) >= 0.92)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
}
