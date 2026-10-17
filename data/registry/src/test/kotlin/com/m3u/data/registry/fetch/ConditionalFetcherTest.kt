package com.m3u.data.registry.fetch

import com.m3u.testing.mockserver.startEmbeddedMockServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConditionalFetcherTest {
    private val directory = Files.createTempDirectory("conditional-fetcher").toFile()

    @Test
    fun `etag revalidation returns the cached body`() = runBlocking {
        startEmbeddedMockServer().use { server ->
            val fetcher = ConditionalFetcher(OkHttpClient(), directory)
            val url = "${server.baseUrl}/linkcheck/etag.json"

            val first = fetcher.fetch(url)
            assertIs<ConditionalFetcher.FetchResult.Fresh>(first)
            val second = fetcher.fetch(url)
            assertIs<ConditionalFetcher.FetchResult.NotModified>(second)
            assertEquals(first.file.readText(), second.file?.readText())
        }
    }

    @Test
    fun `failures fall back to the cached body only when there is one`() = runBlocking {
        val fetcher = ConditionalFetcher(OkHttpClient(), directory, timeoutMillis = 1_000)
        val closedPort = ServerSocket(0).use { it.localPort }
        assertIs<ConditionalFetcher.FetchResult.Failed>(fetcher.fetch("http://127.0.0.1:$closedPort/sources.json"))

        val server = startEmbeddedMockServer()
        val url = "${server.baseUrl}/linkcheck/etag.json"
        val fresh = fetcher.fetch(url)
        assertIs<ConditionalFetcher.FetchResult.Fresh>(fresh)
        assertIs<ConditionalFetcher.FetchResult.Failed>(fetcher.fetch("${server.baseUrl}/linkcheck/forbidden.m3u8"))
        server.close()

        val stale = fetcher.fetch(url)
        assertIs<ConditionalFetcher.FetchResult.Stale>(stale)
        assertEquals(fresh.file.readText(), stale.file.readText())
    }
}
