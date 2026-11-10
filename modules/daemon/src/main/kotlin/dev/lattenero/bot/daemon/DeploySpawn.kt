package dev.lattenero.bot.daemon

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val logger = KotlinLogging.logger {}

/** One launchctl call's outcome — stdout+stderr merged (the loud relay's text). */
data class LaunchctlResult(val exit: Int, val text: String)

/** The launchctl edge; injected so the suite never touches launchd. */
fun interface Launchctl {
    fun run(args: List<String>): LaunchctlResult
}

/**
 * B6's deploy edge — the REAL spawn of scripts/deploy-daemon.nu, detached
 * by CONSTRUCTION: the script runs as ITS OWN one-shot launchd job
 * ([JOB_LABEL]), never as a child of this daemon.
 *
 * The first version used /usr/bin/nohup + ProcessBuilder and trusted
 * "launchd SIGTERMs the job's pid; the orphan reparents". The owner's live
 * /deploy on 2026-11-02 22:02 REFUTED it: launchd tears down the job's
 * whole process tree, so the script died AT ITS OWN `launchctl bootout` —
 * the daemon went down and the bootstrap never ran (the bot stayed silent
 * until a manual bootstrap). A separate job is the only thing that
 * survives its sibling's bootout.
 *
 * The plist carries what the build needs and launchd would NOT inherit:
 * HOME (nu's home dir), JAVA_HOME (gradlew's JVM — the daemon's own) and a
 * PATH wide enough for launchctl/stat/cp. Stdout and stderr both land
 * APPENDED in the deploy log — /file's contract.
 */
class DeploySpawn(
    private val script: File,
    private val logFile: File,
    private val plistFile: File,
    /**
     * The script's own arguments (e.g. the fork automaton's `--real` — its
     * DEFAULT mode is dryrun, so a fork deploy without the flag is a
     * rehearsal, measured 4/11 13:56). The daemon script takes none.
     */
    private val args: List<String> = emptyList(),
    /**
     * The one-shot job's OWN label — the survival law's whole point. The
     * daemon deploy keeps [Companion.JOB_LABEL]; the fork deploy runs as
     * [FORK_JOB_LABEL], a sibling of BOTH the daemon and the daemon's
     * deploy job (in a BOTH deploy each script outlives the other's bootout).
     */
    private val label: String = JOB_LABEL,
    private val uid: String = currentUid(),
    private val javaHome: String = System.getProperty("java.home"),
    private val home: String = System.getProperty("user.home"),
    private val launchctl: Launchctl = Launchctl { args -> runLaunchctl(args) },
    /**
     * The beat between a failed bootstrap and its one retry (injected:
     * tests never sleep). A NON-BLOCKING delay (kap-first sweep 2): the
     * spawn runs on the daemon's coroutine world and a parked thread was
     * a Thread the poller could be needing — the timing is the same
     * [BOOTOUT_BEAT_MS], the waiting is a suspension.
     */
    private val beat: suspend (Long) -> Unit = { delay(it) },
    /** Injectable for the tests (the log-alive detector reads the wall clock). */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** The honest busy answer — both detectors name what they saw. */
    private fun busyMessage(): String =
        "el deploy job $label ya está CORRIENDO — hay un deploy en curso (launchd lo dice, " +
            "o su log sigue vivo); esperá a que termine (el fork tarda ~15 min) y volvé a tocar"

    /** The job's own log is being written RIGHT NOW: a mid-build deploy job. */
    private fun jobLogAlive(): Boolean =
        logFile.exists() && (nowMs() - logFile.lastModified()) < JOB_LOG_ALIVE_MS

    /**
     * The ladder's honest exit: refuse a RUNNING job (busy — never
     * bootstrap it), let a dead wedge fall through to the enable self-heal.
     */
    private fun refuseIfRunning() {
        val probe = launchctl.run(listOf("print", "${domain()}/$label"))
        if (probe.text.contains(JOB_RUNNING_MARK) || jobLogAlive()) {
            throw IllegalStateException(busyMessage())
        }
        logger.warn { "deploy job $label wedged (error 5) but not running — the enable self-heal proceeds" }
    }

    /**
     * The one-shot job's plist — pure, so the suite pins the contract:
     * RunAtLoad, NO KeepAlive (a deploy runs once, never in a loop), the
     * script's own directory as cwd (its FILE_PWD belt), the three env
     * vars and the log redirect.
     */
    fun plist(): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" """ +
                """"http://www.apple.com/DTDs/PropertyList-1.0.dtd">""",
        )
        appendLine("""<plist version="1.0">""")
        appendLine("<dict>")
        appendLine("    <key>Label</key>")
        appendLine("    <string>$label</string>")
        appendLine("    <key>ProgramArguments</key>")
        appendLine("    <array>")
        appendLine("        <string>${xml(script.absolutePath)}</string>")
        for (arg in args) appendLine("        <string>${xml(arg)}</string>")
        appendLine("    </array>")
        appendLine("    <key>WorkingDirectory</key>")
        appendLine("    <string>${xml(script.parentFile.path)}</string>")
        appendLine("    <key>EnvironmentVariables</key>")
        appendLine("    <dict>")
        appendLine("        <key>HOME</key>")
        appendLine("        <string>${xml(home)}</string>")
        appendLine("        <key>JAVA_HOME</key>")
        appendLine("        <string>${xml(javaHome)}</string>")
        appendLine("        <key>PATH</key>")
        appendLine("        <string>${xml(path())}</string>")
        appendLine("    </dict>")
        appendLine("    <key>RunAtLoad</key>")
        appendLine("    <true/>")
        appendLine("    <key>ProcessType</key>")
        appendLine("    <string>Background</string>")
        appendLine("    <key>StandardOutPath</key>")
        appendLine("    <string>${xml(logFile.absolutePath)}</string>")
        appendLine("    <key>StandardErrorPath</key>")
        appendLine("    <string>${xml(logFile.absolutePath)}</string>")
        appendLine("</dict>")
        appendLine("</plist>")
    }

    /**
     * Write the job's plist and hand it to launchd. A previous run of the
     * same label (one-shot, normally already exited) is cleared first —
     * bootstrap refuses an already-loaded label with `error 5: Input/output
     * error` (a lie about the cause).
     *
     * bootout's teardown RACES the bootstrap: measured 2/11 22:20, the
     * second /deploy of the day bounced twice with error 5 because the job
     * was still loaded from the 22:11 run and the bootstrap flew
     * milliseconds after the bootout. The nushell helper already retries
     * once after a beat (`lib/deploy.nu`); this edge now does the same.
     *
     * Any failure to write or to bootstrap throws: [DeployFlow]'s catch
     * turns it into the phone's LOUD failure and THIS daemon stays up.
     */
    suspend fun spawn(): LaunchctlResult {
        // THE BUSY GUARD comes first: a RUNNING deploy job cannot be
        // bootout'ed (launchd answers error 5 while it runs — measured 4/11
        // 16:28-16:39, the owner tapped fork/both AGAIN while the 16:28 fork
        // deploy was mid-build) and double-running a deploy is the real
        // hazard. The honest answer names the state, not launchd noise.
        // TWO detectors, because launchd's state string lied once (measured
        // 16:39: the guard passed while the job was mid-build): the print's
        // state/pid AND the job's own log being alive (a build writes to it
        // every few seconds; a log silent 5 minutes is a dead — or hung,
        // equally un-double-run-able — job).
        val precheck = launchctl.run(listOf("print", "${domain()}/$label"))
        if (precheck.text.contains(JOB_RUNNING_MARK) || jobLogAlive()) {
            throw IllegalStateException(busyMessage())
        }
        plistFile.parentFile?.mkdirs()
        plistFile.writeText(plist())
        logFile.parentFile?.mkdirs()
        // THE BOOTOUT HAS ITS OWN LADDER now: a wedged label (error 5) clears
        // itself seconds later — measured 4/11: two taps 27 s apart wedged
        // each other, the single bootout ate the error and poisoned EVERY
        // bootstrap after it (the phone's 🤖 tap failed while a manual
        // bootout two minutes later succeeded on the first try). rc=3 (not
        // loaded) is a fine outcome — nothing to clear. The bootstrap never
        // runs against a bootout that was still failing error 5 without a
        // fight. 5 attempts: the wedge can outlive 3 (~4 s) when concurrent
        // launchd work hits the same domain (measured at 16:28, the fork's
        // bootstrap racing the bot's bootout) — the budget is now ~10 s.
        var cleared = false
        for (attempt in 1..BOOTOUT_ATTEMPTS) {
            // THE BOOTOUT'S ONE-ARG LAW (measured 4/11 17:5x, the root of
            // «/deploy siempre falla»): `bootout <domain> <label>` as TWO
            // arguments fails error 5 EVERY time — launchd reads the label as
            // a service PATH. The house form (enable, kickstart, print) is
            // ONE combined target: gui/<uid>/<label>. With the two-arg form
            // the bootout never cleared, the label stayed loaded, and every
            // bootstrap then failed error 5 — the phone's same-message failure.
            val bootout = launchctl.run(listOf("bootout", "${domain()}/$label"))
            logger.info { "deploy job $label bootout #$attempt: exit=${bootout.exit} ${bootout.text.trim()}" }
            if (bootout.exit == 0 || bootout.exit == BOOTOUT_NOT_LOADED_EXIT) {
                cleared = true
                break
            }
            if (attempt < BOOTOUT_ATTEMPTS) beat(BOOTOUT_BEAT_MS)
        }
        if (!cleared) {
            // THE LADDER EXHAUSTED ON ERROR 5: a job that is actually RUNNING
            // (measured 16:39 — the second tap raced the 16:28 fork build)
            // is the BUSY case and must NEVER be bootstrapped (the
            // double-deploy hazard; the enable self-heal below is for a
            // DISABLED/stale label, not a live one).
            refuseIfRunning()
        }
        var out = launchctl.run(listOf("bootstrap", domain(), plistFile.absolutePath))
        if (out.exit != 0) {
            beat(BOOTOUT_BEAT_MS)
            out = launchctl.run(listOf("bootstrap", domain(), plistFile.absolutePath))
        }
        // error 5 = disabled / stale label (measured 2/11 22:20 + 4/11 13:56
        // + 4/11 14:4x AGAIN on bot-fork-deploy): the launchd recovery recipe
        // runs HERE now — enable the label and try the last bootstrap, so a
        // wedged label self-heals instead of failing the phone's tap forever.
        // `enable` takes ONE service-target arg (gui/<uid>/<label>): the first
        // version passed domain+label separately and launchd swallowed it as a
        // no-op — the self-heal never healed (measured: the fork tap kept
        // failing error 5 after the "fixed" daemon was live)
        if (out.exit != 0 && out.text.contains(ERROR_5_MARK)) {
            val enable = launchctl.run(listOf("enable", "${domain()}/$label"))
            logger.warn {
                "deploy job $label bootstrap hit error 5 — enable " +
                    "exit=${enable.exit} ${enable.text.trim()}, retrying once"
            }
            out = launchctl.run(listOf("bootstrap", domain(), plistFile.absolutePath))
        }
        if (out.exit != 0) {
            throw IllegalStateException("launchctl bootstrap $label failed: ${out.text.trim()}")
        }
        // the log's line is the RECEIPT: only a bootstrapped job earns it
        logFile.appendText("\n==> deploy job $label armed at ${java.time.Instant.now()}\n")
        return out
    }

    /** `gui/<uid>` — the daemon's own domain; the deploy job is its sibling, not its child. */
    fun domain(): String = "gui/$uid"

    private fun path(): String = listOf(
        "/usr/bin",
        "/bin",
        "/usr/sbin",
        "/sbin",
        File(javaHome, "bin").path,
        File(home, ".local/bin").path,
    ).joinToString(":")

    companion object {
        /** Where the deploy's own words land (the daemon's logs dir; the wiring mkdirs it). */
        const val LOG_FILE_NAME = "deploy-daemon.log"

        /** The one-shot job's label — INDEPENDENT of the daemon's (the survival is the point). */
        const val JOB_LABEL = "com.zcode.bot-deploy"

        /** The fork deploy's own label — a sibling of the daemon AND of the daemon's deploy job. */
        const val FORK_JOB_LABEL = "com.zcode.bot-fork-deploy"

        /** The fork deploy's log — the fork's words never mix with the daemon's. */
        const val FORK_LOG_FILE_NAME = "deploy-fork.log"

        /**
         * The whatsdigest deploy's log (the 📱 target, 10/11): the sibling
         * ecosystem's build words, never mixed with the daemon's.
         */
        const val WHATSAPP_LOG_FILE_NAME = "deploy-whatsapp.log"

        /** The rendered plist's file name inside the daemon's state dir. */
        const val JOB_PLIST_NAME = "deploy-job.plist"

        /** The fork job's rendered plist name — its own file, its own bootout. */
        const val FORK_JOB_PLIST_NAME = "deploy-fork-job.plist"

        /** The whatsdigest job's rendered plist name — its own file, its own bootout. */
        const val WHATSAPP_JOB_PLIST_NAME = "deploy-wd-job.plist"

        /** The whatsdigest deploy's own label — a sibling of everything. */
        const val WHATSAPP_JOB_LABEL = "com.zcode.wd-deploy"

        /** The beat between a failed bootstrap and its one retry (lib/deploy.nu's measured fix). */
        const val BOOTOUT_BEAT_MS = 2_000L

        /** The bootout's own retry ladder — a wedged label clears itself on a later try. */
        const val BOOTOUT_ATTEMPTS = 5

        /** `Boot-out failed: 3: No such process` — the label is not loaded; nothing to clear. */
        const val BOOTOUT_NOT_LOADED_EXIT = 3

        /** `launchctl print`'s marker for an ACTIVE deploy job — the busy guard's trigger. */
        const val JOB_RUNNING_MARK = "state = running"

        /** A job log written this recently means the deploy is mid-run (the second busy detector). */
        const val JOB_LOG_ALIVE_MS = 5 * 60_000L

        /** launchd's own error-5 signature (disabled/stale label — the enable+retry trigger). */
        const val ERROR_5_MARK = "Bootstrap failed: 5"

        private fun xml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** The numeric uid — launchctl's domain target needs it, never the username. */
        @Suppress("TooGenericExceptionCaught") // an unreadable uid degrades to the default, never crashes a boot
        fun currentUid(): String = try {
            val p = ProcessBuilder("/usr/bin/id", "-u").redirectErrorStream(true).start()
            val uid = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && uid.isNotEmpty()) uid else "0"
        } catch (e: Exception) {
            logger.warn { "id -u unreadable (${e.message}) — the deploy job targets the wrong domain" }
            "0"
        }
    }
}

/** The real launchctl: merged output, never throws (the exit code carries the verdict). */
@Suppress("TooGenericExceptionCaught") // a missing launchctl is an exit -1 with its reason, not a crash
private fun runLaunchctl(args: List<String>): LaunchctlResult = try {
    val p = ProcessBuilder(listOf("/bin/launchctl") + args).redirectErrorStream(true).start()
    val text = p.inputStream.bufferedReader().readText()
    LaunchctlResult(p.waitFor(), text)
} catch (e: Exception) {
    LaunchctlResult(-1, e.message ?: e::class.simpleName.orEmpty())
}

/**
 * The script's path — `<home>/daemon.json`'s `deploy_script` key,
 * defaulting to the repo checkout (the installed ~/.zcode/bot-daemon tree
 * carries no scripts; the script is a repo citizen). Any unreadable or
 * unknown-shaped file degrades to the default — the knob NEVER throws —
 * and a path that points at nothing fails LOUDLY at spawn time (the
 * flow's failure leg), never silently here.
 */
object DeployScriptKnob {

    private const val KEY = "deploy_script"
    private const val FORK_KEY = "deploy_fork_script"
    private const val WHATSAPP_KEY = "deploy_whatsapp_script"

    val DEFAULT: File =
        File(System.getProperty("user.home"), "Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu")

    /** The fork deploy's script — THE MIRROR (4/11): the same script the
     *  shell's `zcode-deploy` runs (build-install --when-quit: build with
     *  Telegram phase announces, the swap waits for the owner's ⌘Q — the app
     *  is NEVER killed behind his back; the old auto-cutover retired with
     *  Python). The knob `deploy_fork_script` still overrides. */
    val FORK_DEFAULT: File =
        File(System.getProperty("user.home"), "Projects/ZCode/scripts/build-install.nu")

    /**
     * The whatsdigest deploy's script (the 📱 target, 10/11): the sibling
     * repo's own canonical deploy (build + tests + install + its LaunchAgent
     * restart + its own 45s verification). The knob `deploy_whatsapp_script`
     * still overrides.
     */
    val WHATSAPP_DEFAULT: File =
        File(System.getProperty("user.home"), "Projects/whatsdigest-kt/scripts/deploy-daemon.nu")

    // any reading failure is the same answer: the default (SendKnob's degrade law)
    @Suppress("TooGenericExceptionCaught")
    fun load(daemonJson: File): File = load(daemonJson, KEY, DEFAULT, "deploy_script")

    /** The fork's knob — its own key (`deploy_fork_script`), the same degrade law. */
    @Suppress("TooGenericExceptionCaught")
    fun loadFork(daemonJson: File): File = load(daemonJson, FORK_KEY, FORK_DEFAULT, FORK_KEY)

    /** The whatsdigest knob — its own key (`deploy_whatsapp_script`), the same degrade law. */
    @Suppress("TooGenericExceptionCaught")
    fun loadWhatsapp(daemonJson: File): File = load(daemonJson, WHATSAPP_KEY, WHATSAPP_DEFAULT, WHATSAPP_KEY)

    // any reading failure is the same answer: the default (SendKnob's degrade law)
    @Suppress("TooGenericExceptionCaught")
    private fun load(daemonJson: File, key: String, default: File, logName: String): File = try {
        val root = Json.parseToJsonElement(daemonJson.readText()) as? JsonObject
        (root?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }?.let { File(it) } ?: default
    } catch (e: Exception) {
        logger.warn { "$logName knob unreadable (${e.message}) — the default repo script stays" }
        default
    }
}
