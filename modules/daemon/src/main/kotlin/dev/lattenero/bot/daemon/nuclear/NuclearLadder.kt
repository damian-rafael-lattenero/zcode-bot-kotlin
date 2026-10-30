package dev.lattenero.bot.daemon.nuclear

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * ☢️ NUCLEAR, the owner's verbatim order (29/10): «el nuclear debe
 * reiniciar todo a lo duro, el bot de kotlin y todo zcode habido y por
 * haber ejecutandose en la mac, la app los cli todo y levantar zcode
 * nuevo y conectar el bot todo de cero». This OVERRIDES the old «channel
 * never kills the app» rule — the ladder lives in the DAEMON (never in
 * the channel protocol) and speaks macOS directly:
 *
 * 1. stopAll through the channel (~10s budget) — the polite half;
 * 2. SIGTERM→SIGKILL every zcode CLI runner (external /bin/ps, comm
 *    exact — the measured tech of deploy-fork-and-retire.nu: zombies
 *    answer `<defunct>`, pgrep is blind to this app);
 * 3. the app: `launchctl bootout gui/$UID/<label>` — the ONLY real kill
 *    (SIGKILL alone gets resurrected by launchd ~8s later, measured
 *    13/10), with the anti-resurrection watch and a second bootout;
 * 4. `open -a ZCode` — fresh app, new host, new socket+token;
 * 5. wait for the socket hello (the channel client's Kap retry
 *    reconnects on its own — we only VERIFY hello_ok);
 * 6. announce the summary, THEN `launchctl kickstart -k` our own label —
 *    the daemon restarts under launchd KeepAlive with a clean slate.
 *
 * The DECISIONS are pure ([appLabelsOf], [cliPidsOf], [nextKillRung]);
 * the effects live behind [ProcessEdge] (faked in tests) and the two
 * injected ops (stopAll, hello wait). Every step is a daemon.jsonl line.
 */

/** The one process edge the ladder walks on — the real one shells out, tests fake it. */
interface ProcessEdge {
    /** `launchctl list` verbatim (the label census). */
    fun launchctlList(): String

    /** One launchctl command; answers the exit code. */
    fun launchctl(vararg args: String): Int

    /** `/bin/ps -axo pid=,comm=` — EXTERNAL ps (nu's builtin lies about zombies). */
    fun psPidComm(): List<Pair<Long, String>>

    /** kill -s <signal> <pid>; false when the pid was already gone. */
    fun kill(pid: Long, signal: String): Boolean

    /** `open -a ZCode` — LaunchServices brings the app up fresh. */
    fun openApp(): Boolean

    fun uid(): Long
}

/** The macOS truth: ProcessBuilder over the external binaries, nothing clever. */
class MacProcessEdge : ProcessEdge {
    override fun launchctlList(): String = run("/bin/launchctl", "list")

    override fun launchctl(vararg args: String): Int = exec("/bin/launchctl", *args).first

    override fun psPidComm(): List<Pair<Long, String>> =
        run("/bin/ps", "-axo", "pid=,comm=")
            .lineSequence()
            .mapNotNull { line ->
                val pid = line.trim().takeWhile { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
                val comm = line.trim().substringAfter(' ').trim()
                if (comm.isEmpty()) null else pid to comm
            }
            .toList()

    override fun kill(pid: Long, signal: String): Boolean =
        exec("/bin/kill", "-s", signal, pid.toString()).first == 0

    override fun openApp(): Boolean = exec("/usr/bin/open", "-a", "ZCode").first == 0

    override fun uid(): Long = macosUid

    private fun run(vararg cmd: String): String {
        val (code, out) = exec(*cmd)
        return out
    }

    @Suppress("TooGenericExceptionCaught") // the edge never raises: a failed probe is an empty answer
    private fun exec(vararg cmd: String): Pair<Int, String> = try {
        val proc = ProcessBuilder(*cmd).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        (code to (if (code == 0) out else "$out$err"))
    } catch (e: Exception) {
        (-1 to (e.message ?: e::class.simpleName.orEmpty()))
    }

    private val macosUid: Long by lazy {
        exec("/usr/bin/id", "-u").second.trim().toLongOrNull() ?: DEFAULT_UID
    }

    private companion object {
        const val DEFAULT_UID = 501L
    }
}

// the ladder's shared vocabulary — file-level so the PURE decision fns see it
private const val APP_LABEL_PREFIX = "application.dev.zcode"
private const val APP_COMM = "ZCode"
private val CLI_COMMS = setOf("zcode-cli", "zcode-node-repl-mcp")
private const val SIGTERM = "TERM"
private const val SIGKILL = "KILL"

/** Pure: the app's launchd labels in a `launchctl list` output (application.dev.zcode.*). */
fun appLabelsOf(listOutput: String): List<String> = listOutput
    .lineSequence()
    .map { it.substringAfterLast('\t').trim() }
    .filter { it.startsWith(APP_LABEL_PREFIX) }
    .toList()

/** Pure: the zcode CLI runners in a ps census (comm exact — the app itself is `ZCode`, never matched). */
fun cliPidsOf(ps: List<Pair<Long, String>>): List<Long> = ps
    .filter { (_, comm) -> comm in CLI_COMMS }
    .map { (pid, _) -> pid }

/** Pure: the app's main pids in a ps census (comm exactly `ZCode` — helpers carry full paths). */
fun appPidsOf(ps: List<Pair<Long, String>>): List<Long> = ps
    .filter { (_, comm) -> comm == APP_COMM }
    .map { (pid, _) -> pid }

/** Pure: the kill ladder's next rung — SIGTERM first, SIGKILL for the survivors. */
fun nextKillRung(survivors: List<Long>, alreadyTermed: Boolean): String? = when {
    survivors.isEmpty() -> null
    alreadyTermed -> SIGKILL
    else -> SIGTERM
}

/** One walked step's record — the report the owner reads and the log keeps. */
data class StepOutcome(val step: String, val ok: Boolean, val detail: String) {
    fun line(): String = "${if (ok) "✓" else "✗"} $step: $detail"
}

/** The whole nuclear run's evidence. */
data class NuclearReport(
    val outcomes: List<StepOutcome>,
    val clisKilled: Int,
    val appBootedOut: Boolean,
    val helloOk: Boolean,
) {
    fun summary(): String = buildString {
        append("💥 nuclear listo — ")
        append(outcomes.joinToString(" · ") { it.line() })
        append("\n🔵 CLIs matados: $clisKilled · app ")
        append(if (appBootedOut) "reiniciada" else "NO se pudo reiniciar")
        append(" · bot ")
        append(if (helloOk) "conectado de cero ✅" else "SIN hello_ok 😵")
    }
}

/** The ladder's injected halves: the channel's stopAll and the hello verdict. */
class NuclearLadder(
    private val edge: ProcessEdge,
    private val stopAll: suspend () -> Int,
    private val helloOk: suspend () -> Boolean,
    private val announce: suspend (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val selfKickstart: Boolean = true,
) {

    /** The whole ladder — every step logged, both announces awaited BEFORE the self-kill. */
    suspend fun run(): NuclearReport {
        announce("💥 nuclear: reiniciando todo…")
        val outcomes = mutableListOf<StepOutcome>()

        val stopped = stopAllStep(outcomes)
        killClisStep(outcomes)
        val bootedOut = bootoutAppStep(outcomes)
        val hello = awaitHelloStep(outcomes)

        val report = NuclearReport(
            outcomes = outcomes,
            clisKilled = clisKilled,
            appBootedOut = bootedOut,
            helloOk = hello,
        )
        logger.warn { "NUCLEAR complete: stopped=$stopped clisKilled=$clisKilled bootout=$bootedOut hello=$hello" }
        announce(report.summary()) // awaited: the phone hears the end BEFORE we die
        if (selfKickstart) kickstartSelfStep(outcomes)
        return report
    }

    private var clisKilled = 0

    /** Step 1: the polite half — stopAll with a short budget (the ladder is the HARD half). */
    @Suppress("TooGenericExceptionCaught") // a failed stopAll must never stop the LADDER
    private suspend fun stopAllStep(outcomes: MutableList<StepOutcome>): Int {
        val stopped = try {
            stopAll()
        } catch (e: Exception) {
            logger.warn { "nuclear stopAll failed (the ladder continues): ${e.message}" }
            0
        }
        outcomes += StepOutcome("stopAll", stopped >= 0, "$stopped run(s) stopped")
        logger.info { "nuclear step stopAll: $stopped run(s)" }
        return stopped
    }

    /** Step 2: SIGTERM → 2s → SIGKILL the survivors (zcode-cli, zcode-node-repl-mcp). */
    private suspend fun killClisStep(outcomes: MutableList<StepOutcome>) {
        val pids = cliPidsOf(edge.psPidComm())
        if (pids.isEmpty()) {
            outcomes += StepOutcome("kill CLIs", true, "none running")
            logger.info { "nuclear step kill CLIs: none running" }
            return
        }
        var survivors = escalate(pids)
        clisKilled = pids.size
        val ok = survivors.isEmpty()
        outcomes += StepOutcome("kill CLIs", ok, "${pids.size} CLI(s) ${if (ok) "down" else "some survived"}")
        logger.info { "nuclear step kill CLIs: ${pids.size} targeted, ${survivors.size} survived" }
    }

    /**
     * Step 3: the app's ONLY real kill — bootout unregisters the launchd
     * job so it cannot resurrect (measured: SIGKILL alone returns ~8s
     * later). Anti-resurrection watch with a second bootout, the
     * deploy-fork-and-retire ladder's hard-won rung.
     */
    @Suppress("ReturnCount") // the rungs: label census, bootout, watch, reopen
    private suspend fun bootoutAppStep(outcomes: MutableList<StepOutcome>): Boolean {
        val labels = appLabelsOf(edge.launchctlList())
        val uid = edge.uid()
        var booted = false
        for (label in labels) {
            val code = edge.launchctl("bootout", "gui/$uid/$label")
            logger.info { "nuclear step bootout gui/$uid/$label -> exit $code" }
            if (code == 0) booted = true
        }
        if (labels.isEmpty()) {
            // no launchd label: the app may still run unregistered — kill the main directly
            val pids = appPidsOf(edge.psPidComm())
            pids.forEach { edge.kill(it, SIGKILL) }
            booted = pids.isNotEmpty()
            logger.info { "nuclear step bootout: no label; SIGKILL ${pids.size} main pid(s)" }
        }
        // the anti-resurrection watch: launchd loves a comeback
        delay(RESURRECTION_WATCH)
        val revived = appLabelsOf(edge.launchctlList())
        if (revived.isNotEmpty()) {
            logger.warn { "nuclear: the app resurrected ($revived) — bootout again" }
            revived.forEach { edge.launchctl("bootout", "gui/$uid/$it") }
            delay(RESURRECTION_WATCH)
        }
        val stillAlive = appPidsOf(edge.psPidComm()).isNotEmpty()
        outcomes += StepOutcome(
            "app bootout",
            booted && !stillAlive,
            if (booted) "label(s) ${labels.joinToString().ifEmpty { "killed by pid" }}" else "app not found",
        )
        // reopen — fresh app, fresh host, fresh socket+token
        val opened = edge.openApp()
        logger.info { "nuclear step open -a ZCode -> $opened" }
        return booted && !stillAlive
    }

    /** Step 5: the channel client's OWN Kap retry reconnects — we only verify the hello landed. */
    private suspend fun awaitHelloStep(outcomes: MutableList<StepOutcome>): Boolean {
        val deadline = clock() + HELLO_TIMEOUT.inWholeMilliseconds
        val hello = pollHello(deadline)
        outcomes += StepOutcome("reconnect", hello, if (hello) "hello_ok ✅" else "no hello in $HELLO_TIMEOUT")
        logger.info { "nuclear step reconnect: hello_ok=$hello" }
        return hello
    }

    /** The hello poll: bounded by BOTH a poll count and the wall clock — never a while(true). */
    @Suppress("ReturnCount") // one verdict per early exit: hello landed, clock ran out, polls ran out
    private suspend fun pollHello(deadlineMs: Long): Boolean {
        for (poll in 1..HELLO_POLLS) {
            if (helloOk()) return true
            if (clock() >= deadlineMs) return false
            delay(HELLO_POLL_EVERY)
        }
        return false
    }

    /** One kill rung walked: fire, wait its grace, re-census. Answers the survivors. */
    private suspend fun fireRung(pids: List<Long>, rung: String): List<Long> {
        pids.forEach { edge.kill(it, rung) }
        delay(if (rung == SIGTERM) TERM_GRACE else KILL_GRACE)
        return cliPidsOf(edge.psPidComm())
    }

    /** The escalation ladder's effectful walk: TERM → grace → census → KILL → grace → census. */
    private suspend fun escalate(pids: List<Long>): List<Long> {
        val afterTerm = fireRung(pids, SIGTERM)
        if (afterTerm.isEmpty()) return emptyList() // they died politely — no KILL needed
        return fireRung(afterTerm, SIGKILL) // the last rung: what survives is unkillable from here
    }

    /** Step 6: the daemon itself — kickstart -k is the programmatic restart under KeepAlive. */
    private fun kickstartSelfStep(outcomes: MutableList<StepOutcome>) {
        val code = edge.launchctl("kickstart", "-k", "gui/${edge.uid()}/${DAEMON_LABEL}")
        outcomes += StepOutcome("daemon restart", code == 0, "kickstart -k $DAEMON_LABEL -> exit $code")
        logger.warn { "nuclear step kickstart -k $DAEMON_LABEL -> exit $code (this process is already dead to rights)" }
    }

    companion object {
        const val DAEMON_LABEL = "com.zcode.bot-daemon"

        /** The kill ladder is TWO rungs — TERM then KILL, bounded, never a while(true). */
        const val KILL_RUNGS = 2
        val TERM_GRACE = 2.seconds
        val KILL_GRACE = 1.seconds
        val RESURRECTION_WATCH = 12.seconds
        val HELLO_TIMEOUT: Duration = 180.seconds
        val HELLO_POLL_EVERY = 2.seconds
        const val HELLO_POLLS = 120
    }
}
