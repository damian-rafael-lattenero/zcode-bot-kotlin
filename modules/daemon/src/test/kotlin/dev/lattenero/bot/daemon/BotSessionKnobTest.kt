package dev.lattenero.bot.daemon

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 2026-11-05 — the dedicated-conversation knob pinned: the daemon.json key,
 * the degrade law (any unreadable shape = no pin, never a crash). The pin's
 * ROUTING layering lives in RoutingPinnedTest (channel); the daemon wiring
 * only reads the key and hands the sid to BotCommands.
 */
class BotSessionKnobTest {

    private val tmp: File = Files.createTempDirectory("bot-session-knob").toFile()

    @AfterTest
    fun wipe() {
        tmp.deleteRecursively()
    }

    private fun knobJson(content: String): File =
        File(tmp, "daemon.json").apply { writeText(content) }

    @Test
    fun thePinReadsItsKey() {
        assertEquals(
            "sess_abc123",
            BotSessionKnob.load(knobJson("""{"send": "live", "bind_task_id": "sess_abc123"}""")),
        )
    }

    @Test
    fun silenceDegradesToNoPin() {
        assertNull(BotSessionKnob.load(knobJson("""{"send": "live"}""")))
        assertNull(BotSessionKnob.load(knobJson("{}")))
        assertNull(BotSessionKnob.load(File(tmp, "missing.json")))
    }

    @Test
    fun garbageDegradesToNoPin() {
        assertNull(BotSessionKnob.load(knobJson("not json at all")))
        assertNull(BotSessionKnob.load(knobJson("""{"bind_task_id": 42}""")))
        assertNull(BotSessionKnob.load(knobJson("""{"bind_task_id": "   "}""")))
    }
}
