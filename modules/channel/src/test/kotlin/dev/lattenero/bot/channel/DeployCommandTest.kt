package dev.lattenero.bot.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * B6 — /deploy's pure core pinned, THE ONE-TAP LAW (the owner's order,
 * 4/11: «elegir que deployar y esa eleccion ya empieza el deploy… una
 * única vez»): the owner gate (a non-owner tap is refused, a config that
 * cannot say who owns it fails CLOSED) and the ONE-SHOT consumption (the
 * first landed launch spends the machine; every further target is
 * InFlight until process death; a launch that never landed leaves the
 * shot free). No clock anywhere: the arm/window/expiry machinery is
 * retired, not re-tuned.
 */
class DeployCommandTest {

    @Test
    fun theOwnersTargetConfirms() {
        val m = DeployCommand()
        assertEquals(DeployCommand.Verdict.Confirmed(DeployCommand.Target.FORK), m.confirm(DeployCommand.Target.FORK, OWNER, OWNER))
        assertEquals(DeployCommand.Verdict.Confirmed(DeployCommand.Target.BOTH), m.confirm(DeployCommand.Target.BOTH, OWNER, OWNER))
    }

    @Test
    fun aNonOwnersTapIsRefused() {
        val m = DeployCommand()
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm(DeployCommand.Target.BOT, INTRUDER, OWNER))
        // the stranger's tap spent NOTHING — the owner's next tap fires
        assertEquals(DeployCommand.Verdict.Confirmed(DeployCommand.Target.BOT), m.confirm(DeployCommand.Target.BOT, OWNER, OWNER))
    }

    @Test
    fun aConfigWithoutOwnerFailsClosed() {
        val m = DeployCommand()
        // nobody fires on a config that cannot say who owns it — not even "the sender itself"
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm(DeployCommand.Target.BOT, 42L, null))
        assertFalse(m.authorize(42L, null))
    }

    @Test
    fun authorizeIsNullSafeFailClosed() {
        val m = DeployCommand()
        assertFalse(m.authorize(null, OWNER), "a null sender never passes")
        assertFalse(m.authorize(OWNER, null), "a null owner never passes")
        assertFalse(m.authorize(null, null))
        assertTrue(m.authorize(OWNER, OWNER))
    }

    @Test
    fun confirmingAloneDoesNotSpendTheShot() {
        val m = DeployCommand()
        // the flow marks the shot only after the spawn SUCCEEDED — until
        // then a confirm is just a verdict (a failed launch can be retried)
        assertEquals(DeployCommand.Verdict.Confirmed(DeployCommand.Target.BOT), m.confirm(DeployCommand.Target.BOT, OWNER, OWNER))
        assertEquals(DeployCommand.Verdict.Confirmed(DeployCommand.Target.BOT), m.confirm(DeployCommand.Target.BOT, OWNER, OWNER))
    }

    @Test
    fun theSpentMachineRefusesEveryTargetUntilProcessDeath() {
        val m = DeployCommand()
        m.spent()
        assertEquals(DeployCommand.Verdict.InFlight, m.confirm(DeployCommand.Target.FORK, OWNER, OWNER))
        assertEquals(DeployCommand.Verdict.InFlight, m.confirm(DeployCommand.Target.BOTH, OWNER, OWNER))
        assertEquals(DeployCommand.Verdict.InFlight, m.confirm(DeployCommand.Target.BOT, OWNER, OWNER))
        // there is no un-spend: the latch dies with the process (the
        // durable backstop is the spawn edge's own busy-guard)
    }

    @Test
    fun aNonOwnerTapAfterTheSpentMachineIsStillNotOwner() {
        // the owner gate outranks the latch: the refusal names the truth
        val m = DeployCommand()
        m.spent()
        assertEquals(DeployCommand.Verdict.NotOwner, m.confirm(DeployCommand.Target.BOT, INTRUDER, OWNER))
    }

    @Test
    fun theVerdictVocabularyIsExactlyThree() {
        // the flow renders one text per verdict — a fourth would render as silence
        val verdicts = listOf(
            DeployCommand.Verdict.Confirmed(DeployCommand.Target.BOT),
            DeployCommand.Verdict.NotOwner,
            DeployCommand.Verdict.InFlight,
        )
        assertEquals(3, verdicts.map { it::class }.toSet().size)
        assertIs<DeployCommand.Verdict.Confirmed>(verdicts.first())
    }

    @Test
    fun theTargetVocabularyIsExactlyFour() {
        // the card offers exactly what the machine can fire — nothing else exists
        assertEquals(4, DeployCommand.Target.entries.size)
    }

    private companion object {
        const val OWNER = 7L
        const val INTRUDER = 13L
    }
}
