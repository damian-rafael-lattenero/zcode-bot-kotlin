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
 * B6 — /deploy's wiring half pinned, THE ONE-TAP LAW (the owner's order,
 * 4/11): THE DOOR's order (the row dies → announce FIRST → spawn AFTER —
 * never reversed), the door's receipt law (an announce that did not land
 * refuses the spawn — no blind restarts), the LOUD failure when the spawn
 * edge throws, the owner gate with ZERO spawns on refusal, the button row
 * dying on the card (the edit called with the card's own born id), the
 * in-flight refusal after a launch that landed, and BOTH spawning fork
 * FIRST (a sibling job survives the daemon deploy's own bootout). The
 * `say`, `edit` and `spawn` effects are recorders — no Telegram, no
 * process, no clock sleep.
 */
class DeployFlowTest {

    /** The recorder: what was said, what was edited, what landed, what spawned. */
    private inner class Rig(
        var announceLands: Long? = 101L,
        private val spawnThrows: Exception? = null,
        private val editWorks: Boolean = true,
    ) {
        val spoken = mutableListOf<Triple<String, Boolean, JsonObject?>>()
        val events = mutableListOf<String>() // the ORDER's witness: "edit"/"say"/"spawn" interleave
        val spawnedTargets = mutableListOf<Target>()
        val edits = mutableListOf<Triple<Long, String, JsonObject?>>()

        val say: suspend (String, Boolean, JsonObject?) -> Long? = { text, loud, markup ->
            spoken.add(Triple(text, loud, markup))
            events.add("say:${text.take(24)}")
            announceLands
        }

        val edit: suspend (Long, String, JsonObject?) -> Boolean = { messageId, text, markup ->
            edits.add(Triple(messageId, text, markup))
            events.add("edit:$messageId")
            editWorks
        }

        val spawn: suspend (Target) -> Unit = { target ->
            events.add("spawn:$target")
            spawnThrows?.let { throw it }
            spawnedTargets += target
        }
    }

    private fun flow(rig: Rig, owner: Long? = OWNER): DeployFlow =
        DeployFlow(
            machine = DeployCommand(),
            say = rig.say,
            edit = rig.edit,
            spawn = rig.spawn,
            ownerUserId = { owner },
        )

    @Test
    fun theBareCardOffersThreeTargetsAndFiresNothing() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER)
        assertEquals(1, rig.spoken.size)
        val (text, loud, markup) = rig.spoken.single()
        assertTrue(text.startsWith("🚀 <b>Deploy</b>"), text)
        assertTrue(text.contains("Tocar ES lanzar"), "the one-tap law is ON the card")
        assertEquals(false, loud)
        val row = markup!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray
        assertEquals(3, row.size)
        assertEquals("dep:bot", row[0].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:fork", row[1].jsonObject["callback_data"]!!.jsonPrimitive.content)
        assertEquals("dep:both", row[2].jsonObject["callback_data"]!!.jsonPrimitive.content)
        // the retired go/no NEVER fires: a stale button just re-shows the card
        f.onTap("go", OWNER)
        f.onTap("no", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.TARGETS_CARD, rig.spoken.last().first)
    }

    @Test
    fun aTargetTapFiresTheDoorImmediately() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("fork", OWNER) // the tap IS the confirmation — no second step exists
        assertEquals(listOf(Target.FORK), rig.spawnedTargets)
        assertEquals(2, rig.spoken.size) // announce, spawned-note — no confirm card anywhere
        assertEquals(DeployFlow.THE_ANNOUNCE_FORK, rig.spoken[0].first)
        assertEquals(true, rig.spoken[0].second) // the door is LOUD
        assertNull(rig.spoken[0].third)
        // THE ORDER: the announce is in the record BEFORE the spawn ran
        assertEquals("say", rig.events[0].substringBefore(':'))
        assertEquals("spawn:FORK", rig.events[1])
    }

    @Test
    fun aTargetTextFiresExactlyThatTarget() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("bot", OWNER)
        assertEquals(listOf(Target.BOT), rig.spawnedTargets)
        assertEquals(DeployFlow.THE_ANNOUNCE, rig.spoken[0].first)
    }

    @Test
    fun bothSpawnsTheForkFirstThenTheDaemon() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("both", OWNER)
        // the spawn ORDER is the survival law: fork's job is armed before
        // the daemon's deploy boots out this daemon
        assertEquals(listOf(Target.FORK, Target.BOT), rig.spawnedTargets)
        assertEquals(DeployFlow.THE_ANNOUNCE_BOTH, rig.spoken[0].first)
        assertEquals("spawn:FORK", rig.events[1])
        assertEquals("spawn:BOT", rig.events[2])
    }

    @Test
    fun theButtonRowDiesOnTheCardTheTapCameFrom() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("", OWNER) // the card was born with id 101 (the say receipt)
        f.onTap("bot", OWNER)
        assertEquals(1, rig.edits.size)
        val (messageId, text, markup) = rig.edits.single()
        assertEquals(101L, messageId, "the edit targets the CARD's own born message")
        assertEquals("🚀 <b>Deploy</b>\n${DeployFlow.FLOWN_BOT}", text)
        assertNull(markup, "the keyboard is stripped — the row dies")
        // the edit lands BEFORE the announce: the row must be dead before anything kills this process
        assertEquals("edit:101", rig.events[1])
        assertEquals("say", rig.events[2].substringBefore(':'))
        assertEquals("spawn:BOT", rig.events[3])
    }

    @Test
    fun aFailedEditNeverBlocksTheDoor() = runTest {
        val rig = Rig(editWorks = false)
        val f = flow(rig)
        f.onText("", OWNER)
        f.onTap("fork", OWNER)
        assertEquals(1, rig.edits.size) // the edit was attempted and failed
        assertEquals(listOf(Target.FORK), rig.spawnedTargets, "best-effort: the deploy flies anyway")
    }

    @Test
    fun noCardEverBornMeansNoEditIsAttempted() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER) // the card was never shown — nothing to strip
        assertEquals(0, rig.edits.size)
        assertEquals(listOf(Target.BOT), rig.spawnedTargets)
    }

    @Test
    fun anAnnounceThatDidNotLandRefusesTheSpawn() = runTest {
        val rig = Rig(announceLands = null) // shadow / dry-run / parked / failed — nothing flew
        val f = flow(rig)
        f.onTap("bot", OWNER)
        assertEquals(0, rig.spawnedTargets.size) // NO blind restarts
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("⚠️ No pude anunciar el deploy"), last.first)
        assertEquals(true, last.second) // the refusal is LOUD — the owner must learn why nothing happened
    }

    @Test
    fun aFailedLaunchLeavesTheShotFreeForTheHonestRetry() = runTest {
        val rig = Rig(spawnThrows = IllegalStateException("nohup missing"))
        val f = flow(rig)
        f.onTap("fork", OWNER)
        // the announce STILL flew first — the phone heard the door before the failure
        assertEquals(DeployFlow.THE_ANNOUNCE_FORK, rig.spoken[0].first)
        val last = rig.spoken.last()
        assertTrue(last.first.startsWith("❌ deploy:"), last.first)
        assertTrue(last.first.contains("nohup missing"))
        assertEquals(true, last.second) // LOUD
        // the retry: the same tap fires again (the shot was never spent)
        f.onTap("fork", OWNER)
        assertEquals(2, rig.events.count { it == "spawn:FORK" }, "a failed launch is not a spent one")
    }

    @Test
    fun aLandedLaunchRefusesEveryFurtherTarget() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        assertEquals(1, rig.spawnedTargets.size)
        f.onTap("fork", OWNER) // impatience
        f.onTap("both", OWNER)
        assertEquals(1, rig.spawnedTargets.size, "STILL one — the one-shot never fires twice")
        assertEquals(DeployFlow.IN_FLIGHT, rig.spoken.last().first)
        assertTrue(rig.spoken.last().first.contains("deploy en vuelo"), "the honest refusal")
    }

    @Test
    fun aNonOwnersTapIsRefusedWithZeroSpawns() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", INTRUDER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(1, rig.spoken.size) // refusal only — no announce, no spawned-note
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.single().first)
        assertEquals(true, rig.spoken.single().second)
        // the owner's tap right after fires normally
        f.onTap("bot", OWNER)
        assertEquals(listOf(Target.BOT), rig.spawnedTargets)
    }

    @Test
    fun aConfigWithoutOwnerFailsClosedAtTheWiring() = runTest {
        val rig = Rig()
        val f = flow(rig, owner = null)
        f.onTap("bot", OWNER)
        assertEquals(0, rig.spawnedTargets.size)
        assertEquals(DeployFlow.NOT_OWNER, rig.spoken.single().first)
    }

    @Test
    fun tapsAndTextsRideTheSameDoor() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onText("fork", OWNER)
        assertEquals(listOf(Target.FORK), rig.spawnedTargets)
        // an unknown dep: word never spawns — the card again
        val rig2 = Rig()
        val f2 = flow(rig2)
        f2.onTap("maybe", OWNER)
        assertEquals(0, rig2.spawnedTargets.size)
        assertEquals(DeployFlow.TARGETS_CARD, rig2.spoken.single().first)
    }

    @Test
    fun theSpawnedNoteNamesTheLogTheOwnerCanFetch() = runTest {
        val rig = Rig()
        val f = flow(rig)
        f.onTap("bot", OWNER)
        assertEquals(DeployFlow.SPAWNED, rig.spoken.last().first)
        assertTrue("deploy-daemon.log" in rig.spoken.last().first)
        assertNull(rig.spoken.last().third) // text-only — the door already flew
        // the fork's note names ITS OWN log — the two deploys never mix words
        val rig2 = Rig()
        val f2 = flow(rig2)
        f2.onTap("fork", OWNER)
        assertTrue("deploy-fork.log" in rig2.spoken.last().first)
    }

    private companion object {
        const val OWNER = 7L
        const val INTRUDER = 13L
    }
}
