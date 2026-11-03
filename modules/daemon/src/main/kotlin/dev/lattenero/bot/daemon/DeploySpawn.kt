package dev.lattenero.bot.daemon

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private val logger = KotlinLogging.logger {}

/**
 * B6's deploy edge — the REAL spawn of scripts/deploy-daemon.nu, detached
 * by construction. The script it launches builds, installs and BOOTOUTS
 * THIS VERY DAEMON's LaunchAgent (then bootstraps the new one), so the
 * spawn must survive its own parent's death:
 *
 * - `/usr/bin/nohup` — when launchd tears the daemon down (the script's
 *   own bootout of it), the child's inherited SIGHUP is IGNORED; the
 *   interpreter is the script's shebang's business, never ours.
 * - detached stdio — stdin is /dev/null, stdout+stderr are APPENDED to
 *   the deploy log: no pipe to a dying parent to SIGPIPE the script, and
 *   the build's output lands where /file can fetch it from.
 * - fire and FORGET — no waitFor anywhere: the build takes minutes and
 *   this daemon must keep answering the phone until the bootout lands.
 *
 * The residual truth, said plainly: the child keeps the daemon's process
 * GROUP (POSIX has no setsid from Java). Measured launchd behavior on
 * bootout is SIGTERM to the job's own pid — nohup + the orphan's
 * reparenting covers the child — but a launchd that ever killed whole
 * groups would kill the script mid-deploy. The announce-before-spawn door
 * (DeployFlow) is the reason that window is survivable: the phone already
 * heard the deploy left.
 */
class DeploySpawn(private val script: File, private val logFile: File) {

    /**
     * The builder, exposed so the suite pins the DETACHED flags without
     * ever exec'ing a deploy.
     */
    internal fun builder(): ProcessBuilder =
        ProcessBuilder("/usr/bin/nohup", script.absolutePath)
            .directory(script.parentFile) // the script resolves its repo from $FILE_PWD; cwd is the belt
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)

    /** Fork and move on — answers the child's process (the pid is the log's evidence). */
    fun spawn(): Process = builder().start()

    companion object {
        /** Where the deploy's own words land (the daemon's logs dir; the wiring mkdirs it). */
        const val LOG_FILE_NAME = "deploy-daemon.log"
    }
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
