package dev.lattenero.bot.daemon.nuclear

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The nuclear ladder: the DECISIONS pure (label census, CLI census, the
 * kill rungs) and the RUN against a fake edge — the whole hard-restart
 * order, one step at a time, every rung measured. The owner's order is
 * the contract: stop the runs, kill the CLIs, bootout+reopen the app,
 * verify the hello, kickstart the daemon itself LAST.
 */
class NuclearLadderTest {

    /** The fake edge: a scriptable Mac. */
    private class FakeEdge(
        var listOutput: String = "",
        var ps: List<Pair<Long, String>> = emptyList(),
    ) : ProcessEdge {
        val launchctlCalls = mutableListOf<String>()
        val kills = mutableListOf<Pair<Long, String>>()
        var opened = false
        var killEffect: (Long, String) -> Unit = { _, _ -> }
        val uid = 501L

        override fun launchctlList(): String = listOutput

        /** A truthful fake: bootout unregisters the label AND kills the app tree. */
        override fun launchctl(vararg args: String): Int {
            launchctlCalls += args.joinToString(" ")
            if (args.firstOrNull() == "bootout") {
                val label = args.last()
                listOutput = listOutput.lineSequence().filterNot { it.endsWith(label) }.joinToString("\n")
                ps = ps.filterNot { it.second == "ZCode" }
            }
            return 0
        }

        override fun psPidComm(): List<Pair<Long, String>> = ps

        override fun kill(pid: Long, signal: String): Boolean {
            kills += pid to signal
            killEffect(pid, signal)
            return true
        }

        override fun openApp(): Boolean {
            opened = true
            return true
        }

        override fun uid(): Long = uid
    }

    @Test
    fun appLabelsAreTheLaunchdCensus() {
        val out = """
            PID	Status	Label
            -	0	com.apple.xpc.launchd.discoveryd
            76826	0	application.dev.zcode.app.29388080.29389110
            11073	0	com.zcode.bot-daemon
        """.trimIndent()
        assertEquals(
            listOf("application.dev.zcode.app.29388080.29389110"),
            appLabelsOf(out),
        )
        assertEquals(emptyList(), appLabelsOf("PID\tStatus\tLabel\n"))
    }

    @Test
    fun cliCensusMatchesCommExactlyNeverTheApp() {
        val ps = listOf(
            76826L to "ZCode", // the app's main — NEVER a CLI kill (bootout owns it)
            32968L to "/Applications/ZCode.app/Contents/Frameworks/ZCode Helper", // a helper
            36784L to "zcode-cli",
            36955L to "zcode-node-repl-mcp",
            36785L to "zcode-cli <defunct>", // a zombie answers its OLD name — comm is NOT exact
        )
        assertEquals(listOf(36784L, 36955L), cliPidsOf(ps))
        assertEquals(listOf(76826L), appPidsOf(ps)) // comm EXACTLY ZCode
    }

    @Test
    fun killRungsEscalateTermThenKillAndStopWhenEmpty() {
        assertEquals("TERM", nextKillRung(listOf(1L), alreadyTermed = false))
        assertEquals("KILL", nextKillRung(listOf(1L), alreadyTermed = true))
        assertEquals(null, nextKillRung(emptyList(), alreadyTermed = false))
        assertEquals(null, nextKillRung(emptyList(), alreadyTermed = true))
    }

    @Test
    fun theLadderRunsTheOwnersOrderStepByStep() = runTest {
        val edge = FakeEdge(
            listOutput = "76826\t0\tapplication.dev.zcode.app.1.2.3",
            ps = listOf(36784L to "zcode-cli", 36955L to "zcode-node-repl-mcp", 76826L to "ZCode"),
        )
        // the kills LAND: the census after TERM is empty (they died politely)
        edge.killEffect = { _, _ -> edge.ps = listOf(76826L to "ZCode") }

        val announced = mutableListOf<String>()
        var hellos = 0
        val ladder = NuclearLadder(
            edge = edge,
            stopAll = { 2 },
            helloOk = { hellos++; hellos > 1 }, // second poll lands the hello
            announce = { announced.add(it) },
            clock = { 0L },
            selfKickstart = true,
        )
        val report = ladder.run()

        // the announces: the owner's start word, then the summary — BEFORE the self-kill
        assertEquals("💥 nuclear: reiniciando todo…", announced.first())
        assertTrue(announced.last().startsWith("💥 nuclear listo"))
        assertEquals(2, announced.size)

        // stopAll → TERM both CLIs → bootout the label → open → hello → kickstart self LAST
        assertEquals(listOf(36784L to "TERM", 36955L to "TERM"), edge.kills)
        assertTrue(edge.launchctlCalls.contains("bootout gui/501/application.dev.zcode.app.1.2.3"))
        assertTrue(edge.opened)
        assertTrue(edge.launchctlCalls.last() == "kickstart -k gui/501/com.zcode.bot-daemon")

        assertEquals(2, report.clisKilled)
        assertTrue(report.appBootedOut)
        assertTrue(report.helloOk)
        assertEquals(
            listOf("stopAll", "kill CLIs", "app bootout", "reconnect", "daemon restart"),
            report.outcomes.map { it.step },
        )
    }

    @Test
    fun survivingTermEscalatesToKill() = runTest {
        val edge = FakeEdge(
            ps = listOf(1L to "zcode-cli", 2L to "zcode-cli"),
        )
        // a stubborn CLI: TERM does nothing, KILL clears it
        edge.killEffect = { pid, sig ->
            if (sig == "KILL") edge.ps = emptyList()
        }
        val ladder = NuclearLadder(
            edge = edge,
            stopAll = { 0 },
            helloOk = { true },
            announce = {},
            clock = { 0L },
            selfKickstart = false,
        )
        val report = ladder.run()
        assertEquals(listOf(1L to "TERM", 2L to "TERM", 1L to "KILL", 2L to "KILL"), edge.kills)
        assertTrue(report.outcomes.first { it.step == "kill CLIs" }.ok)
    }

    @Test
    fun noHelloIsHonestNotFakeSuccess() = runTest {
        val edge = FakeEdge(listOutput = "")
        val ladder = NuclearLadder(
            edge = edge,
            stopAll = { 0 },
            helloOk = { false },
            announce = {},
            clock = { 0L },
            selfKickstart = false,
        )
        val report = ladder.run()
        assertFalse(report.helloOk)
        assertTrue(announcedOf(report).contains("SIN hello_ok"))
    }

    private fun announcedOf(report: NuclearReport): String = report.summary()
}
