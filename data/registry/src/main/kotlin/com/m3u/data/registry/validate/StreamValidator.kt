package com.m3u.data.registry.validate

import com.m3u.data.registry.plan.ValidationTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * Checks on the device that a stream url really plays, within a fixed time budget:
 * 1. GET the url with the channel's headers and require `#EXTM3U`;
 * 2. for a master playlist, pick the tallest variant at or below 1080p and GET it;
 * 3. require at least one segment in the media playlist;
 * 4. optionally read the first 64 KB of a recent segment (the third newest: the very newest may not
 *    have reached every CDN edge yet).
 *
 * Network-level failures (timeout, connection, DNS) are retried once with a fresh budget; an HTTP
 * error or a broken playlist is final.
 *
 * The validator runs from the viewer's own network, so geo-blocking is judged correctly; the
 * registry's CI only records hints.
 */
class StreamValidator(
    client: OkHttpClient,
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val probeSegment: Boolean = true,
    private val maxHeight: Int = 1080,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client = client.newBuilder()
        .callTimeout(budgetMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(budgetMillis, TimeUnit.MILLISECONDS)
        .readTimeout(budgetMillis, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun validateAll(
        targets: List<ValidationTarget>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, ValidationResult> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val done = AtomicInteger()
        val unique = targets.distinctBy { it.url }
        unique.map { target ->
            async {
                semaphore.withPermit {
                    val result = validate(target)
                    onProgress(done.incrementAndGet(), unique.size)
                    target.url to result
                }
            }
        }.awaitAll().toMap()
    }

    suspend fun validate(target: ValidationTarget): ValidationResult {
        val first = attempt(target)
        if (first.ok || first.failureReason !in RETRYABLE_REASONS) return first
        return attempt(target)
    }

    private suspend fun attempt(target: ValidationTarget): ValidationResult {
        val startedAt = clock()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
        var latency: Long? = null
        val result = withTimeoutOrNull(budgetMillis) {
            try {
                val first = get(target.url, target.headers, deadline)
                latency = first.latencyMs
                inspect(target, first, deadline, depth = 0)
            } catch (exception: IOException) {
                Outcome.Failure(describe(exception))
            }
        } ?: Outcome.Failure(REASON_TIMEOUT)
        return when (result) {
            is Outcome.Success -> ValidationResult(
                ok = true,
                checkedAt = startedAt,
                latencyMs = latency,
                height = result.height,
                bandwidth = result.bandwidth,
            )

            is Outcome.Failure -> ValidationResult(
                ok = false,
                checkedAt = startedAt,
                latencyMs = latency,
                failureReason = result.reason,
            )
        }
    }

    private sealed interface Outcome {
        data class Success(val height: Int?, val bandwidth: Long?) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    private class Fetched(
        val url: String,
        val code: Int,
        val contentType: String?,
        val body: ByteArray,
        val latencyMs: Long,
        // the body hit the read limit; its last line may be cut
        val truncated: Boolean,
    ) {
        val text: String
            get() = body.toString(Charsets.UTF_8).let { text ->
                if (truncated) text.substringBeforeLast('\n') else text
            }
    }

    private suspend fun inspect(
        target: ValidationTarget,
        fetched: Fetched,
        deadline: Long,
        depth: Int,
        variant: HlsInspector.Variant? = null,
    ): Outcome {
        if (fetched.code !in 200..299) return Outcome.Failure("HTTP ${fetched.code}")
        return when (val playlist = HlsInspector.parse(fetched.text)) {
            HlsInspector.Playlist.NotHls -> if (fetched.looksLikeMedia()) {
                Outcome.Success(height = null, bandwidth = null)
            } else {
                Outcome.Failure(REASON_NOT_HLS)
            }

            is HlsInspector.Playlist.Master -> {
                if (depth > 1) return Outcome.Failure(REASON_NESTED)
                val chosen = HlsInspector.chooseVariant(playlist.variants, maxHeight)
                    ?: return Outcome.Failure(REASON_NO_VARIANTS)
                val variantUrl = HlsInspector.resolve(fetched.url, chosen.uri)
                inspect(target, get(variantUrl, target.headers, deadline), deadline, depth + 1, chosen)
            }

            is HlsInspector.Playlist.Media -> {
                val segment = playlist.segments.getOrNull((playlist.segments.size - PROBE_FROM_END).coerceAtLeast(0))
                    ?: return Outcome.Failure(REASON_EMPTY)
                if (probeSegment) {
                    val probe = get(
                        url = HlsInspector.resolve(fetched.url, segment),
                        headers = target.headers + ("Range" to "bytes=0-${SEGMENT_PROBE_BYTES - 1}"),
                        deadline = deadline,
                        limit = SEGMENT_PROBE_BYTES,
                    )
                    if (probe.code !in 200..299) return Outcome.Failure("segment HTTP ${probe.code}")
                    if (probe.body.isEmpty()) return Outcome.Failure(REASON_EMPTY_SEGMENT)
                }
                Outcome.Success(height = variant?.height, bandwidth = variant?.bandwidth)
            }
        }
    }

    private suspend fun get(
        url: String,
        headers: Map<String, String>,
        deadline: Long,
        limit: Int = PLAYLIST_LIMIT_BYTES,
    ): Fetched = runInterruptible(Dispatchers.IO) {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remaining <= 0) throw InterruptedIOException(REASON_TIMEOUT)
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val call = client.newCall(request)
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
        val started = System.nanoTime()
        call.execute().use { response ->
            val latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            val output = ByteArrayOutputStream()
            var truncated = false
            response.body.byteStream().use { input ->
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size() >= limit) {
                        truncated = true
                        break
                    }
                }
            }
            Fetched(
                url = response.request.url.toString(),
                code = response.code,
                contentType = response.header("Content-Type"),
                body = output.toByteArray(),
                latencyMs = latency,
                truncated = truncated,
            )
        }
    }

    // a progressive MPEG-TS or MP4 stream instead of a playlist
    private fun Fetched.looksLikeMedia(): Boolean {
        val type = contentType.orEmpty().lowercase()
        if (type.startsWith("video/") && !type.contains("mpegurl")) return true
        return body.size > 188 && body[0] == 0x47.toByte() && body[188] == 0x47.toByte()
    }

    private fun describe(exception: IOException): String = when (exception) {
        is UnknownHostException -> REASON_DNS
        is InterruptedIOException -> REASON_TIMEOUT
        is SSLException -> "tls"
        is ConnectException -> REASON_CONNECTION
        else -> exception.message?.take(80) ?: exception::class.simpleName.orEmpty()
    }

    companion object {
        const val DEFAULT_BUDGET_MILLIS = 6_000L
        const val DEFAULT_CONCURRENCY = 4
        const val SEGMENT_PROBE_BYTES = 64 * 1024

        // a 24 h DVR playlist (TRT) is about 1 MB
        private const val PLAYLIST_LIMIT_BYTES = 2 * 1024 * 1024
        private const val READ_BUFFER_BYTES = 16 * 1024
        private const val PROBE_FROM_END = 3

        const val REASON_TIMEOUT = "timeout"
        const val REASON_NOT_HLS = "not a playlist"
        const val REASON_EMPTY = "empty playlist"
        const val REASON_EMPTY_SEGMENT = "empty segment"
        const val REASON_NO_VARIANTS = "no variants"
        const val REASON_NESTED = "nested master playlists"
        const val REASON_CONNECTION = "connection failed"
        const val REASON_DNS = "dns"
        private val RETRYABLE_REASONS = setOf(REASON_TIMEOUT, REASON_CONNECTION, REASON_DNS)
    }
}
