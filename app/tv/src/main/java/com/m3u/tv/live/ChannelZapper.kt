package com.m3u.tv.live

/**
 * Remote-control channel switching for the ranked live lineup, free of Android and Compose.
 *
 * - [step]: ▲/▼ and CH+/CH− move through channel numbers, wrapping at both ends.
 * - [typeDigit]: number keys build a channel number; it is final once no longer number can start
 *   with the typed digits, otherwise the caller commits it after a short pause.
 * - [back]: Back returns to the channel watched before, then the one before that, and so on.
 */
class ChannelZapper {
    data class Entry(
        val number: Int,
        val channelId: Int,
    )

    data class NumberInput(
        val digits: String,
        // the channel the digits name, if any
        val match: Entry?,
        // no further digit can change the result: commit now
        val final: Boolean,
    ) {
        val number: Int get() = digits.toInt()
    }

    private var lineup: List<Entry> = emptyList()
    private val history = ArrayDeque<Int>()
    var current: Int? = null
        private set

    fun setLineup(entries: List<Entry>) {
        lineup = entries.sortedBy { it.number }
        val known = lineup.mapTo(hashSetOf()) { it.channelId }
        history.retainAll { it in known }
    }

    /**
     * Records that [channelId] started playing. The previous channel is remembered for [back]
     * unless the switch itself came from [back].
     */
    fun onPlayed(channelId: Int, fromBack: Boolean = false) {
        val previous = current
        if (!fromBack && previous != null && previous != channelId) {
            history.addLast(previous)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        current = channelId
    }

    fun clear() {
        history.clear()
        current = null
    }

    /**
     * The channel [offset] numbers away from the current one, wrapping around the lineup.
     */
    fun step(offset: Int): Entry? {
        if (lineup.isEmpty()) return null
        val index = lineup.indexOfFirst { it.channelId == current }
        if (index < 0) return if (offset >= 0) lineup.first() else lineup.last()
        val size = lineup.size
        return lineup[((index + offset) % size + size) % size]
    }

    /**
     * Pops the previously watched channel, or null when Back should leave the player.
     */
    fun back(): Entry? {
        while (history.isNotEmpty()) {
            val channelId = history.removeLast()
            lineup.firstOrNull { it.channelId == channelId }?.let { return it }
        }
        return null
    }

    fun typeDigit(previous: String, digit: Int): NumberInput {
        require(digit in 0..9) { "digit $digit" }
        val digits = (previous + digit).trimStart('0').ifEmpty { "0" }.takeLast(MAX_DIGITS)
        val number = digits.toInt()
        val match = lineup.firstOrNull { it.number == number }
        val maxNumber = lineup.maxOfOrNull { it.number } ?: 0
        val canGrow = digits.length < MAX_DIGITS && number != 0 && number * 10 <= maxNumber
        return NumberInput(digits = digits, match = match, final = !canGrow)
    }

    fun entryOf(channelId: Int): Entry? = lineup.firstOrNull { it.channelId == channelId }

    companion object {
        const val MAX_DIGITS = 3
        const val NUMBER_COMMIT_DELAY_MILLIS = 1_500L
        private const val MAX_HISTORY = 20
    }
}
