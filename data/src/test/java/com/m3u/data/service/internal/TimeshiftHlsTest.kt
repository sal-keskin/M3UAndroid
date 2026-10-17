package com.m3u.data.service.internal

import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimeshiftHlsTest {
    private val sixSecondsUs = 6_000_000L

    private fun segment(sequence: Long, relativeStartUs: Long, discontinuity: Int = 0) = HlsMediaPlaylist.Segment(
        "seg-$sequence.ts",
        null,
        "",
        sixSecondsUs,
        discontinuity,
        relativeStartUs,
        null,
        null,
        null,
        0L,
        C.LENGTH_UNSET.toLong(),
        false,
        emptyList(),
    )

    private fun playlist(
        mediaSequence: Long,
        count: Int,
        baseUri: String = "https://cdn.example/live/index.m3u8",
        startTimeUs: Long = 0L,
        discontinuitySequence: Int = 0,
        discontinuities: Map<Long, Int> = emptyMap(),
        hasEndTag: Boolean = false,
    ) = HlsMediaPlaylist(
        HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN,
        baseUri,
        emptyList(),
        C.TIME_UNSET,
        false,
        startTimeUs,
        true,
        discontinuitySequence,
        mediaSequence,
        3,
        sixSecondsUs,
        C.TIME_UNSET,
        false,
        hasEndTag,
        false,
        null,
        (0 until count).map { index ->
            val sequence = mediaSequence + index
            segment(sequence, index * sixSecondsUs, discontinuities[sequence] ?: 0)
        },
        emptyList(),
        HlsMediaPlaylist.ServerControl(C.TIME_UNSET, false, C.TIME_UNSET, C.TIME_UNSET, false),
        emptyMap(),
        emptyList(),
    )

    @Test
    fun `segments that slid out of the live window stay seekable`() {
        val previous = playlist(mediaSequence = 100, count = 4, startTimeUs = 1_000L)
        val current = playlist(mediaSequence = 103, count = 4)

        val merged = mergeLiveWindow(previous, current, maxWindowUs = 600_000_000L)

        assertEquals(100L, merged.mediaSequence)
        assertEquals(7, merged.segments.size)
        assertEquals(
            (0 until 7).map { it * sixSecondsUs },
            merged.segments.map { it.relativeStartTimeUs },
        )
        assertEquals("https://cdn.example/live/seg-100.ts", merged.segments.first().url)
        assertEquals("https://cdn.example/live/seg-106.ts", merged.segments.last().url)
        assertEquals(1_000L, merged.startTimeUs)
        assertEquals(7 * sixSecondsUs, merged.durationUs)
        assertTrue(merged.isNewerThan(previous))
    }

    @Test
    fun `old segments keep resolving against their own playlist url`() {
        val previous = playlist(mediaSequence = 100, count = 3, baseUri = "https://a.example/old/index.m3u8")
        val current = playlist(mediaSequence = 102, count = 3, baseUri = "https://b.example/new/index.m3u8")
        val merged = mergeLiveWindow(previous, current, maxWindowUs = 600_000_000L)
        assertEquals("https://a.example/old/seg-100.ts", merged.segments[0].url)
        assertEquals("https://b.example/new/seg-102.ts", merged.segments[2].url)
    }

    @Test
    fun `window is trimmed and discontinuities keep one counter`() {
        // previous merged window 100..119 with a discontinuity before 110 (absolute sequence 6 → 7)
        val previous = playlist(
            mediaSequence = 100,
            count = 20,
            startTimeUs = 0L,
            discontinuitySequence = 6,
            discontinuities = (110L..119L).associateWith { 1 },
        )
        val current = playlist(mediaSequence = 118, count = 4, discontinuitySequence = 7)

        val merged = mergeLiveWindow(previous, current, maxWindowUs = 60_000_000L)

        assertEquals(112L, merged.mediaSequence)
        assertEquals(10, merged.segments.size)
        assertEquals(7, merged.discontinuitySequence)
        assertTrue(merged.segments.all { it.relativeDiscontinuitySequence == 0 })
        assertEquals(12 * sixSecondsUs, merged.startTimeUs)
        assertEquals(0L, merged.segments.first().relativeStartTimeUs)
    }

    @Test
    fun `finished or reset playlists are not merged`() {
        val previous = playlist(mediaSequence = 100, count = 4)
        val ended = playlist(mediaSequence = 103, count = 4, hasEndTag = true)
        assertSame(ended, mergeLiveWindow(previous, ended, maxWindowUs = 600_000_000L))
        val reset = playlist(mediaSequence = 5, count = 4)
        assertSame(reset, mergeLiveWindow(previous, reset, maxWindowUs = 600_000_000L))
    }
}
