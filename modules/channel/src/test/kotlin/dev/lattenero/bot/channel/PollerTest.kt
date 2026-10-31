package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import dev.lattenero.bot.transport.TgOffset
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The poller against a MockEngine (the transport-suite pattern, ZERO
 * network): the batch pipeline, the owner's fail-closed gate, the offset
 * confirmed POST-dispatch (the 14/10 lesson), and the 409 = "somebody
 * else still polls" honesty.
 */
class PollerTest {

    private val cfg = TgConfig(token = "tok", chatId = JsonPrimitive(42), ownerUserId = 7L)

    private fun tempDir(): File = Files.createTempDirectory("poller").toFile()

    private fun poller(
        dir: File,
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        dispatch: suspend (TgConfig, InboundUpdate) -> Unit = { _, _ -> },
    ): Poller {
        val api = TgApi({ cfg }, engineFactory = factory(body, status))
        return Poller(api = api, offsetFile = File(dir, "tg-offset"), dispatch = dispatch)
    }

    private fun factory(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(
                    MockEngineConfig().apply(block).apply {
                        addHandler { respond(body, status) }
                    },
                )
        }

    private fun update(id: Long, text: String, from: Long = 7L): String = buildJsonObject {
        put("update_id", id)
        put(
            "message",
            buildJsonObject {
                put("text", text)
                put("from", buildJsonObject { put("id", from) })
            },
        )
    }.toString()

    @Test
    fun dispatchesTheBatchAndConfirmsTheOffsetAfter() = runTest {
        val dir = tempDir()
        TgOffset.save(File(dir, "tg-offset"), 10) // the PREVIOUS batch's confirmation
        val seen = mutableListOf<String>()
        var offsetDuringDispatch = 0L
        val poller = poller(dir, """{"result":[${update(10, "/status")}, ${update(11, "hola")}]}""") { _, m ->
            if (m is InboundUpdate.Message) seen.add(m.text)
            offsetDuringDispatch = TgOffset.current(File(dir, "tg-offset"))
        }
        val outcome = poller.cycle(cfg)
        assertEquals(listOf("/status", "hola"), seen)
        assertEquals(10L, offsetDuringDispatch) // during dispatch: still the OLD confirmation
        assertEquals(12L, outcome.offset)
        assertEquals(12L, TgOffset.current(File(dir, "tg-offset"))) // confirmed POST-dispatch
    }

    @Test
    fun aPoisonedMessageIsSkippedNotCrashLooped() = runTest {
        val dir = tempDir()
        TgOffset.save(File(dir, "tg-offset"), 10) // the previous confirmation
        val poller = poller(dir, """{"result":[${update(10, "/nuclear")}]}""") { _, _ ->
            throw IllegalStateException("poisoned message")
        }
        val outcome = poller.cycle(cfg) // the poison must not crash the cycle (F35b)
        assertEquals(1, outcome.messages)
        assertEquals(11L, TgOffset.current(File(dir, "tg-offset"))) // advanced past it: no crash-loop
    }

    @Test
    fun theOwnerGateIsFailClosed() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<Long>()
        val poller = poller(
            dir,
            """{"result":[${update(10, "hi", from = 7)}, ${update(11, "hi", from = 8)}]}""",
        ) { _, m -> seen.add(m.fromId!!) }
        poller.cycle(cfg)
        assertEquals(listOf(7L), seen) // the stranger never reaches the dispatcher
    }

    @Test
    fun noOwnerInTheConfigMeansNobodyGetsThrough() = runTest {
        val dir = tempDir()
        val noOwner = cfg.copy(ownerUserId = null)
        val seen = mutableListOf<String>()
        val api = TgApi({ noOwner }, engineFactory = factory("""{"result":[${update(10, "hi")}]}"""))
        val poller = Poller(api = api, offsetFile = File(dir, "tg-offset"), dispatch = { _, m -> if (m is InboundUpdate.Message) seen.add(m.text) })
        poller.cycle(noOwner)
        assertEquals(emptyList(), seen)
    }

    @Test
    fun a409IsPolicyNotAnError() = runTest {
        val dir = tempDir()
        val poller = poller(
            dir,
            """{"ok":false,"error_code":409,"description":"Conflict: terminated by other getUpdates request"}""",
            status = HttpStatusCode.Conflict,
        ) { _, _ -> error("never") }
        val outcome = poller.cycle(cfg)
        assertEquals(true, outcome.conflict)
        assertEquals(0, outcome.messages)
    }

    @Test
    fun unconfiguredSkipsThePollEntirely() = runTest {
        val dir = tempDir()
        var calls = 0
        val counting: HttpClientEngineFactory<MockEngineConfig> = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler { calls++; respond("{}") } })
        }
        val api = TgApi({ cfg.copy(token = "") }, engineFactory = counting)
        val poller = Poller(api = api, offsetFile = File(dir, "tg-offset"), dispatch = { _, _ -> error("never") })
        val outcome = poller.cycle(cfg.copy(token = ""))
        assertEquals(0, calls)
        assertEquals(0, outcome.messages)
    }

    @Test
    fun nonTextUpdatesAdvanceTheCursorButDoNotDispatch() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<String>()
        val poller = poller(
            dir,
            """{"result":[
                {"update_id":10,"callback_query":{"id":"cb1","data":"nuc:go","from":{"id":8}}},
                {"update_id":11,"edited_message":{"text":"edited","from":{"id":7}}},
                {"update_id":12,"message":{"photo":"x","from":{"id":7}}}
            ]}""",
        ) { _, m ->
            when (m) {
                is InboundUpdate.Message -> seen.add(m.text)
                is InboundUpdate.Callback -> seen.add("cb:${m.data}")
                is InboundUpdate.Attachment -> seen.add("att:${m.fileId}") // a real attachment dispatches (this test's junk photo never does)
            }
        }
        val outcome = poller.cycle(cfg)
        assertEquals(listOf("edited"), seen) // a STRANGER's tap and photos never dispatch; edits count
        assertEquals(13L, outcome.offset) // but the cursor advanced over ALL of them
    }

    @Test
    fun theOwnersButtonTapDispatchesAsACallback() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<InboundUpdate>()
        val poller = poller(
            dir,
            """{"result":[
                {"update_id":10,"callback_query":{"id":"cb9","data":"t:ses_abc","from":{"id":7}}}
            ]}""",
        ) { _, m -> seen.add(m) }
        val outcome = poller.cycle(cfg)
        assertEquals(1, seen.size)
        val cb = seen.single() as InboundUpdate.Callback
        assertEquals("cb9", cb.callbackId)
        assertEquals("t:ses_abc", cb.data)
        assertEquals(11L, outcome.offset)
    }
}
