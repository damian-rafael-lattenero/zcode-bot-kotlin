package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.CH_REPLY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The /retry ammo's store: one JSON object, one atomic write, honest
 * reads — a missing file, torn JSON or a wrong shape answers null (the
 * honest «nada para relanzar»), never an exception, never an invention.
 */
class LastPromptTest {

    /** A fresh store on a temp dir; the pair is (dir, store) for the cleanup lines. */
    private fun tempStore(): Pair<java.nio.file.Path, LastPrompt> {
        val dir = Files.createTempDirectory("last-prompt-test")
        return dir to LastPrompt(dir.resolve("last-prompt.json").toFile())
    }

    @Test
    fun saveThenLoadRoundTripsTheVerbatimPrompt() {
        val (dir, store) = tempStore()
        store.save("task-77", "arreglá el login del bot", 1_000_000L)
        val saved = store.load()!!
        assertEquals("task-77", saved.sid)
        assertEquals("arreglá el login del bot", saved.text)
        assertEquals(1_000_000L, saved.ts)
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }

    @Test
    fun aNewSaveOverwritesTheOldPrompt() {
        val (dir, store) = tempStore()
        store.save("task-77", "first", 1L)
        store.save("task-02", "second — the only one /retry knows", 2L)
        val saved = store.load()!!
        assertEquals("task-02", saved.sid)
        assertEquals("second — the only one /retry knows", saved.text)
        assertEquals(2L, saved.ts)
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }

    @Test
    fun missingFileAnswersNull() {
        val (dir, store) = tempStore()
        assertNull(store.load(), "no prompt yet — the honest null")
        Files.deleteIfExists(dir)
    }

    @Test
    fun tornJsonAnswersNullNeverThrows() {
        val (dir, store) = tempStore()
        dir.resolve("last-prompt.json").toFile().writeText("""{"sid":"task-77","text":"trunc""")
        assertNull(store.load(), "torn JSON = nothing saved, honestly")
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }

    @Test
    fun wrongShapeAnswersNull() {
        val (dir, store) = tempStore()
        val file = dir.resolve("last-prompt.json").toFile()
        file.writeText("""["not","an","object"]""")
        assertNull(store.load(), "a JSON array is not a prompt")
        file.writeText("""{"text":""}""") // a blank text is no prompt at all
        assertNull(store.load())
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }

    @Test
    fun theSavedTextIsCappedAtChReplyLikeTheOutbox() {
        val (dir, store) = tempStore()
        store.save("task-77", "x".repeat(CH_REPLY + 500), 1L)
        assertEquals(CH_REPLY, store.load()!!.text.length, "the cap matches the outbox's own law")
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }

    @Test
    fun theFileCarriesTheSidTextTsContract() {
        val (dir, store) = tempStore()
        store.save("task-77", "hola", 42L)
        val root = Json.parseToJsonElement(dir.resolve("last-prompt.json").toFile().readText()).jsonObject
        assertEquals("task-77", root["sid"]!!.jsonPrimitive.content)
        assertEquals("hola", root["text"]!!.jsonPrimitive.content)
        assertEquals(42L, root["ts"]!!.jsonPrimitive.longOrNull)
        assertTrue(root.containsKey("ts"), "the contract is exactly {sid, text, ts}")
        Files.deleteIfExists(dir.resolve("last-prompt.json"))
        Files.deleteIfExists(dir)
    }
}
