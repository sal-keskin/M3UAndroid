package com.m3u.data.registry.validate

import com.m3u.data.registry.plan.ValidationTarget
import com.m3u.testing.mockserver.EmbeddedMockServer
import com.m3u.testing.mockserver.startEmbeddedMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the validator against the deterministic link-check routes of `:testing:mock-server`.
 */
class StreamValidatorTest {
    private val validator = StreamValidator(OkHttpClient(), budgetMillis = 2_000L)

    private fun target(path: String, headers: Map<String, String> = emptyMap()) =
        ValidationTarget("${server.baseUrl}$path", headers)

    @Test
    fun `good master picks the tallest variant at or below 1080p`() = runBlocking {
        val result = validator.validate(target("/linkcheck/good/master.m3u8"))
        assertTrue(result.ok, result.failureReason)
        assertEquals(1080, result.height)
        assertEquals(5_000_000L, result.bandwidth)
        assertTrue((result.latencyMs ?: -1) >= 0)
    }

    @Test
    fun `forbidden is reported with its status`() = runBlocking {
        val result = validator.validate(target("/linkcheck/forbidden.m3u8"))
        assertFalse(result.ok)
        assertEquals("HTTP 403", result.failureReason)
    }

    @Test
    fun `empty playlist fails`() = runBlocking {
        val result = validator.validate(target("/linkcheck/empty.m3u8"))
        assertFalse(result.ok)
        assertEquals(StreamValidator.REASON_EMPTY, result.failureReason)
    }

    @Test
    fun `slow source runs out of budget`() = runBlocking {
        val started = System.nanoTime()
        val result = validator.validate(target("/linkcheck/slow.m3u8"))
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertFalse(result.ok)
        assertEquals(StreamValidator.REASON_TIMEOUT, result.failureReason)
        assertTrue(elapsedMillis < 5_000, "validation took $elapsedMillis ms")
    }

    @Test
    fun `channel headers are sent`() = runBlocking {
        assertEquals("HTTP 403", validator.validate(target("/linkcheck/needs-referer.m3u8")).failureReason)
        val result = validator.validate(
            target("/linkcheck/needs-referer.m3u8", mapOf("Referer" to "https://referer.example/"))
        )
        assertTrue(result.ok, result.failureReason)
    }

    @Test
    fun `missing segments fail the probe`() = runBlocking {
        val result = validator.validate(target("/linkcheck/segment-404.m3u8"))
        assertFalse(result.ok)
        assertEquals("segment HTTP 404", result.failureReason)
        assertTrue(StreamValidator(OkHttpClient(), probeSegment = false).validate(target("/linkcheck/segment-404.m3u8")).ok)
    }

    @Test
    fun `day-long dvr playlists validate, even past the read limit`() = runBlocking {
        // ~1 MB, like TRT's 24 h window
        assertTrue(validator.validate(target("/linkcheck/dvr/14400.m3u8")).ok)
        // ~2.6 MB: read to the limit, the cut last line is ignored
        val truncated = StreamValidator(OkHttpClient(), budgetMillis = 10_000L).validate(target("/linkcheck/dvr/48000.m3u8"))
        assertTrue(truncated.ok, truncated.failureReason)
    }

    @Test
    fun `web pages are not playlists`() = runBlocking {
        assertEquals(StreamValidator.REASON_NOT_HLS, validator.validate(target("/linkcheck/not-hls.m3u8")).failureReason)
    }

    @Test
    fun `validate all reports progress and keeps one result per url`() = runBlocking {
        val progress = mutableListOf<Int>()
        val targets = listOf(
            target("/linkcheck/good/master.m3u8"),
            target("/linkcheck/forbidden.m3u8"),
            target("/linkcheck/empty.m3u8"),
            target("/linkcheck/good/master.m3u8"),
        )
        val results = validator.validateAll(targets) { done, _ -> synchronized(progress) { progress += done } }
        assertEquals(3, results.size)
        assertEquals(listOf(1, 2, 3), progress.sorted())
        assertTrue(results.getValue("${server.baseUrl}/linkcheck/good/master.m3u8").ok)
    }

    @Test
    fun `inspector chooses variants`() {
        val variants = listOf(
            HlsInspector.Variant("a", 800_000, 640, 360),
            HlsInspector.Variant("b", 5_000_000, 1920, 1080),
            HlsInspector.Variant("c", 9_000_000, 2560, 1440),
        )
        assertEquals("b", HlsInspector.chooseVariant(variants)?.uri)
        assertEquals("a", HlsInspector.chooseVariant(variants.take(1) + variants.last())?.uri)
        assertEquals("c", HlsInspector.chooseVariant(listOf(variants.last()))?.uri)
        assertEquals(
            "y",
            HlsInspector.chooseVariant(
                listOf(
                    HlsInspector.Variant("x", 12_000_000, null, null),
                    HlsInspector.Variant("y", 6_000_000, null, null),
                    HlsInspector.Variant("z", 2_000_000, null, null),
                )
            )?.uri,
        )
        assertEquals("https://h/live/1080.m3u8", HlsInspector.resolve("https://h/live/master.m3u8", "1080.m3u8"))
        assertEquals("https://o/x.ts", HlsInspector.resolve("https://h/live/master.m3u8", "https://o/x.ts"))
    }

    companion object {
        private lateinit var server: EmbeddedMockServer

        @JvmStatic
        @BeforeClass
        fun start() {
            server = startEmbeddedMockServer()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            server.close()
        }
    }
}
