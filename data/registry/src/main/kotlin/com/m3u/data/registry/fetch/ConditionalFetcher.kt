package com.m3u.data.registry.fetch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads registry and source documents with ETag / If-None-Match (and Last-Modified)
 * revalidation. Bodies are streamed to disk so a 4 MB api response never sits in memory.
 *
 * A failed request falls back to the previously cached body when there is one, so a flaky source
 * never empties the candidate list; [FetchResult.Failed] is only returned when nothing is cached.
 */
class ConditionalFetcher(
    client: OkHttpClient,
    private val cacheDirectory: File,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val client = client.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    sealed interface FetchResult {
        // the body to read, or null when nothing could be fetched or cached
        val file: File?

        // a fresh 200 body was stored
        data class Fresh(override val file: File) : FetchResult

        // 304: the cached body is still current
        data class NotModified(override val file: File) : FetchResult

        // the request failed but an older body is available
        data class Stale(override val file: File, val error: String) : FetchResult

        data class Failed(val error: String) : FetchResult {
            override val file: File? get() = null
        }
    }

    suspend fun fetch(url: String): FetchResult = runInterruptible(Dispatchers.IO) {
        cacheDirectory.mkdirs()
        val key = sha1(url)
        val body = File(cacheDirectory, "$key.body")
        val meta = File(cacheDirectory, "$key.meta")
        val cached = body.takeIf(File::isFile)?.let { readMeta(meta) }
        val request = Request.Builder()
            .url(url)
            .apply {
                if (cached != null) {
                    cached.etag?.let { header("If-None-Match", it) }
                    cached.lastModified?.let { header("If-Modified-Since", it) }
                }
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 && cached != null -> FetchResult.NotModified(body)
                    response.isSuccessful -> {
                        val temp = File(cacheDirectory, "$key.tmp")
                        response.body.byteStream().use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (!temp.renameTo(body)) {
                            temp.copyTo(body, overwrite = true)
                            temp.delete()
                        }
                        writeMeta(
                            meta,
                            CachedMeta(
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                            )
                        )
                        FetchResult.Fresh(body)
                    }

                    cached != null -> FetchResult.Stale(body, "HTTP ${response.code}")
                    else -> FetchResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (exception: IOException) {
            val error = exception.message ?: exception::class.simpleName.orEmpty()
            if (cached != null) FetchResult.Stale(body, error) else FetchResult.Failed(error)
        }
    }

    /**
     * The last body stored for [url], without any network request.
     */
    fun cachedFile(url: String): File? = File(cacheDirectory, "${sha1(url)}.body").takeIf(File::isFile)

    private data class CachedMeta(
        val etag: String?,
        val lastModified: String?,
    )

    private fun readMeta(file: File): CachedMeta = runCatching {
        val lines = file.readLines()
        CachedMeta(
            etag = lines.getOrNull(0)?.takeIf(String::isNotBlank),
            lastModified = lines.getOrNull(1)?.takeIf(String::isNotBlank),
        )
    }.getOrDefault(CachedMeta(null, null))

    private fun writeMeta(file: File, meta: CachedMeta) {
        file.writeText(listOf(meta.etag.orEmpty(), meta.lastModified.orEmpty()).joinToString("\n"))
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
