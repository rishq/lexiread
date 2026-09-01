package com.lexiread.core.util

import com.lexiread.core.util.SrsScheduler.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SM-2 reference:
 * https://www.supermemo.com/en/blog/application-of-a-spaced-repetition-algorithm
 */
class SrsSchedulerTest {

    private val dayMs = 24 * 60 * 60 * 1000L
    private val now = 1_700_000_000_000L

    // --- intervals ----------------------------------------------------------

    @Test
    fun `first successful review schedules one day ahead`() {
        val next = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 4, now = now)

        assertEquals(1, next.reps)
        assertEquals(1, next.intervalDays)
        assertEquals(now + dayMs, next.nextReviewEpoch)
        assertEquals(now, next.lastReviewEpoch)
    }

    @Test
    fun `second successful review schedules six days ahead`() {
        val afterFirst = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 4, now = now)
        val afterSecond = SrsScheduler.nextState(afterFirst, quality = 4, now = now)

        assertEquals(2, afterSecond.reps)
        assertEquals(6, afterSecond.intervalDays)
    }

    @Test
    fun `third review multiplies the previous interval by the ease factor`() {
        val afterFirst = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 4, now = now)
        val afterSecond = SrsScheduler.nextState(afterFirst, quality = 4, now = now)
        val afterThird = SrsScheduler.nextState(afterSecond, quality = 4, now = now)

        // EF stays 2.5 for q=4: 2.5 + (0.1 - (5-4)*(0.08 + (5-4)*0.02)) = 2.5 + 0.0
        // Interval: 6 * 2.5 = 15
        assertEquals(3, afterThird.reps)
        assertEquals(2.5, afterThird.ease, 1e-9)
        assertEquals(15, afterThird.intervalDays)
    }

    @Test
    fun `failed recall resets repetitions and interval`() {
        val afterFirst = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 5, now = now)
        val afterFail = SrsScheduler.nextState(afterFirst, quality = 1, now = now)

        assertEquals(0, afterFail.reps)
        assertEquals(1, afterFail.intervalDays)
        // EF' = 2.6 + (0.1 - (5-1)*(0.08 + (5-1)*0.02)) = 2.6 + (0.1 - 0.64) = 2.6 - 0.54 = 2.06
        assertEquals(2.06, afterFail.ease, 1e-9)
    }

    // --- ease factor ---------------------------------------------------------

    @Test
    fun `perfect recall raises the ease factor`() {
        val next = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 5, now = now)

        assertEquals(2.6, next.ease, 1e-9)
    }

    @Test
    fun `ease never drops below the 1,3 floor`() {
        var state = SrsScheduler.SrsState()
        repeat(20) { state = SrsScheduler.nextState(state, quality = 0, now = now) }

        assertEquals(1.3, state.ease, 1e-9)
    }

    @Test
    fun `quality is clamped into the 0-5 range`() {
        val next = SrsScheduler.nextState(SrsScheduler.SrsState(), quality = 42, now = now)

        // q=5 after clamping: ease 2.6, interval 1.
        assertEquals(2.6, next.ease, 1e-9)
        assertEquals(1, next.intervalDays)
    }

    // --- convenience rating mapping -----------------------------------------

    @Test
    fun `AGAIN maps to a failed repetition`() {
        val next = SrsScheduler.review(SrsScheduler.SrsState(), ReviewRating.AGAIN, now)

        assertEquals(0, next.reps)
        // AGAIN -> q=1: EF' = 2.5 + (0.1 - (5-1)*(0.08 + (5-1)*0.02)) = 2.5 - 0.54 = 1.96
        assertEquals(1.96, next.ease, 1e-9)
    }

    @Test
    fun `HARD is a pass but keeps the ease constant`() {
        val next = SrsScheduler.review(SrsScheduler.SrsState(), ReviewRating.HARD, now)

        assertEquals(1, next.reps)
        // HARD -> q=3: EF' = 2.5 + (0.1 - (5-3)*(0.08 + (5-3)*0.02)) = 2.5 - 0.14 = 2.36
        assertEquals(2.36, next.ease, 1e-9)
        assertEquals(1, next.intervalDays)
    }

    // --- initial state & due -------------------------------------------------

    @Test
    fun `a freshly saved word is due immediately`() {
        val state = SrsScheduler.initialState(now)

        assertEquals(0, state.reps)
        assertEquals(0, state.intervalDays)
        assertEquals(now, state.nextReviewEpoch)
        assertTrue(state.isDueAt(now))
    }

    @Test
    fun `a word scheduled in the future is not due`() {
        val state = SrsScheduler.SrsState(nextReviewEpoch = now + dayMs)

        assertTrue(!state.isDueAt(now))
    }
}
