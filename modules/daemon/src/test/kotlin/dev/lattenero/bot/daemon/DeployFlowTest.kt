package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 — /deploy's wiring half pinned: THE DOOR's order (announce FIRST,
 * spawn AFTER — never reversed), the door's receipt law (an announce that
 * did not land refuses the spawn — no blind restarts), the LOUD failure
 * when the spawn edge throws, the owner gate with ZERO spawns on refusal,
 * the expiry and the double-tap, and the card's keyboard on the arm. The
 * `say` and `spawn` effects are recorders — no Telegram, no process, no
 * clock sleep (the machine's clock is the class's own [NOW]).
 */
class DeployFlowTest {

    /** The injected clock — one class instance per test method, so it never leaks. */
    private var NOW = 1_000_000L

    /** The recorder: what was said (text, loud, markup), what landed, what spawned. */
    private inner class Rig(var announceLands: Long? = 101L, private val spawnThrows: Exception? = null) {
        val spoken = mutableListOf<Triple<String, Boolean, JsonObject?>>()
        val events = mutableListOf<String>() // the ORDER's witness: "say" and "spawn" interleave
        var spawns = 0

        val say: suspend (String, Boolean, JsonObject?) -> Long? = { text, loud, markup ->
            spoken.add(Triple(text, loud, markup))
            events.add("say:${text.take(24)}")
            announceLands
        }

        val spawn: () -> Unit = {
            events.add("spawn")
            spawnThrows?.let { throw it }
            spawns++
        }
    }

    private fun flow(rig: Rig, owner: Long? = OWNER): DeployFlow =
        DeployFlow(
            machine = DeployCommand(clock = { NOW }),
            say = rig.say,
            spawn = rig.spawn,
            ownerUserId = { owner },
        )

    @Test
    fun theCardArmsTheMachineAndSpeaksTheConfirmKeyboard() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        assertEquals(1, rig.spoken.size)
        val (text, loud, markup) = rig.spoken.single()
        assertTrue(text.startsWith("🚀 <b>Deploy del daemon</b>"))
        assertEquals(false, loud)
        val row = markup!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("dep:go", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:no", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // the arm is real: the owner's TAP lands without a second card
        f.onTap("go", OWNER)
        assertEquals(1, rig.spawns)
    }

    @Test
    fun aConfirmedGoAnnouncesBeforeItSpawns() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER) // arm
        f.onText("go", OWNER) // confirm
        assertEquals(1, rig.spawns)
        assertEquals(3, rig.spoken.size) // card, announce, spawned-note
        assertEquals(DeployFlow.THE_ANNOUNCE, rig.spoken[1].first)
        assertEquals(true, rig.spoken[1].second) // the door is LOUD
        assertNull(rig.spoken[1].third)
        // THE ORDER: the announce is in the record BEFORE the spawn ran
        assertEquals("say", rig.events[1].substringBefore(':'))
        assertEquals("spawn", rig.events[2])
    }

    @Test
    fun anAnnounceThatDidNotLandRefusesTheSpawn() = runTest {
        val rig = Rig(announceLands = null) // shadow / dry-run / parked / failed — nothing flew
        val f = flow(rig)
        f.onText("", OWNER)
        f.onText("go", OWNER)
        assertEquals(0, rig.spawns) // NO blind restarts
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("⚠️ No pude anunciar el deploy"))
        assertEquals(true, last.second) // the refusal is LOUD — the owner must learn why nothing happened
    }

    @Test
    fun aSpawnThatThrowsSpeaksLoudAndTheDaemonStaysUp() = runTest {
        val rig = Rig(spawnThrows = IllegalStateException("nohup missing"))
        val f = flow(rig)
        f.onText("", OWNER)
        f.onText("go", OWNER)
        // the announce STILL flew first — the phone heard the door before the failure
        assertEquals(DeployFlow.THE_ANNOUNCE, rig.spoken[1].first)
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("❌ deploy:"))
        assertTrue(last.first.contains("nohup missing"))
        assertEquals(true, last.second) // LOUD
        assertEquals(0, rig.spawns)
    }

    @Test
    fun aNonOwnersGoIsRefusedWithZeroSpawnsAndTheArmSurvives() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        f.onText("go", INTRUDER)
        assertEquals(0, rig.spawns)
        assertEquals(2, rig.spoken.size) // card + refusal — no announce, no spawned-note
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.last().first)
        // the owner's arm was NOT consumed by the stranger's tap
        f.onText("go", OWNER)
        assertEquals(1, rig.spawns)
    }

    @Test
    fun aConfigWithoutOwnerFailsClosedAtTheWiring() = runTest {
        val rig = Rig()
        val f = flow(rig, owner = null)
        f.onText("", OWNER)
        f.onText("go", OWNER)
        assertEquals(0, rig.spawns)
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.last().first)
    }

    @Test
    fun anExpiredGoSpeaksTheExpiryAndNeverSpawns() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER) // armed at NOW
        NOW += DeployCommand.WINDOW_MS + 1 // the window closed
        f.onText("go", OWNER)
        assertEquals(0, rig.spawns)
        assertTrue(rig.spoken.last().first.startsWith("⌛"))
        // the expired arm is consumed: a fresh tap reads as nothing armed
        NOW += 1000
        f.onText("go", OWNER)
        assertEquals(0, rig.spawns)
        assertEquals(DeployFlow.NOT_ARMED, rig.spoken.last().first)
    }

    @Test
    fun aDoubleGoAfterTheConfirmIsRefused() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        f.onText("go", OWNER)
        assertEquals(1, rig.spawns)
        f.onText("go", OWNER) // impatience
        assertEquals(1, rig.spawns) // STILL one — the spent arm never fires twice
        assertEquals(DeployFlow.NOT_ARMED, rig.spoken.last().first)
    }

    @Test
    fun theNoCancelsWithoutTouchingTheSpawner() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        f.onTap("no", OWNER)
        assertEquals(0, rig.spawns)
        assertEquals(DeployFlow.CANCELLED, rig.spoken.last().first)
        assertEquals(false, rig.spoken.last().second)
    }

    @Test
    fun tapsAndTextsRideTheSameMachine() = runTest {
        // a whitespace rest IS the bare card (Python's rest ladder), and the
        // go arrives as a TAP — the two transports share one arm
        val rig = Rig()
        val f = flow(rig)
        f.onText("   ", OWNER)
        f.onTap("go", OWNER)
        assertEquals(1, rig.spawns)
        // an unknown dep: word never spawns and never spends the window
        val rig2 = Rig()
        val f2 = flow(rig2)
        f2.onText("", OWNER)
        f2.onTap("maybe", OWNER)
        assertEquals(0, rig2.spawns)
        assertEquals(DeployFlow.NOT_ARMED, rig2.spoken.last().first)
        f2.onTap("go", OWNER) // the arm survived the unknown word
        assertEquals(1, rig2.spawns)
    }

    @Test
    fun theSpawnedNoteNamesTheLogTheOwnerCanFetch() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        f.onText("go", OWNER)
        val note = rig.spoken.last()
        assertEquals(DeployFlow.SPAWNED, note.first)
        assertTrue("deploy-daemon.log" in note.first)
        assertNull(note.third) // text-only — the door already flew
    }

    private companion object {
        const val OWNER = 7L
        const val INTRUDER = 13L
    }
}
