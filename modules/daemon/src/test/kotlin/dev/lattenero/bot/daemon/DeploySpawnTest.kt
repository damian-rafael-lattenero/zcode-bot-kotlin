package dev.lattenero.bot.daemon

import kotlinx.coroutines.test.runTest
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
    fun spawnBootstrapsASiblingJobAfterClearingAPreviousOne() = runTest {
        val calls = mutableListOf<List<String>>()
        val fake = Launchctl { args ->
            calls += args
            LaunchctlResult(0, "")
        }
        val out = edge(fake).spawn()

        assertEquals(0, out.exit)
        assertEquals(3, calls.size)
        // the busy pre-check (`print`) first, then bootout (bootstrap refuses a
        // loaded label), then bootstrap THIS plist
        assertEquals("print", calls[0].first())
        assertEquals(listOf("bootout", "gui/501", DeploySpawn.JOB_LABEL), calls[1])
        assertEquals(listOf("bootstrap", "gui/501", plist.absolutePath), calls[2])
        // the rendered plist is on disk — launchctl reads the FILE, not a string
        assertTrue(plist.exists())
        assertContains(plist.readText(), DeploySpawn.JOB_LABEL)
    }

    // 2026-11-04 hardening v4 — the BUSY GUARD: a RUNNING deploy job gets an
    // honest «ya está corriendo» BEFORE launchd is asked anything — the
    // owner's double-tap at 16:28/16:36 hit launchd's error 5 while the
    // 16:28 fork deploy was mid-build, and the raw error read like a bug.

    @Test
    fun aRunningJobIsRefusedByNameBeforeAnythingFires() = runTest {
        val calls = mutableListOf<List<String>>()
        val fake = Launchctl { args ->
            calls += args
            when (args.first()) {
                "print" -> LaunchctlResult(0, "gui/501/com.zcode.bot-fork-deploy = {\n\tstate = running\n\tpid = 30584\n}")
                else -> LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        )
        val e = assertFailsWith<IllegalStateException> { spawn.spawn() }
        assertContains(e.message.orEmpty(), "ya está CORRIENDO")
        assertContains(e.message.orEmpty(), DeploySpawn.JOB_LABEL)
        // NOTHING touched launchd beyond the pre-check: no bootout, no bootstrap
        assertEquals(0, calls.count { it.first() == "bootout" })
        assertEquals(0, calls.count { it.first() == "bootstrap" })
    }

    // 2026-11-04 hardening v3 — the BOOTOUT has its own ladder: a wedged
    // label (error 5) clears itself seconds later; two taps in a row wedged
    // each other and the single bootout poisoned every bootstrap after it.

    @Test
    fun aWedgedBootoutClearsItselfOnTheNextTry() = runTest {
        var bootouts = 0
        var bootstraps = 0
        val fake = Launchctl { args ->
            when (args.first()) {
                "bootout" -> {
                    bootouts++
                    if (bootouts == 1) LaunchctlResult(5, "Boot-out failed: 5: Input/output error")
                    else LaunchctlResult(0, "")
                }
                "bootstrap" -> {
                    bootstraps++
                    LaunchctlResult(0, "")
                }
                else -> LaunchctlResult(0, "")
            }
        }
        val out = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        ).spawn()
        assertEquals(0, out.exit)
        assertEquals(2, bootouts) // the wedged one + the retry that cleared it
        assertEquals(1, bootstraps) // the bootstrap ran ONCE, against a CLEAN label
    }

    @Test
    fun aNotLoadedBootoutProceedsWithoutBeating() = runTest {
        // rc=3 (No such process) = nothing to clear — the ladder accepts it
        // and goes straight to the bootstrap
        val calls = mutableListOf<List<String>>()
        val beats = mutableListOf<Long>()
        val fake = Launchctl { args ->
            calls += args
            when (args.first()) {
                "bootout" -> LaunchctlResult(3, "Boot-out failed: 3: No such process")
                else -> LaunchctlResult(0, "")
            }
        }
        val out = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = { beats += it },
        ).spawn()
        assertEquals(0, out.exit)
        assertEquals(1, calls.count { it.first() == "bootout" })
        assertEquals(emptyList(), beats) // no retry beat: not-loaded IS the good answer
    }

    @Test
    fun theBootoutLadderIsBounded() = runTest {
        // three wedged bootouts still reach the bootstrap ladder (whose
        // enable-on-5 may cure the label) — but nothing loops forever
        var bootouts = 0
        var enables = 0
        var bootstraps = 0
        val fake = Launchctl { args ->
            when (args.first()) {
                "bootout" -> {
                    bootouts++
                    LaunchctlResult(5, "Boot-out failed: 5: Input/output error")
                }
                "bootstrap" -> {
                    bootstraps++
                    LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
                }
                "enable" -> {
                    enables++
                    LaunchctlResult(0, "")
                }
                else -> LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        )
        assertFailsWith<IllegalStateException> { spawn.spawn() }
        assertEquals(DeploySpawn.BOOTOUT_ATTEMPTS, bootouts)
        assertEquals(1, enables)
        assertEquals(3, bootstraps) // beat-retry + enable-retry, bounded
    }

    @Test
    fun aRacingBootoutIsRetriedOnceSoASecondDeployOfTheDayStillLands() = runTest {
        // measured 2/11 22:20: the job was still loaded from the 22:11 run,
        // bootout had not finished, and the bootstrap bounced with error 5
        var bootstraps = 0
        val beats = mutableListOf<Long>()
        val fake = Launchctl { args ->
            if (args.first() == "bootstrap") {
                bootstraps++
                if (bootstraps == 1) LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
                else LaunchctlResult(0, "")
            } else {
                LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = { beats += it },
        )
        val out = spawn.spawn()
        assertEquals(0, out.exit)
        assertEquals(2, bootstraps)
        assertEquals(listOf(DeploySpawn.BOOTOUT_BEAT_MS), beats)
    }

    @Test
    fun aRaceLostEvenAfterTheSelfHealStillFailsLoudly() = runTest {
        // 2026-11-04 hardening: an error-5 race now gets launchd's own
        // recovery (enable + ONE last bootstrap) — a label that refuses even
        // that still throws, LOUD, and the phone hears it
        var bootstraps = 0
        var enables = 0
        val fake = Launchctl { args ->
            if (args.first() == "bootstrap") {
                bootstraps++
                LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
            } else {
                if (args.first() == "enable") enables++
                LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        )
        assertFailsWith<IllegalStateException> { spawn.spawn() }
        assertEquals(3, bootstraps) // beat-retry + the enable-retry
        assertEquals(1, enables)
    }

    @Test
    fun aFailedBootstrapThrowsLoudlySoThePhoneHearsIt() = runTest {
        val fake = Launchctl { args ->
            if (args.first() == "bootstrap") {
                LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
            } else {
                LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        )
        val e = assertFailsWith<IllegalStateException> { spawn.spawn() }
        assertContains(e.message.orEmpty(), "Input/output error")
        assertContains(e.message.orEmpty(), DeploySpawn.JOB_LABEL)
    }

    @Test
    fun theDomainIsTheDaemonsOwnGuiDomain() {
        assertEquals("gui/501", edge().domain())
    }

    // 2026-11-04 — the FORK edge: its own label, its own log, its own plist —
    // a sibling of the daemon AND of the daemon's deploy job.

    @Test
    fun theForkJobCarriesItsOwnLabelAndLogNeverTheDaemons() {
        val forkScript = File("/Users/fake/Projects/zcode-bot-kotlin/scripts/deploy-fork-and-retire.nu")
        val forkLog = File(tmp, "logs/deploy-fork.log")
        val forkPlist = File(tmp, "state/deploy-fork-job.plist")
        val p = DeploySpawn(
            forkScript,
            forkLog,
            forkPlist,
            label = DeploySpawn.FORK_JOB_LABEL,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
        ).plist()
        assertContains(p, "<string>${DeploySpawn.FORK_JOB_LABEL}</string>")
        assertContains(p, "<string>${forkScript.absolutePath}</string>")
        // the labels never collide — each job bootouts/bootstraps ITSELF only
        assertContains(p, DeploySpawn.FORK_JOB_LABEL)
        assertEquals("com.zcode.bot-fork-deploy", DeploySpawn.FORK_JOB_LABEL)
        assertEquals("deploy-fork.log", DeploySpawn.FORK_LOG_FILE_NAME)
        assertEquals("deploy-fork-job.plist", DeploySpawn.FORK_JOB_PLIST_NAME)
    }

    @Test
    fun theDefaultLabelIsStillTheDaemonsDeployJob() {
        // the no-arg ctor keeps B6's label — the daemon deploy's contract is untouched
        assertContains(edge().plist(), "<string>${DeploySpawn.JOB_LABEL}</string>")
    }

    // 2026-11-04 hardening — args, bootout logging, and the error-5 self-heal.

    @Test
    fun thePlistCarriesTheScriptsArgs() {
        val p = DeploySpawn(
            script,
            log,
            plist,
            args = listOf("--real"),
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
        ).plist()
        // the args ride INSIDE ProgramArguments, after the script
        assertContains(p, "<string>${script.absolutePath}</string>\n        <string>--real</string>")
        // the daemon default carries NO extra args
        assertFalse(edge().plist().contains("--real"))
    }

    @Test
    fun anError5BootstrapEnablesTheLabelAndTriesOnceMore() = runTest {
        // measured 4/11 13:56: the phone's tap failed twice with error 5 — the
        // edge now runs launchd's own recovery recipe (enable + last bootstrap)
        val calls = mutableListOf<List<String>>()
        var bootstraps = 0
        val fake = Launchctl { args ->
            calls += args
            when (args.first()) {
                "bootstrap" -> {
                    bootstraps++
                    if (bootstraps <= 2) LaunchctlResult(5, "Bootstrap failed: 5: Input/output error")
                    else LaunchctlResult(0, "")
                }
                else -> LaunchctlResult(0, "")
            }
        }
        val beats = mutableListOf<Long>()
        val out = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = { beats += it },
        ).spawn()
        assertEquals(0, out.exit)
        // the order IS the recipe: print → bootout → bootstrap ×2 → enable → bootstrap;
        // enable takes ONE service-target arg (gui/<uid>/<label>) — the two-arg
        // form was a silent no-op and the self-heal never healed
        assertEquals(listOf("enable", "gui/501/${DeploySpawn.JOB_LABEL}"), calls[4])
        assertEquals(3, calls.count { it.first() == "bootstrap" })
        // the second beat never ran: the enable-retry is immediate
        assertEquals(listOf(DeploySpawn.BOOTOUT_BEAT_MS), beats)
    }

    @Test
    fun aPlainFailureNeverEnablesAndStillThrows() = runTest {
        // a NON-error-5 failure is not launchd's disabled-label disease —
        // enabling would be cargo-cult; the edge fails LOUD as before
        val calls = mutableListOf<List<String>>()
        val fake = Launchctl { args ->
            calls += args
            if (args.first() == "bootstrap") {
                LaunchctlResult(93, "Bootstrap failed: 93: Operation not permitted")
            } else {
                LaunchctlResult(0, "")
            }
        }
        val spawn = DeploySpawn(
            script,
            log,
            plist,
            uid = "501",
            javaHome = "/jdk-25",
            home = "/Users/fake",
            launchctl = fake,
            beat = {},
        )
        val e = assertFailsWith<IllegalStateException> { spawn.spawn() }
        assertContains(e.message.orEmpty(), "93")
        assertEquals(0, calls.count { it.first() == "enable" })
    }

    @Test
    fun theKnobDefaultsToTheRepoCheckoutWhenTheConfigIsSilent() {
        val missing = File("/nonexistent/daemon.json")
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(missing))
        assertEquals(DeployScriptKnob.DEFAULT, DeployScriptKnob.load(tempJson("{}")))
    }

    @Test
    fun theForkKnobDefaultsToTheForkScriptAndHonorsItsOwnKey() {
        val missing = File("/nonexistent/daemon.json")
        assertEquals(DeployScriptKnob.FORK_DEFAULT, DeployScriptKnob.loadFork(missing))
        assertEquals(DeployScriptKnob.FORK_DEFAULT, DeployScriptKnob.loadFork(tempJson("{}")))
        // its OWN key — the daemon's deploy_script never leaks into the fork's edge
        val custom = tempJson("""{"deploy_fork_script": "/opt/my-fork-deploy.nu"}""")
        assertEquals(File("/opt/my-fork-deploy.nu"), DeployScriptKnob.loadFork(custom))
        // the daemon's key is NOT the fork's key
        val daemonOnly = tempJson("""{"deploy_script": "/opt/daemon-only.nu"}""")
        assertEquals(DeployScriptKnob.FORK_DEFAULT, DeployScriptKnob.loadFork(daemonOnly))
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
        // the fork's knob obeys the same degrade law
        assertEquals(DeployScriptKnob.FORK_DEFAULT, DeployScriptKnob.loadFork(tempJson("not json at all")))
        assertEquals(DeployScriptKnob.FORK_DEFAULT, DeployScriptKnob.loadFork(tempJson("""{"deploy_fork_script": "  "}""")))
        // a WRONG shape never throws — the failure would be loud at spawn time, not here
    }

    private fun tempJson(content: String): File =
        File.createTempFile("b6-knob", ".json").apply { writeText(content); deleteOnExit() }
}
