package app.vowed

import app.vowed.data.ReviewFreshness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFreshnessTest {
    @Test fun aReviewOpenForMoreThanFortySecondsIsRebuiltBeforeTheWalletOpens() {
        assertFalse(ReviewFreshness.isStale(builtAtMs = 1_000, nowMs = 1_000))
        assertFalse(ReviewFreshness.isStale(1_000, 41_000)) // exactly 40 s
        assertTrue(ReviewFreshness.isStale(1_000, 41_001))
        assertTrue(ReviewFreshness.isStale(0, 80_000)) // the minute-and-a-half the emulator test took
    }
}
