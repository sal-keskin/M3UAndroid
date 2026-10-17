package com.m3u.tv.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelZapperTest {
    // channel numbers 1..38 with a gap at 24 (disabled channel); channel id = number * 10
    private val zapper = ChannelZapper().apply {
        setLineup((1..38).filter { it != 24 }.map { ChannelZapper.Entry(number = it, channelId = it * 10) })
    }

    @Test
    fun `up and down wrap around the lineup`() {
        zapper.onPlayed(10)
        assertEquals(2, zapper.step(+1)?.number)
        assertEquals(38, zapper.step(-1)?.number)
        zapper.onPlayed(380)
        assertEquals(1, zapper.step(+1)?.number)
        zapper.onPlayed(230)
        assertEquals(25, zapper.step(+1)?.number)
    }

    @Test
    fun `back walks the history and then leaves`() {
        zapper.onPlayed(10)
        zapper.onPlayed(20)
        zapper.onPlayed(50)
        val previous = zapper.back()
        assertEquals(2, previous?.number)
        zapper.onPlayed(previous!!.channelId, fromBack = true)
        assertEquals(1, zapper.back()?.number)
        zapper.onPlayed(10, fromBack = true)
        assertNull(zapper.back())
    }

    @Test
    fun `number entry commits early when no longer number exists`() {
        val four = zapper.typeDigit("", 4)
        assertEquals(4, four.match?.number)
        assertTrue(four.final)

        val one = zapper.typeDigit("", 1)
        assertEquals(1, one.match?.number)
        assertFalse(one.final)
        val twelve = zapper.typeDigit(one.digits, 2)
        assertEquals(12, twelve.match?.number)
        assertTrue(twelve.final)

        val three = zapper.typeDigit("", 3)
        assertFalse(three.final)
        val thirtyNine = zapper.typeDigit(three.digits, 9)
        assertNull(thirtyNine.match)
        assertTrue(thirtyNine.final)
    }

    @Test
    fun `disabled channel numbers resolve to nothing`() {
        val two = zapper.typeDigit("", 2)
        val twentyFour = zapper.typeDigit(two.digits, 4)
        assertNull(twentyFour.match)
        assertEquals(24, twentyFour.number)
    }

    @Test
    fun `history forgets channels that left the lineup`() {
        zapper.onPlayed(10)
        zapper.onPlayed(20)
        zapper.setLineup(listOf(ChannelZapper.Entry(2, 20), ChannelZapper.Entry(3, 30)))
        assertNull(zapper.back())
    }
}
