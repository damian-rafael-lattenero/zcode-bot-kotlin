package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.DeployCommand
import dev.lattenero.bot.channel.DeployCommand.Target
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
 * the expiry and the double-tap. 2026-11-04: the TARGET contract — bare
 * /deploy offers 🤖 bot / 🍴 fork / 🚀 both and arms NOTHING, the tap
 * arms THAT target, the go fires exactly it, and BOTH spawns fork FIRST
 * (a sibling job survives the daemon deploy's own bootout). The `say` and
 * `spawn` effects are recorders — no Telegram, no process, no clock sleep.
 */
class DeployFlowTest {

    /** The injected clock — one class instance per test method, so it never leaks. */
    private var NOW = 1_000_000L

    /** The recorder: what was said (text, loud, markup), what landed, what spawned. */
    private inner class Rig(var announceLands: Long? = 101L, private val spawnThrows: Exception? = null) {
        val spoken = mutableListOf<Triple<String, Boolean, JsonObject?>>()
        val events = mutableListOf<String>() // the ORDER's witness: "say" and "spawn" interleave
        val spawnedTargets = mutableListOf<Target>()

        val say: suspend (String, Boolean, JsonObject?) -> Long? = { text, loud, markup ->
            spoken.add(Triple(text, loud, markup))
            events.add("say:${text.take(24)}")
            announceLands
        }

        val spawn: suspend (Target) -> Unit = { target ->
            events.add("spawn:$target")
            spawnThrows?.let { throw it }
            spawnedTargets += target
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
    fun theBareCardOffersThreeTargetsAndArmsNothing() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        assertEquals(1, rig.spoken.size)
        val (text, loud, markup) = rig.spoken.single()
        assertTrue(text.startsWith("🚀 <b>Deploy</b>"))
        assertEquals(false, loud)
        val row = markup!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals(3, row.size)
        assertEquals("dep:bot", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:fork", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:both", row[2].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // NOTHING is armed yet: a stray go never spawns — the choice comes first
        f.onTap("go", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.NOT_ARMED, rig.spoken.last().first)
    }

    @Test
    fun aTargetTapArmsThatTargetAndShowsItsConfirmCard() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("fork", OWNER)
        assertEquals(1, rig.spoken.size)
        val (text, _, markup) = rig.spoken.single()
        assertTrue(text.startsWith("🍴 <b>Deploy del fork de ZCode</b>"), text)
        assertTrue(text.contains("⌘Q"), "the fork's honest arc is on the card: build now, the swap waits for the owner's ⌘Q")
        val row = markup!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals("dep:go", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:no", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        f.onTap("go", OWNER)
        assertEquals(listOf(Target.FORK), rig.spawnedTargets)
    }

    @Test
    fun aConfirmedGoAnnouncesBeforeItSpawns() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER) // arm
        f.onTap("go", OWNER) // confirm
        assertEquals(listOf(Target.BOT), rig.spawnedTargets)
        assertEquals(3, rig.spoken.size) // confirm card, announce, spawned-note
        assertEquals(DeployFlow.THE_ANNOUNCE, rig.spoken[1].first)
        assertEquals(true, rig.spoken[1].second) // the door is LOUD
        assertNull(rig.spoken[1].third)
        // THE ORDER: the announce is in the record BEFORE the spawn ran
        assertEquals("say", rig.events[1].substringBefore(':'))
        assertEquals("spawn:BOT", rig.events[2])
    }

    @Test
    fun bothSpawnsTheForkFirstThenTheDaemon() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("both", OWNER)
        val (text, _, _) = rig.spoken.single()
        assertTrue(text.startsWith("🚀 <b>Deploy de AMBOS</b>"), text)
        f.onTap("go", OWNER)
        // the spawn ORDER is the survival law: fork's job is armed before
        // the daemon's deploy boots out this daemon
        assertEquals(listOf(Target.FORK, Target.BOT), rig.spawnedTargets)
        assertEquals(DeployFlow.THE_ANNOUNCE_BOTH, rig.spoken[1].first)
        assertEquals("spawn:FORK", rig.events[2])
        assertEquals("spawn:BOT", rig.events[3])
    }

    @Test
    fun anAnnounceThatDidNotLandRefusesTheSpawn() = runTest {
        val rig = Rig(announceLands = null) // shadow / dry-run / parked / failed — nothing flew
        val f = flow(rig)
        f.onTap("bot", OWNER)
        f.onTap("go", OWNER)
        assertEquals(0, rig.spawnedTargets.size) // NO blind restarts
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("⚠️ No pude anunciar el deploy"))
        assertEquals(true, last.second) // the refusal is LOUD — the owner must learn why nothing happened
    }

    @Test
    fun aSpawnThatThrowsSpeaksLoudAndTheDaemonStaysUp() = runTest {
        val rig = Rig(spawnThrows = IllegalStateException("nohup missing"))
        val f = flow(rig)
        f.onTap("fork", OWNER)
        f.onTap("go", OWNER)
        // the announce STILL flew first — the phone heard the door before the failure
        assertEquals(DeployFlow.THE_ANNOUNCE_FORK, rig.spoken[1].first)
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("❌ deploy:"))
        assertTrue(last.first.contains("nohup missing"))
        assertEquals(true, last.second) // LOUD
        assertEquals(0, rig.spawnedTargets.size)
    }

    @Test
    fun aNonOwnersGoIsRefusedWithZeroSpawnsAndTheArmSurvives() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        f.onTap("go", INTRUDER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(2, rig.spoken.size) // confirm card + refusal — no announce, no spawned-note
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.last().first)
        // the owner's arm was NOT consumed by the stranger's tap
        f.onTap("go", OWNER)
        assertEquals(listOf(Target.BOT), rig.spawnedTargets)
    }

    @Test
    fun aConfigWithoutOwnerFailsClosedAtTheWiring() = runTest {
        val rig = Rig()
        val f = flow(rig, owner = null)
        f.onTap("bot", OWNER)
        f.onTap("go", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.last().first)
    }

    @Test
    fun anExpiredGoSpeaksTheExpiryAndNeverSpawns() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER) // armed at NOW
        NOW += DeployCommand.WINDOW_MS + 1 // the window closed
        f.onTap("go", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertTrue(rig.spoken.last().first.startsWith("⌛"))
        // the expired arm is consumed: a fresh tap reads as nothing armed
        NOW += 1000
        f.onTap("go", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.NOT_ARMED, rig.spoken.last().first)
    }

    @Test
    fun aDoubleGoAfterTheConfirmIsRefused() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        f.onTap("go", OWNER)
        assertEquals(1, rig.spawnedTargets.size)
        f.onTap("go", OWNER) // impatience
        assertEquals(1, rig.spawnedTargets.size) // STILL one — the spent arm never fires twice
        assertEquals(DeployFlow.NOT_ARMED, rig.spoken.last().first)
    }

    @Test
    fun theNoCancelsWithoutTouchingTheSpawner() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        f.onTap("no", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.CANCELLED, rig.spoken.last().first)
        assertEquals(false, rig.spoken.last().second)
    }

    @Test
    fun tapsAndTextsRideTheSameMachine() = runTest {
        // a TEXT target word arms directly — "/deploy fork" IS the fork arm
        val rig = Rig()
        val f = flow(rig)
        f.onText("fork", OWNER)
        f.onTap("go", OWNER)
        assertEquals(listOf(Target.FORK), rig.spawnedTargets)
        // an unknown dep: word never spawns and never spends the window
        val rig2 = Rig()
        val f2 = flow(rig2)
        f2.onTap("bot", OWNER)
        f2.onTap("maybe", OWNER)
        assertEquals(0, rig2.spawnedTargets.size)
        assertEquals(DeployFlow.NOT_ARMED, rig2.spoken.last().first)
        f2.onTap("go", OWNER) // the arm survived the unknown word
        assertEquals(listOf(Target.BOT), rig2.spawnedTargets)
    }

    @Test
    fun aReSelectionReplacesTheArm() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("both", OWNER)
        f.onTap("fork", OWNER) // the newest choice is the only trusted one
        f.onTap("go", OWNER)
        assertEquals(listOf(Target.FORK), rig.spawnedTargets) // BOTH never fired
    }

    @Test
    fun theSpawnedNoteNamesTheLogTheOwnerCanFetch() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        f.onTap("go", OWNER)
        assertEquals(DeployFlow.SPAWNED, rig.spoken.last().first)
        assertTrue("deploy-daemon.log" in rig.spoken.last().first)
        assertNull(rig.spoken.last().third) // text-only — the door already flew
        // the fork's note names ITS OWN log — the two deploys never mix words
        val rig2 = Rig()
        val f2 = flow(rig2)
        f2.onTap("fork", OWNER)
        f2.onTap("go", OWNER)
        assertTrue("deploy-fork.log" in rig2.spoken.last().first)
    }

    private companion object {
        const val OWNER = 7L
        const val INTRUDER = 13L
    }
}
