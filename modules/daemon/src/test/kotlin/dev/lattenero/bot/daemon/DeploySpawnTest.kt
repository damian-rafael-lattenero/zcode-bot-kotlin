package dev.lattenero.bot.daemon

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * B6's deploy edge pinned WITHOUT ever exec'ing a deploy: the builder's
 * detached flags are the contract — nohup in front of the script (the
 * shebang's interpreter, SIGHUP ignored), stdin from /dev/null, stdout+
 * stderr APPENDED to the deploy log (no pipe to a dying parent), errors
 * merged, and the cwd the script's own repo. The knob's ladder is here
 * too: the default repo script, the daemon.json override, the honest
 * degrade on garbage.
 */
class DeploySpawnTest {

    private val script = File("/Users/fake/Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu")
    private val log = File("/Users/fake/.zcode/bot-kotlin/logs/deploy-daemon.log")

    @Test
    fun theBuilderIsNohupOverTheScriptWithHerPinnedInterpreter() {
        val cmd = DeploySpawn(script, log).builder().command()
        assertEquals(listOf("/usr/bin/nohup", script.absolutePath), cmd)
    }

    @Test
    fun theBuildersStdioIsDetached() {
        val b = DeploySpawn(script, log).builder()
        // stdin: /dev/null — the script never reads a dying parent's pipe
        assertEquals(ProcessBuilder.Redirect.Type.READ, b.redirectInput().type())
        assertEquals("/dev/null", b.redirectInput().file()!!.path)
        // stdout+stderr: ONE append stream into the deploy log — /file can fetch it later
        assertEquals(ProcessBuilder.Redirect.Type.APPEND, b.redirectOutput().type())
        assertSame(log, b.redirectOutput().file())
        assertEquals(true, b.redirectErrorStream())
    }

    @Test
    fun theBuilderRunsFromTheScriptsOwnDirectory() {
        val b = DeploySpawn(script, log).builder()
        assertEquals(script.parentFile, b.directory())
    }

    @Test
    fun theLogLivesInTheDaemonsOwnLogsDir() {
        // the name is the /file contract the spawned-note names
        assertEquals("deploy-daemon.log", DeploySpawn.LOG_FILE_NAME)
        assertTrue(DeploySpawn(script, log).builder().redirectOutput().file()!!.path.endsWith(".log"))
    }

    @Test
    fun theKnobDefaultsToTheRepoCheckoutWhenTheConfigIsSilent() {
        val missing = File("/nonexistent/daemon.json")
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(missing))
        val silent = tempJson("{}")
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(silent))
    }

    @Test
    fun theKnobHonorsTheOverride() {
        val custom = tempJson("""{"send": "live", "deploy_script": "/opt/my-deploy.nu"}""")
        assertEquals(File("/opt/my-deploy.nu"), DeployScriptKnob.load(custom))
    }

    @Test
    fun theKnobDegradesToTheDefaultOnGarbageAndBlank() {
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(tempJson("not json at all")))
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(tempJson("""{"deploy_script": 42}""")))
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(tempJson("""{"deploy_script": "  "}""")))
        // a WRONG shape never throws — the failure would be loud at spawn time, not here
    }

    private fun tempJson(content: String): File =
        File.createTempFile("b6-knob", ".json").apply { writeText(content); deleteOnExit() }
}
