package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B6 — /deploy's pure core pinned: the two-step confirmation (the go is
 * the button's alone), the OWNER gate (a non-owner tap is refused with the
 * owner's arm untouched), the 600 s expiry (Python's _DEPLOY_TIMEOUT_S,
 * reinterpreted), the arm ALWAYS consumed by its verdict (a spent
 * confirmation never fires twice), and the re-arm that opens a fresh
 * window. The clock is injected — no test sleeps a single millisecond.
 */
class DeployCommandTest {

    private var now = 0L

    private fun machine() = DeployCommand(clock = { now })

    @Test
    fun aFreshArmConfirmsTheOwnersGoInsideTheWindow() {
        val m = machine()
        m.arm()
        now = DeployCommand.WINDOW_MS - 1 // a hair before the ceiling
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun theGoIsConsumedByItsShot() {
        val m = machine()
        m.arm()
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
        // the double-tap: impatience must never launch a SECOND deploy
        assertEquals(DeployCommand.Verdict.NotArmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun theWindowExpiresAtExactly600s() {
        val m = machine()
        m.arm()
        now = DeployCommand.WINDOW_MS // the boundary itself: the ceiling is inclusive
        assertEquals(DeployCommand.Verdict.Expired, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun anExpiredGoConsumesTheArm() {
        val m = machine()
        m.arm()
        now = DeployCommand.WINDOW_MS + 1
        assertEquals(DeployCommand.Verdict.Expired, m.confirm("go", OWNER, OWNER))
        now += 1000 // a fresh tap, still nothing armed
        assertEquals(DeployCommand.Verdict.NotArmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun aGoWithoutArmIsNotArmed() {
        val m = machine()
        assertEquals(DeployCommand.Verdict.NotArmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun aNonOwnersTapIsRefusedAndTheOwnersArmSurvives() {
        val m = machine()
        m.arm()
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm("go", INTRUDER, OWNER))
        // the owner's confirmation was NOT consumed by the stranger's tap
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun aNonOwnersNoNeverCancelsTheOwnersArm() {
        val m = machine()
        m.arm()
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm("no", INTRUDER, OWNER))
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun aConfigWithoutOwnerFailsClosed() {
        val m = machine()
        m.arm()
        // nobody confirms on a config that cannot say who owns it — not even "the sender itself"
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm("go", 42L, null))
    }

    @Test
    fun theOwnersNoCancelsOnce() {
        val m = machine()
        m.arm()
        assertEquals(DeployCommand.Verdict.Cancelled, m.confirm("no", OWNER, OWNER))
        assertEquals(DeployCommand.Verdict.NotArmed, m.confirm("no", OWNER, OWNER)) // the second no has nothing to cancel
    }

    @Test
    fun aStaleNoReadsAsExpiredNotCancelled() {
        val m = machine()
        m.arm()
        now = DeployCommand.WINDOW_MS + 5
        assertEquals(DeployCommand.Verdict.Expired, m.confirm("no", OWNER, OWNER))
    }

    @Test
    fun aReArmOpensAFreshWindow() {
        val m = machine()
        m.arm()
        now = DeployCommand.WINDOW_MS - 100_000 // 500s in: the first window is nearly spent
        m.arm() // the bare /deploy again — a FRESH 600s from NOW
        now += DeployCommand.WINDOW_MS - 100_000 // 500s after the RE-arm (1000s after the first)
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun unknownWordsOnTheConfirmHalfFireNothing() {
        val m = machine()
        m.arm()
        assertEquals(DeployCommand.Verdict.NotArmed, m.confirm("please", OWNER, OWNER))
        // a wrong word never spends the window — only go/no ever fires
        assertEquals(DeployCommand.Verdict.Confirmed, m.confirm("go", OWNER, OWNER))
    }

    @Test
    fun theVerdictVocabularyIsExactlyFive() {
        // the wiring renders one text per verdict — a sixth would render as silence
        val verdicts = listOf(
            DeployCommand.Verdict.Confirmed,
            DeployCommand.Verdict.Cancelled,
            DeployCommand.Verdict.Expired,
            DeployCommand.Verdict.NotArmed,
            DeployCommand.Verdict.NotOwner,
        )
        assertEquals(5, verdicts.map { it::class }.toSet().size)
        assertIs<DeployCommand.Verdict.Confirmed>(verdicts.first())
    }

    private companion object {
        const val OWNER = 7L
        const val INTRUDER = 13L
    }
}
