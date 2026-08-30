package com.lexiread.core.util

import kotlin.math.max

/**
 * SM-2 spaced repetition algorithm (SuperMemo 2).
 *
 * Based on the original specification by Piotr Wozniak:
 * https://www.supermemo.com/en/blog/application-of-a-spaced-repetition-algorithm
 *
 * Quality is a 0-5 rating of the recall:
 * 5 = perfect, 4 = correct with hesitation, 3 = correct with difficulty,
 * 2 = incorrect but easy to recall, 1 = incorrect, some familiarity, 0 = complete blackout.
 */
object SrsScheduler {

    /** Minimum ease factor — never drops below 1.3 (SM-2 spec). */
    private const val MIN_EASE = 1.3
    private const val INITIAL_EASE = 2.5

    data class SrsState(
        val reps: Int = 0,
        val ease: Double = INITIAL_EASE,
        val intervalDays: Int = 0,
        val lastReviewEpoch: Long = 0,
        val nextReviewEpoch: Long = 0
    ) {
        val isDue: Boolean
            get() = nextReviewEpoch == 0L || System.currentTimeMillis() >= nextReviewEpoch
    }

    /**
     * Computes the next SRS state after a review with the given quality.
     *
     * SM-2 formula:
     * - If quality >= 3: increase reps, compute new interval, update ease.
     * - If quality < 3: reset reps to 0, interval to 1 (relearn from scratch).
     * - Ease is always updated, but never below [MIN_EASE].
     */
    fun nextState(current: SrsState, quality: Int, now: Long = System.currentTimeMillis()): SrsState {
        val q = quality.coerceIn(0, 5)

        // Update ease factor: EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        val newEase = max(
            MIN_EASE,
            current.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        )

        val newReps: Int
        val newInterval: Int

        if (q >= 3) {
            newReps = current.reps + 1
            newInterval = when (newReps) {
                1 -> 1
                2 -> 6
                else -> ((current.intervalDays * newEase)).toInt().coerceAtLeast(1)
            }
        } else {
            // Failed recall: restart the repetition schedule.
            newReps = 0
            newInterval = 1
        }

        val nextReview = now + newInterval.toLong() * 24 * 60 * 60 * 1000L

        return current.copy(
            reps = newReps,
            ease = newEase,
            intervalDays = newInterval,
            lastReviewEpoch = now,
            nextReviewEpoch = nextReview
        )
    }

    /** Convenience: review with a simple "again"/"good"/"easy" rating. */
    fun review(current: SrsState, rating: ReviewRating, now: Long = System.currentTimeMillis()): SrsState {
        val quality = when (rating) {
            ReviewRating.AGAIN -> 1   // Failed, see it soon
            ReviewRating.HARD -> 3   // Got it with difficulty
            ReviewRating.GOOD -> 4   // Correct with minor hesitation
            ReviewRating.EASY -> 5   // Perfect recall
        }
        return nextState(current, quality, now)
    }

    /** Returns the initial state for a newly saved word. */
    fun initialState(now: Long = System.currentTimeMillis()): SrsState = SrsState(
        reps = 0,
        ease = INITIAL_EASE,
        intervalDays = 0,
        lastReviewEpoch = 0,
        nextReviewEpoch = now // Due immediately
    )

    enum class ReviewRating { AGAIN, HARD, GOOD, EASY }
}
