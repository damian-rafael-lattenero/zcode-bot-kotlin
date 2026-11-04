package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import dev.lattenero.bot.transport.TgOffset
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * U9 · F1 — THE CURSOR IS AN AUTHORIZATION BOUNDARY.
 *
 * `tg-offset` was the only thing separating "the owner typed /nuclear go"
 * from "the owner typed /nuclear go YESTERDAY". When the file is absent
 * (fresh state) or poisoned (truncated write), `TgOffset.current` answers
 * 0 — and 0 asks Telegram for every pending update of the last 24 h. A
 * batch replayed into the dispatcher re-runs old commands: a full ☢️
 * ladder, a deploy, duplicate prompts/turns.
 *
 * The law these tests pin: dispatch only rides a cursor that was LIVE
 * (or this process's own in-memory floor); a lost cursor consumes the
 * backlog WITHOUT dispatching and tells the owner.
 */
class PollerCursorSecurityTest {

    private val cfg = TgConfig(token = "tok", chatId = JsonPrimitive(42), ownerUserId = 7L)

    private fun tempDir(): File = Files.createTempDirectory("cursor").toFile()

    /** One owner message (the destructive command of the drill). */
    private fun ownerMessage(id: Long, text: String): String = """
        {"update_id":$id,"message":{"message_id":${id + 100},"from":{"id":7},"text":"$text"}}
    """.trimIndent()

    /** One owner button tap. */
    private fun ownerCallback(id: Long, data: String): String = """
        {"update_id":$id,"callback_query":{"id":"cb$id","data":"$data","from":{"id":7}}}
    """.trimIndent()

    /**
     * A fake Bot API that HONOURS the offset it is asked for — the only
     * shape that can prove the in-memory floor is used (a mock that
     * replays everything would make a re-dispatch look inevitable).
     */
    private fun offsetAwareFactory(updates: List<Pair<Long, String>>): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(
                    MockEngineConfig().apply(block).apply {
                        addHandler { request ->
                            val body = (request.body as TextContent).text
                            val asked = (Json.parseToJsonElement(body) as JsonObject)["offset"]
                                ?.jsonPrimitive?.long ?: 0L
                            val picked = updates.filter { it.first >= asked }.map { it.second }
                            respond("""{"result":[${picked.joinToString(",")}]}""")
                        }
                    },
                )
        }

    private fun poller(
        dir: File,
        updates: List<Pair<Long, String>>,
        dispatch: suspend (TgConfig, InboundUpdate) -> Unit,
        lostNotices: MutableList<Int> = mutableListOf(),
    ): Poller = Poller(
        api = TgApi({ cfg }, engineFactory = offsetAwareFactory(updates)),
        offsetFile = File(dir, "tg-offset"),
        dispatch = dispatch,
        onCursorLost = { skipped -> lostNotices.add(skipped) },
    )

    @Test
    fun anAbsentCursorConsumesTheBacklogWithoutDispatchingTheOldNuclear() = runTest {
        val dir = tempDir() // no tg-offset at all: the fresh-state trap
        val dispatched = mutableListOf<String>()
        val notices = mutableListOf<Int>()
        val poller = poller(
            dir,
            listOf(
                10L to ownerMessage(10, "/nuclear go"),
                11L to ownerCallback(11, "nuc:go"),
            ),
            dispatch = { _, m -> dispatched.add(m::class.simpleName.orEmpty()) },
            lostNotices = notices,
        )
        val outcome = poller.cycle(cfg)
        assertEquals(emptyList(), dispatched, "the backlog never reaches the dispatcher")
        assertTrue(outcome.cursorLost, "the pass reports the lost cursor")
        assertEquals(0, outcome.messages, "nothing was dispatched")
        assertEquals(12L, outcome.offset, "the backlog is CONSUMED: the cursor moves past it")
        assertEquals(12L, TgOffset.current(File(dir, "tg-offset")), "and lands on disk")
        assertEquals(listOf(2), notices, "the owner hears exactly how many updates were dropped")
    }

    @Test
    fun aCorruptCursorIsTheSameTrapAndTheSameAnswer() = runTest {
        val dir = tempDir()
        File(dir, "tg-offset").writeText("12x") // the truncated-write story
        val dispatched = mutableListOf<String>()
        val poller = poller(
            dir,
            listOf(10L to ownerMessage(10, "/deploy go")),
            dispatch = { _, m -> dispatched.add(m::class.simpleName.orEmpty()) },
        )
        val outcome = poller.cycle(cfg)
        assertEquals(emptyList(), dispatched)
        assertTrue(outcome.cursorLost)
        assertEquals(11L, TgOffset.current(File(dir, "tg-offset")))
    }

    @Test
    fun afterTheLostCursorTheNextBatchFlows() = runTest {
        val dir = tempDir()
        val dispatched = mutableListOf<String>()
        val updates = mutableListOf<Pair<Long, String>>(
            10L to ownerMessage(10, "/nuclear go"),
        )
        val poller = poller(dir, updates, dispatch = { _, m -> if (m is InboundUpdate.Message) dispatched.add(m.text) })

        poller.cycle(cfg) // the discard pass
        assertEquals(emptyList(), dispatched)

        updates.add(11L to ownerMessage(11, "hola, seguí"))
        val second = poller.cycle(cfg) // rides the floor: only the NEW update
        assertEquals(listOf("hola, seguí"), dispatched, "the fresh words flow; the old nuclear stays dead")
        assertFalse(second.cursorLost)
        assertEquals(1, second.messages)
    }

    @Test
    fun aLiveCursorStillDispatchesTheOwnersWords() = runTest {
        val dir = tempDir()
        TgOffset.save(File(dir, "tg-offset"), 10) // the healthy state
        val dispatched = mutableListOf<String>()
        val poller = poller(
            dir,
            listOf(10L to ownerMessage(10, "/nuclear go")),
            dispatch = { _, m -> if (m is InboundUpdate.Message) dispatched.add(m.text) },
        )
        val outcome = poller.cycle(cfg)
        assertEquals(listOf("/nuclear go"), dispatched, "a live cursor dispatches as always")
        assertFalse(outcome.cursorLost)
        assertEquals(11L, outcome.offset)
    }

    @Test
    fun aDiskThatRefusesTheSaveDoesNotRedispatchTheBatchToday() = runTest {
        assumeTrue(System.getProperty("os.name").lowercase().contains("mac") || !isRoot(), "root ignores dir modes")
        val dir = tempDir()
        val dispatched = mutableListOf<String>()
        val updates = mutableListOf<Pair<Long, String>>(10L to ownerMessage(10, "primero"))
        val poller = poller(dir, updates, dispatch = { _, m -> if (m is InboundUpdate.Message) dispatched.add(m.text) })
        TgOffset.save(File(dir, "tg-offset"), 10) // live

        poller.cycle(cfg)
        assertEquals(listOf("primero"), dispatched)

        // the disk turns read-only: the save of 11 will fail
        val locked = dir.setWritable(false)
        try {
            updates.add(11L to ownerMessage(11, "segundo"))
            val second = poller.cycle(cfg)
            assertEquals(
                listOf("primero", "segundo"),
                dispatched,
                "the floor asked for offset 11 — 'primero' was NOT re-dispatched",
            )
            assertEquals(1, second.messages)
        } finally {
            if (locked) dir.setWritable(true)
        }
    }

    private fun isRoot(): Boolean = System.getProperty("user.name") == "root"
}
