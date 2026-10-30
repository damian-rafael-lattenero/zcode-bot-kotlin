package dev.lattenero.bot.transport

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The transport's slice of telegram-remote.json (zcode_bot.config):
 * load_config's fallback shape and config.configured()'s Python
 * truthiness — the same gates the Python send() checks before talking
 * to anyone.
 */
class TgConfigTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun loadsTheRealFileShape() {
        val f = File(dir, "telegram-remote.json")
        f.writeText(
            """
            {"enabled": true, "token": "abc123", "chat_id": 555, "loud_all": false}
            """.trimIndent(),
        )
        val cfg = TgConfig.load(f)
        assertTrue(cfg.enabled)
        assertEquals("abc123", cfg.token)
        assertEquals(JsonPrimitive(555), cfg.chatId, "chat_id keeps its file type — the wire param passes through as-is")
        assertFalse(cfg.loudAll)
        assertTrue(cfg.configured)
    }

    @Test
    fun aBrokenOrMissingFileIsTheDefaultsNotACrash() {
        val broken = File(dir, "broken.json").apply { writeText("{ this is not json") }
        val defaults = TgConfig.load(broken)
        assertTrue(defaults.enabled, "load_config's fallback: enabled defaults ON")
        assertEquals("", defaults.token)
        assertTrue(defaults.loudAll)
        assertFalse(defaults.configured)
        assertFalse(TgConfig.load(File(dir, "missing.json")).configured)
    }

    @Test
    fun configuredIsPythonsTruthinessTripleGate() {
        assertFalse(TgConfig(enabled = false, token = "t", chatId = JsonPrimitive(1)).configured)
        assertFalse(TgConfig(token = "", chatId = JsonPrimitive(1)).configured)
        // Python truthiness: 0, "" and false are no chat
        assertFalse(TgConfig(token = "t", chatId = JsonPrimitive(0)).configured)
        assertFalse(TgConfig(token = "t", chatId = JsonPrimitive("")).configured)
        assertFalse(TgConfig(token = "t", chatId = JsonPrimitive(false)).configured)
        assertTrue(TgConfig(token = "t", chatId = JsonPrimitive("555")).configured, "a string chat id is truthy")
    }

    @Test
    fun redactMasksTheTokenEverywhereItAppears() {
        val cfg = TgConfig(token = "SECRETTOKEN")
        assertEquals(
            "connect failed https://api.telegram.org/bot***/sendMessage",
            cfg.redact("connect failed https://api.telegram.org/botSECRETTOKEN/sendMessage"),
        )
        assertEquals("no token, nothing to mask", TgConfig().redact("no token, nothing to mask"))
    }

    @Test
    fun theApiBaseMirrorsPythonsDefaultAndEnvOverride() {
        assertEquals("https://api.telegram.org", TgConfig.DEFAULT_API, "config.py: API = os.environ.get('ZT_API_BASE', ...)")
    }

    @Test
    fun theFirehoseLaneParsesAndTheViewSwapsOnlyTheToken() {
        val f = File(dir, "lane.json")
        f.writeText("""{"token": "MAIN", "chat_id": 555, "firehose_token": "FIRE"}""")
        val cfg = TgConfig.load(f)
        assertTrue(cfg.firehoseSplit)
        assertEquals("FIRE", cfg.firehose.token)
        assertEquals(JsonPrimitive(555), cfg.firehose.chatId, "same chat — the lane is a throat, not a room")
        assertEquals("MAIN", cfg.token, "the commands lane is untouched")
        assertTrue(cfg.firehose.configured)
    }

    @Test
    fun noFirehoseTokenMeansNoLane() {
        val plain = TgConfig(token = "MAIN", chatId = JsonPrimitive(555))
        assertFalse(plain.firehoseSplit)
        assertSame(plain, plain.firehose, "the pre-lane behavior, byte for byte")
        val empty = TgConfig(token = "MAIN", chatId = JsonPrimitive(555), firehoseToken = "")
        assertFalse(empty.firehoseSplit)
        assertSame(empty, empty.firehose)
    }
}
