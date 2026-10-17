package com.m3u.data.registry.timeshift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveWindowMergerTest {
    // segment = its media sequence number; every segment lasts 6 s
    private fun window(first: Long, count: Int) = LiveWindowMerger.Window(first, (first until first + count).toList())
    private val sixSeconds: (Long) -> Long = { 6_000_000L }

    @Test
    fun `segments that slid out are kept in front`() {
        val merged = LiveWindowMerger.merge(window(100, 4), window(102, 4), maxDurationUs = 600_000_000L, sixSeconds)!!
        assertEquals(100L, merged.mediaSequence)
        assertEquals((100L..105L).toList(), merged.segments)
        assertEquals(2, merged.retained)
    }

    @Test
    fun `history is trimmed to the maximum duration`() {
        // previous merged window holds 100..119 (120 s); allow 60 s in total
        val merged = LiveWindowMerger.merge(window(100, 20), window(118, 4), maxDurationUs = 60_000_000L, sixSeconds)!!
        assertEquals((112L..121L).toList(), merged.segments)
        assertEquals(112L, merged.mediaSequence)
        assertEquals(6, merged.retained)
    }

    @Test
    fun `contiguous windows without overlap still merge`() {
        val merged = LiveWindowMerger.merge(window(100, 3), window(103, 3), maxDurationUs = 600_000_000L, sixSeconds)!!
        assertEquals((100L..105L).toList(), merged.segments)
    }

    @Test
    fun `gaps resets and unchanged windows use the current playlist`() {
        assertNull(LiveWindowMerger.merge(window(100, 3), window(110, 3), 600_000_000L, sixSeconds))
        assertNull(LiveWindowMerger.merge(window(100, 3), window(50, 3), 600_000_000L, sixSeconds))
        assertNull(LiveWindowMerger.merge(window(100, 3), window(100, 4), 600_000_000L, sixSeconds))
        assertNull(LiveWindowMerger.merge(window(100, 0), window(101, 3), 600_000_000L, sixSeconds))
    }

    @Test
    fun `nothing is kept when the current window alone fills the budget`() {
        assertNull(LiveWindowMerger.merge(window(100, 6), window(102, 6), maxDurationUs = 30_000_000L, sixSeconds))
    }
}
