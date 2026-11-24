package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The edit throttle's split (measured 24/11): the render feed parks itself
 * through a 429 window, but a QUESTION CARD's advance or seal is critical —
 * the owner's only surface for a pending question must never be dropped, or
 * the card keeps showing a question that already advanced.
 */
class QuestionCardEditTest {

    @Test
    fun theFeedIsThrottledWhileTheQuestionSurfaceIsNot() {
        assertTrue(editThrottled(cooldownActive = true, critical = false)) // a render waits
        assertFalse(editThrottled(cooldownActive = true, critical = true)) // the card never does
        assertFalse(editThrottled(cooldownActive = false, critical = false)) // no window, no throttle
        assertFalse(editThrottled(cooldownActive = false, critical = true))
    }
}
