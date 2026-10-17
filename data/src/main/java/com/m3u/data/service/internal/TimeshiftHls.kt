package com.m3u.data.service.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.m3u.data.registry.timeshift.LiveWindowMerger
import com.m3u.data.registry.validate.HlsInspector
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Timeshift for live HLS: keeps up to [maxWindowUs] of already-published segments listed in the
 * media playlist so the viewer can pause and go back a little.
 *
 * The previous playlist snapshot handed over by the playlist tracker is already merged, so the
 * merge is stateless. Low-latency, event, vod and interstitial playlists are left untouched.
 */
internal class TimeshiftHlsPlaylistParserFactory(
    private val maxWindowUs: Long = DEFAULT_WINDOW_US,
) : HlsPlaylistParserFactory {
    private val delegate = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        delegate.createPlaylistParser()

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
        return ParsingLoadable.Parser { uri, input ->
            val parsed = parser.parse(uri, input)
            if (parsed is HlsMediaPlaylist && previousMediaPlaylist != null) {
                mergeLiveWindow(previousMediaPlaylist, parsed, maxWindowUs)
            } else {
                parsed
            }
        }
    }

    companion object {
        val DEFAULT_WINDOW_US: Long = TimeUnit.MINUTES.toMicros(10)
    }
}

internal fun mergeLiveWindow(
    previous: HlsMediaPlaylist,
    current: HlsMediaPlaylist,
    maxWindowUs: Long,
): HlsMediaPlaylist {
    // the server already offers a long DVR window (TRT keeps 24 h): nothing to add
    if (current.durationUs >= maxWindowUs) return current
    val mergeable = !current.hasEndTag &&
        !previous.hasEndTag &&
        current.playlistType == HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN &&
        current.partTargetDurationUs == C.TIME_UNSET &&
        current.interstitials.isEmpty()
    if (!mergeable) return current
    val merged = LiveWindowMerger.merge(
        previous = LiveWindowMerger.Window(previous.mediaSequence, previous.segments),
        current = LiveWindowMerger.Window(current.mediaSequence, current.segments),
        maxDurationUs = maxWindowUs,
        durationUs = { it.durationUs },
    ) ?: return current

    val retained = merged.segments.subList(0, merged.retained)
    val currentFirst = current.segments.first()
    val retainedDurationUs = retained.sumOf { it.durationUs }

    // absolute discontinuity numbers keep both halves on one counter
    val previousFirstIndex = (merged.mediaSequence - previous.mediaSequence).toInt()
    val absoluteBase = previous.discontinuitySequence + retained.first().relativeDiscontinuitySequence
    val overlap = (current.mediaSequence - previous.mediaSequence).toInt()
    val currentAbsoluteFirst = when {
        current.hasDiscontinuitySequence -> current.discontinuitySequence + currentFirst.relativeDiscontinuitySequence
        overlap < previous.segments.size ->
            previous.discontinuitySequence + previous.segments[overlap].relativeDiscontinuitySequence
        else -> previous.discontinuitySequence + previous.segments.last().relativeDiscontinuitySequence
    }
    val baseStartUs = previous.segments[previousFirstIndex].relativeStartTimeUs

    val segments = ArrayList<HlsMediaPlaylist.Segment>(merged.segments.size)
    retained.forEach { segment ->
        segments += segment.absolute(
            baseUri = previous.baseUri,
            relativeStartTimeUs = segment.relativeStartTimeUs - baseStartUs,
            relativeDiscontinuitySequence = previous.discontinuitySequence +
                segment.relativeDiscontinuitySequence - absoluteBase,
        )
    }
    current.segments.forEach { segment ->
        segments += segment.absolute(
            baseUri = current.baseUri,
            relativeStartTimeUs = retainedDurationUs + segment.relativeStartTimeUs - currentFirst.relativeStartTimeUs,
            relativeDiscontinuitySequence = currentAbsoluteFirst +
                segment.relativeDiscontinuitySequence - currentFirst.relativeDiscontinuitySequence - absoluteBase,
        )
    }
    val startTimeUs = if (current.hasProgramDateTime) {
        current.startTimeUs - retainedDurationUs
    } else {
        previous.startTimeUs + baseStartUs
    }
    val startOffsetUs = if (current.startOffsetUs != C.TIME_UNSET && current.startOffsetUs > 0) {
        current.startOffsetUs + retainedDurationUs
    } else {
        current.startOffsetUs
    }
    return HlsMediaPlaylist(
        current.playlistType,
        current.baseUri,
        current.tags,
        startOffsetUs,
        current.preciseStart,
        startTimeUs,
        /* hasDiscontinuitySequence = */ true,
        absoluteBase,
        merged.mediaSequence,
        current.version,
        current.targetDurationUs,
        current.partTargetDurationUs,
        current.hasIndependentSegments,
        current.hasEndTag,
        current.hasProgramDateTime,
        current.protectionSchemes,
        segments,
        current.trailingParts,
        current.serverControl,
        current.renditionReports,
        current.interstitials,
    )
}

private fun HlsMediaPlaylist.Segment.absolute(
    baseUri: String,
    relativeStartTimeUs: Long,
    relativeDiscontinuitySequence: Int,
): HlsMediaPlaylist.Segment = HlsMediaPlaylist.Segment(
    resolveSegmentUri(baseUri, url),
    initializationSegment?.let { init ->
        HlsMediaPlaylist.Segment(
            resolveSegmentUri(baseUri, init.url),
            null,
            init.title,
            init.durationUs,
            init.relativeDiscontinuitySequence,
            init.relativeStartTimeUs,
            init.drmInitData,
            init.fullSegmentEncryptionKeyUri?.let { resolveSegmentUri(baseUri, it) },
            init.encryptionIV,
            init.byteRangeOffset,
            init.byteRangeLength,
            init.hasGapTag,
            init.parts,
        )
    },
    title,
    durationUs,
    relativeDiscontinuitySequence,
    relativeStartTimeUs,
    drmInitData,
    fullSegmentEncryptionKeyUri?.let { resolveSegmentUri(baseUri, it) },
    encryptionIV,
    byteRangeOffset,
    byteRangeLength,
    hasGapTag,
    parts,
)

// java.net.URI first (pure JVM); Media3's RFC 3986 resolver for urls URI rejects
private fun resolveSegmentUri(baseUri: String, reference: String): String {
    val resolved = HlsInspector.resolve(baseUri, reference)
    return if (resolved.contains("://")) resolved else UriUtil.resolve(baseUri, reference)
}

/**
 * Serves media segments through the on-disk cache so going back replays what was already
 * downloaded; playlists always go to the network (a cached live playlist would freeze playback).
 */
internal class TimeshiftDataSourceFactory(
    private val upstream: DataSource.Factory,
    cache: Cache,
) : DataSource.Factory {
    private val cached = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    override fun createDataSource(): DataSource =
        SegmentRoutingDataSource(upstream.createDataSource(), cached.createDataSource())
}

private class SegmentRoutingDataSource(
    private val direct: DataSource,
    private val cached: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        direct.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.isMediaSegment()) cached else direct
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    private fun Uri.isMediaSegment(): Boolean {
        val extension = lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return false
        return extension in SEGMENT_EXTENSIONS
    }

    private companion object {
        val SEGMENT_EXTENSIONS = setOf("ts", "aac", "m4s", "mp4", "m4a", "m4v", "cmfv", "cmfa", "mp3", "ac3", "ec3")
    }
}
