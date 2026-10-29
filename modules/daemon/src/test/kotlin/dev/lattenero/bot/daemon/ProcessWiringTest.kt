package dev.lattenero.bot.daemon

import kap.Kap
import kap.ProcessStoppedException
import kap.RestartPolicy
import kap.Schedule
import kap.process
import kap.repeat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Main wiring idiom, under virtual time: cadence is schedule algebra,
 * the heartbeat beats independently of cycle progress, bugs restart under
 * the convergence law, stop() is clean. (Process itself is law-tested in
 * kap; this pins OUR usage of it.)
 */
class ProcessWiringTest {

    @Test
    fun cadenceHeartbeatAndCleanStop() = runTest {
        val passes = AtomicInteger()
        val beats = AtomicInteger()
        val zconn = Kap { passes.incrementAndGet() }
            .repeat(Schedule.spaced(1.seconds))
            .process(
                name = "wiring",
                restart = RestartPolicy.constant(50.milliseconds, 3),
                heartbeatEvery = 250.milliseconds,
                onHeartbeat = { beats.incrementAndGet() },
            )
        val handle = zconn.startIn(backgroundScope)

        advanceTimeBy(3.5.seconds)
        assertEquals(4, passes.get(), "first run immediate, then one per interval")
        assertTrue(beats.get() >= 10, "the heartbeat beats at its own cadence, saw ${beats.get()}")

        handle.stop()
        try {
            handle.await()
            fail("stop() must terminal-fail await with ProcessStoppedException")
        } catch (e: ProcessStoppedException) {
            // the clean shutdown path Main catches
        }
    }

    @Test
    fun aFailingCycleRestartsUnderThePolicyAndThenConverges() = runTest {
        val calls = AtomicInteger()
        val zconn = Kap { if (calls.incrementAndGet() <= 2) error("boom") else "ok" }
            .repeat(Schedule.spaced(1.seconds))
            .process(name = "wiring-restart", restart = RestartPolicy.constant(50.milliseconds, 5))
        val handle = zconn.startIn(backgroundScope)

        advanceTimeBy(1.seconds)
        assertEquals(3, calls.get(), "two failures restarted, the third ran")
        assertEquals(2, handle.restarts.value)

        handle.stop()
    }
}
