package dev.lattenero.bot.daemon

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val uid: String = currentUid(),
    private val javaHome: String = System.getProperty("java.home"),
    private val home: String = System.getProperty("user.home"),
    private val launchctl: Launchctl = Launchctl { args -> runLaunchctl(args) },
    /** The beat between a failed bootstrap and its one retry (injected: tests never sleep). */
    private val beat: (Long) -> Unit = { Thread.sleep(it) },
) {

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
        appendLine("    <string>$JOB_LABEL</string>")
        appendLine("    <key>ProgramArguments</key>")
        appendLine("    <array>")
        appendLine("        <string>${xml(script.absolutePath)}</string>")
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
    fun spawn(): LaunchctlResult {
        plistFile.parentFile?.mkdirs()
        plistFile.writeText(plist())
        logFile.parentFile?.mkdirs()
        launchctl.run(listOf("bootout", domain(), JOB_LABEL))
        var out = launchctl.run(listOf("bootstrap", domain(), plistFile.absolutePath))
        if (out.exit != 0) {
            beat(BOOTOUT_BEAT_MS)
            out = launchctl.run(listOf("bootstrap", domain(), plistFile.absolutePath))
        }
        if (out.exit != 0) {
            throw IllegalStateException("launchctl bootstrap $JOB_LABEL failed: ${out.text.trim()}")
        }
        // the log's line is the RECEIPT: only a bootstrapped job earns it
        logFile.appendText("\n==> deploy job $JOB_LABEL armed at ${java.time.Instant.now()}\n")
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

        /** The rendered plist's file name inside the daemon's state dir. */
        const val JOB_PLIST_NAME = "deploy-job.plist"

        /** The beat between a failed bootstrap and its one retry (lib/deploy.nu's measured fix). */
        const val BOOTOUT_BEAT_MS = 2_000L

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

    val DEFAULT: File =
        File(System.getProperty("user.home"), "Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu")

    // any reading failure is the same answer: the default (SendKnob's degrade law)
    @Suppress("TooGenericExceptionCaught")
    fun load(daemonJson: File): File = try {
        val root = Json.parseToJsonElement(daemonJson.readText()) as? JsonObject
        (root?.get(KEY) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() }?.let { File(it) } ?: DEFAULT
    } catch (e: Exception) {
        logger.warn { "deploy_script knob unreadable (${e.message}) — the default repo script stays" }
        DEFAULT
    }
}
