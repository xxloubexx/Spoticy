package com.spoticy.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun formatTime_zero() {
        assertEquals("00:00", formatTime(0))
    }

    @Test
    fun formatTime_seconds() {
        assertEquals("00:05", formatTime(5_000))
    }

    @Test
    fun formatTime_minutes() {
        assertEquals("01:01", formatTime(61_000))
    }

    @Test
    fun formatTime_negativeValue() {
        assertEquals("00:00", formatTime(-1_000))
    }
}
