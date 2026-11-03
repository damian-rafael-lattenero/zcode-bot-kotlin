package dev.lattenero.bot.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B6's deploy edge pinned WITHOUT ever touching launchd: the job's plist
 * is the contract. The owner's live /deploy (2/11 22:02) proved the old
 * nohup+ProcessBuilder spawn died at the script's own bootout — launchd
 * tears down the job's whole tree — so the script must be a SIBLING
 * launchd job. The knob's ladder is here too: the default repo script,
 * the daemon.json override, the honest degrade on garbage.
 */
class DeploySpawnTest {

    // a REAL writable tree: spawn() writes the plist and the log marker
    private val tmp: File = Files.createTempDirectory("b6-deploy").toFile()
    private val script = File("/Users/fake/Projects/zcode-bot-kotlin/scripts/deploy-daemon.nu")
    private val log = File(tmp, "logs/deploy-daemon.log")
    private val plist = File(tmp, "state/deploy-job.plist")

    @AfterTest
    fun wipe() {
        tmp.deleteRecursively()
    }

    private fun edge(launchctl: Launchctl = Launchctl { LaunchctlResult(0, "") }) = DeploySpawn(
        script,
        log,
        plist,
        uid = "501",
        javaHome = "/jdk-25",
        home = "/Users/fake",
        launchctl = launchctl,
    )

    @Test
    fun theScriptIsItsOwnLaunchdJobNeverTheDaemonsChild() {
        val p = edge().plist()
        assertContains(p, "<string>${DeploySpawn.JOB_LABEL}</string>")
        // the label is independent of the daemon's (the survival IS the point)
        assertFalse(DeploySpawn.JOB_LABEL == "com.zcode.bot-daemon")
        // one shot: RunAtLoad runs it once; KeepAlive would loop a deploy
        assertContains(p, "<key>RunAtLoad</key>")
        assertFalse(p.contains("<key>KeepAlive</key>"))
    }

    @Test
    fun theJobRunsTheScriptFromItsOwnDirectory() {
        val p = edge().plist()
        assertContains(p, "<string>${script.absolutePath}</string>")
        assertContains(p, "<string>${script.parentFile.path}</string>")
    }

    @Test
    fun thePlistCarriesWhatLaunchdWouldNotInherit() {
        val p = edge().plist()
        // HOME: nu's home dir; JAVA_HOME: gradlew's JVM; PATH: launchctl/stat/cp + the JDK
        assertContains(p, "<string>/Users/fake</string>")
        assertContains(p, "<string>/jdk-25</string>")
        assertContains(p, "/jdk-25/bin")
        assertContains(p, "/usr/bin")
        assertContains(p, "/Users/fake/.local/bin")
    }

    @Test
    fun theLogIsTheJobsStdioAndTheFileNameIsFileSContract() {
        val p = edge().plist()
        assertEquals(2, Regex(Regex.escape(log.absolutePath)).findAll(p).count())
        assertContains(p, "<key>StandardOutPath</key>")
        assertContains(p, "<key>StandardErrorPath</key>")
        assertEquals("deploy-daemon.log", DeploySpawn.LOG_FILE_NAME)
    }

    @Test
    fun spawnBootstrapsASiblingJobAfterClearingAPreviousOne() {
        val calls = mutableListOf<List<String>>()
        val fake = Launchctl { args ->
            calls += args
            LaunchctlResult(0, "")
        }
        val out = edge(fake).spawn()

        assertEquals(0, out.exit)
        assertEquals(2, calls.size)
        // bootout first (bootstrap refuses a loaded label), then bootstrap THIS plist
        assertEquals(listOf("bootout", "gui/501", DeploySpawn.JOB_LABEL), calls[0])
        assertEquals(listOf("bootstrap", "gui/501", plist.absolutePath), calls[1])
        // the rendered plist is on disk — launchctl reads the FILE, not a string
        assertTrue(plist.exists())
        assertContains(plist.readText(), DeploySpawn.JOB_LABEL)
    }

    @Test
    fun aFailedBootstrapThrowsLoudlySoThePhoneHearsIt() {
        val fake = Launchctl { args ->
            if (args.first() == "bootstrap") {
                LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
            } else {
                LaunchctlResult(0, "")
            }
        }
        val e = assertFailsWith<IllegalStateException> { edge(fake).spawn() }
        assertContains(e.message.orEmpty(), "Input/output error")
        assertContains(e.message.orEmpty(), DeploySpawn.JOB_LABEL)
    }

    @Test
    fun theDomainIsTheDaemonsOwnGuiDomain() {
        assertEquals("gui/501", edge().domain())
    }

    @Test
    fun theKnobDefaultsToTheRepoCheckoutWhenTheConfigIsSilent() {
        val missing = File("/nonexistent/daemon.json")
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(missing))
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(tempJson("{}")))
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
