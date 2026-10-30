package dev.lattenero.bot.daemon.send

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.transport.TgConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The firehose lane's wiring contract: with the token the render traffic
 * rides its OWN throat (files, view, cooldown window); without it the
 * lanes are ONE — the pre-split behavior byte for byte. The park
 * isolation is pinned with REAL files: the cooldown pre-check parks
 * before any wire is touched — zero network.
 */
class LanesTest {

    @TempDir
    lateinit var base: File

    private val pythonState: File get() = File(base, "state").apply { mkdirs() }
    private val remoteFile: File get() = File(base, "telegram-remote.json")

    private fun config(token: String, firehose: String = "") = remoteFile.writeText(
        buildJsonObject {
            put("enabled", true)
            put("token", token)
            if (firehose.isNotEmpty()) put("firehose_token", firehose)
            put("chat_id", 4242)
        }.toString(),
    )

    @Test
    fun withoutTheTokenTheLanesAreOne() = runBlocking {
        config(token = "MAIN")
        val lanes = buildLanes(SendMode.LIVE, { remoteFile }, pythonState)
        assertTrue(lanes.firehose === lanes.leg, "no split: ONE leg, the identity")
        assertTrue(lanes.live!!.leg === lanes.leg)
        // the live lane watches the SHARED files — the pre-split behavior
        lanes.live.cooldown.extend(30)
        assertTrue(File(pythonState, LaneFiles.COOLDOWN).exists(), "the shared file, not a firehose twin")
        assertEquals("MAIN", lanes.live.config().token)
    }

    @Test
    fun withTheTokenTheRenderLaneIsItsOwnThroat() = runBlocking {
        config(token = "MAIN", firehose = "FIRE")
        val lanes = buildLanes(SendMode.LIVE, { remoteFile }, pythonState)
        assertTrue(lanes.firehose !== lanes.leg, "split: two throats")
        assertTrue(lanes.live!!.leg === lanes.firehose)
        assertTrue(lanes.live.api !== lanes.api)
        // the view answers the firehose token's config (same chat, other token)
        assertEquals("FIRE", lanes.live.config().token)
        assertEquals(TgConfig.load(remoteFile).chatId, lanes.live.config().chatId)
    }

    @Test
    fun theFirehoseWindowParksRendersWhileTheCommandsOutboxStaysClean() = runBlocking {
        config(token = "MAIN", firehose = "FIRE")
        val lanes = buildLanes(SendMode.LIVE, { remoteFile }, pythonState)
        // a firehose 429 window opens: the file's MTIME is the expiry epoch
        val window = File(pythonState, LaneFiles.FIRE_COOLDOWN)
        window.createNewFile()
        window.setLastModified(System.currentTimeMillis() + 60_000)

        // a render send parks BEFORE any wire is touched — its OWN outbox holds it
        lanes.firehose.deliverMarkup(
            ShadowDecision(ts = 1.0, tsIso = "t", watcher = "spectator", decision = "say", text = "render flood"),
            loud = false,
            markup = null,
        )
        assertTrue("render flood" in File(pythonState, LaneFiles.FIRE_OUTBOX).readText())
        // the commands lane's outbox never saw it — /stop would still go out
        assertEquals(false, File(pythonState, LaneFiles.OUTBOX).exists())
    }

    @Test
    fun dryRunCarriesTwoLegsToo() {
        config(token = "MAIN", firehose = "FIRE")
        val lanes = buildLanes(SendMode.DRY_RUN, { remoteFile }, pythonState)
        assertTrue(lanes.firehose !== lanes.leg, "the dry-run firehose logs its OWN redacted url")
        assertEquals(null, lanes.live)
        assertEquals(null, lanes.api)
    }

    @Test
    fun theFilesAreTheNamedContract() {
        assertEquals("tg-cooldown-firehose", LaneFiles.FIRE_COOLDOWN)
        assertEquals("outbox-firehose.json", LaneFiles.FIRE_OUTBOX)
        assertEquals("tg-last-ph-firehose", LaneFiles.FIRE_LAST_PH)
    }
}
