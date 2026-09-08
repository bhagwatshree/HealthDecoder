package com.healthdecoder.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how a trend point's timestamp becomes its position on the chart's x-axis.
 *
 * This exists because dropping the clock time was a real, silent defect: two blood pressure
 * readings taken the same morning and evening landed on the identical x-coordinate and drew as a
 * single stacked dot, and a day on which every reading was logged collapsed to one distinct
 * timestamp — at which point the chart gave up on time-based spacing entirely.
 */
class TrendPointTimeTest {

    @Test
    fun `two readings on the same day at different times get different positions`() {
        val morning = isoToMillis("2026-09-06T08:15")
        val evening = isoToMillis("2026-09-06T20:30")

        assertNotEquals(morning, evening)
        assertTrue(morning!! < evening!!)
    }

    @Test
    fun `the gap between two same-day readings matches the real elapsed time`() {
        val eight = isoToMillis("2026-09-06T08:00")!!
        val eleven = isoToMillis("2026-09-06T11:00")!!

        assertEquals(3 * 60 * 60 * 1000L, eleven - eight)
    }

    @Test
    fun `a date-only lab report still resolves to midnight, exactly as before`() {
        // Lab points carry no time component (DashboardEngine truncates reportDate at the "T"),
        // so this change must be a strict no-op for every existing lab chart.
        val dateOnly = isoToMillis("2026-09-06")!!
        val explicitMidnight = isoToMillis("2026-09-06T00:00")!!

        assertEquals(explicitMidnight, dateOnly)
    }

    @Test
    fun `ordering across days is preserved`() {
        val earlierDayLate = isoToMillis("2026-09-05T23:00")!!
        val laterDayEarly = isoToMillis("2026-09-06T01:00")!!

        assertTrue(earlierDayLate < laterDayEarly)
    }

    @Test
    fun `a malformed timestamp yields null rather than throwing`() {
        // The chart filters these out; an exception here would take the whole screen down.
        assertNull(isoToMillis(""))
        assertNull(isoToMillis("not-a-date"))
        assertNull(isoToMillis("2026-09"))
    }

    @Test
    fun `a timestamp with seconds is tolerated`() {
        // Nothing writes this today, but an imported or future-format reading must not vanish.
        val withSeconds = isoToMillis("2026-09-06T08:15:42")
        val withoutSeconds = isoToMillis("2026-09-06T08:15")

        assertEquals(withoutSeconds, withSeconds)
    }
}
