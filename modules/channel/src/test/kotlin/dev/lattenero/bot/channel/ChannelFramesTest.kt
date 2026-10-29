package dev.lattenero.bot.channel

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract pinned against DAEMON-CHANNEL.md's OWN examples and
 * the shapes of `packages/shared/src/daemon-channel.ts` — a drift of the
 * fork's protocol fails THIS test before it can fail a turn. Key order
 * is byte-territory: kotlinx preserves insertion order, so the fixtures
 * below are compared as parsed JSON (order-insensitive) AND the exact
 * spelling of critical fields as strings.
 */
class ChannelFramesTest {

    private val token = "a".repeat(64) // 64 hex, the spec's auth material

    @Test
    fun helloEncodesTheSpecsFirstLine() {
        val line = encodeClient(ClientFrame.Hello(token))
        val obj = Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
        assertEquals("hello", obj["type"].toString().trim('"'))
        assertEquals(1, obj["protocolVersion"].toString().toInt())
        assertEquals(token, obj["token"].toString().trim('"'))
        assertEquals("bot-kotlin", obj["clientId"].toString().trim('"'))
        // the spec's example shape, field by field
        assertEquals(setOf("type", "protocolVersion", "token", "clientId"), obj.keys)
    }

    @Test
    fun inboundEncodesMessageIdAndReceivedAt() {
        val line = encodeClient(
            ClientFrame.Inbound(id = "r1", text = "arreglá el login", messageId = "tg-1001", receivedAt = 1761700000123),
        )
        val obj = Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject
        assertEquals("inbound", obj["type"].toString().trim('"'))
        assertEquals("r1", obj["id"].toString().trim('"'))
        assertEquals("arreglá el login", obj["text"].toString().trim('"'))
        assertEquals("tg-1001", obj["messageId"].toString().trim('"'))
        assertEquals(1761700000123, obj["receivedAt"].toString().toLong())
    }

    @Test
    fun queryEncodesTheSpecsQuotaExample() {
        val line = encodeClient(ClientFrame.Query("q1", ChannelQuery.Quota()))
        assertEquals("""{"type":"query","id":"q1","query":{"kind":"quota"}}""", line)
    }

    @Test
    fun taskListCarriesScopesAndLimit() {
        val query = ChannelQuery.TaskList(
            listKind = "timeline",
            workspaceScopes = listOf(WorkspaceScope("/Users/damian/Projects/app"), WorkspaceScope("/w2", "identity")),
            limit = 20,
        )
        val obj = Json.parseToJsonElement(encodeClient(ClientFrame.Query("q1", query))) as kotlinx.serialization.json.JsonObject
        val inner = obj["query"] as kotlinx.serialization.json.JsonObject
        assertEquals("timeline", inner["listKind"].toString().trim('"'))
        val scopes = inner["workspaceScopes"].toString()
        assertTrue("/Users/damian/Projects/app" in scopes && "identity" in scopes && "workspacePath" in scopes)
        assertEquals(20, inner["limit"].toString().toInt())
    }

    @Test
    fun subscribeUnsubscribePingEncode() {
        val sub = Json.parseToJsonElement(encodeClient(ClientFrame.Subscribe("s1", "task-9", "/ws"))) as
            kotlinx.serialization.json.JsonObject
        assertEquals("subscribe", sub["type"].toString().trim('"'))
        assertEquals("task-9", sub["taskId"].toString().trim('"'))

        val unsub = Json.parseToJsonElement(encodeClient(ClientFrame.Unsubscribe("u1", "task-9"))) as
            kotlinx.serialization.json.JsonObject
        assertEquals("unsubscribe", unsub["type"].toString().trim('"'))

        val ping = Json.parseToJsonElement(encodeClient(ClientFrame.Ping("p1"))) as
            kotlinx.serialization.json.JsonObject
        assertEquals("ping", ping["type"].toString().trim('"'))
    }

    @Test
    fun helloOkDecodesTheSpecsExample() {
        val line = """
            {"type":"hello_ok","protocolVersion":1,
             "server":{"pid":1234,"startedAt":1761700000000,"appVersion":"3.16.2"},
             "bot":{"id":"daemon-local","enabled":true},
             "capabilities":["inbound","stream","queries:listTasks"]}
        """.trimIndent()
        val frame = decodeServerLine(line) as ServerFrame.HelloOk
        assertEquals(1, frame.protocolVersion)
        assertEquals(1234L, frame.serverPid)
        assertEquals(1761700000000L, frame.serverStartedAt)
        assertEquals("3.16.2", frame.appVersion)
        assertEquals("daemon-local", frame.botId)
        assertTrue(frame.botEnabled)
        assertEquals(listOf("inbound", "stream", "queries:listTasks"), frame.capabilities)
    }

    @Test
    fun inboundResultDecodesDuplicateAndReplies() {
        val frame = decodeServerLine(
            """{"type":"inbound_result","id":"r1","duplicate":true,"replies":[]}""",
        ) as ServerFrame.InboundResult
        assertTrue(frame.duplicate)
        assertEquals(emptyList(), frame.replies)

        val answered = decodeServerLine(
            """{"type":"inbound_result","id":"r2","replies":[{"text":"draft created"}]}""",
        ) as ServerFrame.InboundResult
        assertEquals(listOf("draft created"), answered.replies)
    }

    @Test
    fun streamEventDecodesTheSpecsThoughtChunk() {
        val line = """
            {"type":"stream_event","taskId":"task-9","seq":1,"at":1761700004567,
             "event":{"type":"agent_thought_chunk","taskId":"task-9","traceId":"t",
                      "parentToolUseId":null,"content":"penso que..."}}
        """.trimIndent()
        val frame = decodeServerLine(line) as ServerFrame.StreamEvent
        assertEquals("task-9", frame.taskId)
        assertEquals(1L, frame.seq)
        assertEquals(1761700004567L, frame.at)
        assertEquals("agent_thought_chunk", frame.event["type"].toString().trim('"'))
        assertEquals("penso que...", frame.event["content"].toString().trim('"'))
    }

    @Test
    fun errorDecodesWithItsFatalFlag() {
        val frame = decodeServerLine(
            """{"type":"error","id":"q1","code":"unknown_query","message":"kind","fatal":false}""",
        ) as ServerFrame.ChannelError
        assertEquals("q1", frame.id)
        assertEquals("unknown_query", frame.code)
        assertEquals(false, frame.fatal)
    }

    @Test
    fun pongAndByeDecode() {
        val pong = decodeServerLine("""{"type":"pong","id":"p1","at":1761700000000}""") as ServerFrame.Pong
        assertEquals("p1", pong.id)
        assertEquals(1761700000000L, pong.at)

        val bye = decodeServerLine("""{"type":"bye","reason":"host-shutdown"}""") as ServerFrame.Bye
        assertEquals("host-shutdown", bye.reason)
    }

    @Test
    fun unknownTypeAndGarbageAreIgnoredNotFatal() {
        assertNull(decodeServerLine("""{"type":"future_frame","x":1}"""))
        assertNull(decodeServerLine("not json at all"))
        assertNull(decodeServerLine(""))
    }

    @Test
    fun describeNeverLeaksTheToken() {
        val described = describe(ClientFrame.Hello(token))
        assertTrue(token !in described)
        assertTrue("***" in described)
        // inbound previews the LENGTH, not the prompt
        val inbound = describe(ClientFrame.Inbound("r1", "secreto del dueño", "tg-1", 0L))
        assertTrue("secreto" !in inbound)
        assertTrue("17 chars" in inbound)
    }

    @Test
    fun queryKindSpellsTheWireNames() {
        assertEquals("listTasks", queryKind(ChannelQuery.ListTasks("/w")))
        assertEquals("taskList", queryKind(ChannelQuery.TaskList("timeline", emptyList())))
        assertEquals("activeTaskIds", queryKind(ChannelQuery.ActiveTaskIds("/w")))
        assertEquals("taskMeta", queryKind(ChannelQuery.TaskMeta("t")))
        assertEquals("taskSnapshot", queryKind(ChannelQuery.TaskSnapshot("t")))
        assertEquals("taskUsage", queryKind(ChannelQuery.TaskUsage("t")))
        assertEquals("quota", queryKind(ChannelQuery.Quota()))
        assertEquals("botStatus", queryKind(ChannelQuery.BotStatus))
        assertEquals("stop", queryKind(ChannelQuery.Stop("t")))
        assertEquals("stopAll", queryKind(ChannelQuery.StopAll()))
        assertEquals("compact", queryKind(ChannelQuery.Compact("t")))
    }

    @Test
    fun specConstantsMatchTheContract() {
        assertEquals(1, ChannelSpec.PROTOCOL_VERSION)
        assertEquals("daemon-local.sock", ChannelSpec.SOCKET_FILENAME)
        assertEquals("daemon-local.token", ChannelSpec.TOKEN_FILENAME)
        assertEquals(24 * 1024 * 1024, ChannelSpec.MAX_LINE_BYTES)
        assertEquals(10_000L, ChannelSpec.HANDSHAKE_TIMEOUT_MS)
        assertEquals(2 * 60_000L, ChannelSpec.INBOUND_DEDUPE_TTL_MS)
    }
}
