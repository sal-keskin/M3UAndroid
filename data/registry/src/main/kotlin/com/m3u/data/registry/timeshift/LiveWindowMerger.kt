package com.m3u.data.registry.timeshift

/**
 * Grows a live HLS window so the viewer can pause and go back a little.
 *
 * A live media playlist only lists the newest few segments, so a player can only seek inside that
 * short window. Each time the playlist is reloaded, this merger keeps the segments that dropped off
 * the front of the previous (already merged) window, up to [maxDurationUs] in total.
 *
 * It is pure: segments are opaque, only their media sequence numbers and durations matter.
 */
object LiveWindowMerger {
    data class Window<S>(
        val mediaSequence: Long,
        val segments: List<S>,
    )

    data class Merged<S>(
        val mediaSequence: Long,
        val segments: List<S>,
        // leading segments taken from the previous window; the rest come from the current one
        val retained: Int,
    )

    /**
     * @return the merged window, or null when [current] should be used as it is: no history to add,
     * a gap (segments were missed), or a media sequence reset.
     */
    fun <S> merge(
        previous: Window<S>,
        current: Window<S>,
        maxDurationUs: Long,
        durationUs: (S) -> Long,
    ): Merged<S>? {
        if (previous.segments.isEmpty() || current.segments.isEmpty()) return null
        val offset = current.mediaSequence - previous.mediaSequence
        if (offset <= 0L || offset > previous.segments.size) return null
        val history = previous.segments.subList(0, offset.toInt())
        var total = (history + current.segments).sumOf(durationUs)
        var dropped = 0
        while (dropped < history.size && total > maxDurationUs) {
            total -= durationUs(history[dropped])
            dropped++
        }
        if (dropped == history.size) return null
        return Merged(
            mediaSequence = previous.mediaSequence + dropped,
            segments = history.subList(dropped, history.size) + current.segments,
            retained = history.size - dropped,
        )
    }
}
