package dev.lattenero.bot.channel

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client's connection lifetime against an in-memory transport: the
 * handshake gates everything, the degraded state (no socket) is the
 * documented exception family, queries correlate by id, inbound is
 * fire-and-forget, and subscriptions REPLAY on the next session (the
 * continuous stream's no-replay contract).
 */
class ChannelClientTest {

    /** In-memory socket: every written line answered by the script; reads suspend until a line exists. */
    private class FakeTransport(var script: (String) -> List<String>) : ChannelTransport {
        val written = mutableListOf<String>()
        private val incoming = ArrayDeque<String>()
        var connectAnswer = true
        var open = false

        override suspend fun connect(): Boolean {
            open = connectAnswer
            return connectAnswer
        }

        override suspend fun writeLine(line: String) {
            written.add(line)
            script(line).forEach(incoming::addLast)
        }

        override suspend fun readLine(): String? {
            while (incoming.isEmpty() && open) delay(10) // wait for the peer (test-only polling)
            return incoming.removeFirstOrNull()
        }

        override fun shutdown() {
            open = false
        }
    }

    private val token = "b".repeat(64)

    private fun helloOk(line: String): List<String> = when {
        line.contains("\"hello\"") -> listOf(
            """{"type":"hello_ok","protocolVersion":1,"server":{"pid":1,"startedAt":2},"bot":{"id":"daemon-local","enabled":true},"capabilities":["inbound"]}""",
        )

        else -> emptyList()
    }

    @Test
    fun noSocketIsTheDegradedStateNeverACrash() = runTest {
        val transport = FakeTransport { emptyList() }.apply { connectAnswer = false }
        val client = ChannelClient({ transport }, { token }, {})
        val e = assertFailsWith<ChannelDown> { client.session() }
        assertTrue("not deployed" in e.message!!)
        assertEquals(0, transport.written.size) // nothing was even written
    }

    @Test
    fun missingTokenIsDegradedToo() = runTest {
        val transport = FakeTransport { helloOk(it) }
        val client = ChannelClient({ transport }, { null }, {})
        val e = assertFailsWith<ChannelDown> { client.session() }
        assertTrue("token" in e.message!!)
    }

    @Test
    fun authFailedIsDegradedNotABug() = runTest {
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> listOf(
                    """{"type":"error","code":"auth_failed","fatal":true}""",
                )

                else -> emptyList()
            }
        }
        val client = ChannelClient({ transport }, { token }, {})
        val e = assertFailsWith<ChannelDown> { client.session() }
        assertTrue("auth_failed" in e.message!!)
    }

    @Test
    fun handshakeWritesTheTokenOnTheWireOnly() = runTest {
        val transport = FakeTransport { helloOk(it) }
        val client = ChannelClient({ transport }, { token }, {})
        launch { client.session() }
        // the handshake line exists, with the token (the wire is the wire);
        // what can never happen is the token in a LOG — describe() redacts
        delay(50)
        val hello = transport.written.single { it.contains("\"hello\"") }
        assertTrue(token in hello)
        assertTrue(token !in describe(ClientFrame.Hello(token)))
        transport.shutdown()
    }

    @Test
    fun queriesCorrelateByIdInsideALiveSession() = runTest {
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line)
                line.contains("\"kind\":\"quota\"") -> listOf("""{"type":"query_result","id":"${idOf(line)}","result":{"pct":41}}""")
                line.contains("\"type\":\"ping\"") -> listOf("""{"type":"pong","id":"${idOf(line)}","at":7}""")
                else -> emptyList()
            }
        }
        val client = ChannelClient({ transport }, { token }, {})
        var answer: QueryAnswer? = null
        val job = launch {
            // the session must be UP before the query has a client to ride on
            while (!client.connected) delay(10)
            answer = client.query(ChannelQuery.Quota())
            transport.shutdown()
        }
        client.session()
        job.join()
        val ok = answer as QueryAnswer.Ok
        assertEquals("""{"pct":41}""", ok.resultJson)
    }

    @Test
    fun inboundFiresAndItsLateResultArrivesAsTheCallback() = runTest {
        val results = mutableListOf<ServerFrame.InboundResult>()
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line)
                line.contains("\"type\":\"inbound\"") -> listOf(
                    """{"type":"inbound_result","id":"${idOf(line)}","replies":[{"text":"draft created"}]}""",
                )

                else -> emptyList()
            }
        }
        val client = ChannelClient({ transport }, { token }, {}, onInboundResult = { results.add(it) })
        var receipt: InboundReceipt? = null
        val job = launch {
            while (!client.connected) delay(10)
            receipt = client.sendInbound("arreglá el login", "tg-99", 123L)
            delay(100)
            transport.shutdown() // the late result was served; end the session
        }
        client.session()
        job.join()
        assertEquals(InboundReceipt.FIRED, receipt)
        assertTrue(transport.written.any { it.contains("arreglá el login") && it.contains("tg-99") })
        assertEquals(listOf("draft created"), results.single().replies)
    }

    @Test
    fun awaitedInboundOwnsItsResultTheCallbackStaysSilent() = runTest {
        // the bind's contract: sendInboundAwait reads the replies itself —
        // the unawaited callback must NOT double-speak them
        val results = mutableListOf<ServerFrame.InboundResult>()
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line)
                line.contains("\"type\":\"inbound\"") -> listOf(
                    """{"type":"inbound_result","id":"${idOf(line)}","duplicate":false,""" +
                        """"replies":[{"text":"The current task is still running. Try again later."}]}""",
                )

                else -> emptyList()
            }
        }
        val client = ChannelClient({ transport }, { token }, {}, onInboundResult = { results.add(it) })
        var answer: InboundAnswer? = null
        val job = launch {
            while (!client.connected) delay(10)
            answer = client.sendInboundAwait("/task task-9", "tg-7-bind", 5L)
            transport.shutdown()
        }
        client.session()
        job.join()
        val answered = answer as InboundAnswer.Answered
        assertTrue(answered.replies.single().contains("still running"))
        assertEquals(0, results.size) // awaited: the waiter owns the result
    }

    @Test
    fun subscriptionsReplayOnEverySession() = runTest {
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line)
                line.contains("\"type\":\"subscribe\"") -> listOf(
                    """{"type":"subscribed","id":"${idOf(line)}","taskId":"${taskIdOf(line)}"}""",
                )

                else -> emptyList()
            }
        }
        val resubscribed = mutableListOf<String>()
        val client = ChannelClient({ transport }, { token }, {}, onResubscribed = { resubscribed.add(it) })
        client.subscribe("task-9", "/ws") // no session yet: remembered, not sent
        assertEquals(emptyList(), transport.written.filter { it.contains("subscribe") })

        // watchdog: the serve loop parks on readLine; the test cuts it
        val watchdog = launch { delay(200); transport.shutdown() }
        client.session() // first session: the remembered subscription replays
        watchdog.cancel()
        assertTrue(transport.written.any { it.contains("\"subscribe\"") && it.contains("task-9") })
        assertEquals(listOf("task-9"), resubscribed)
    }

    @Test
    fun resetForgetsSubscriptionsAndKillsTheSocket() = runTest {
        val transport = FakeTransport { line -> if (line.contains("\"hello\"")) helloOk(line) else emptyList() }
        val client = ChannelClient({ transport }, { token }, {})
        client.subscribe("task-9", "/ws")
        client.reset()
        assertEquals(false, transport.open)
        // the next session starts with a clean slate: no replay
        val job = launch { client.session() }
        delay(50)
        assertTrue(transport.written.none { it.contains("subscribe") })
        transport.shutdown()
        job.join()
    }

    @Test
    fun unparseableAndUnknownLinesAreIgnoredNotFatal() = runTest {
        val transport = FakeTransport { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line) + listOf("garbage not json", """{"type":"brand_new_frame"}""")
                else -> emptyList()
            }
        }
        val events = mutableListOf<ServerFrame.StreamEvent>()
        val client = ChannelClient({ transport }, { token }, onStreamEvent = { events.add(it) })
        // feed one stream event then close: the garbage before it must not end the session
        transport.script = { line ->
            when {
                line.contains("\"hello\"") -> helloOk(line) + listOf(
                    "not json",
                    """{"type":"stream_event","taskId":"t","seq":1,"at":1,"event":{"type":"task_run_started"}}""",
                )

                else -> emptyList()
            }
        }
        val job = launch { client.session() }
        while (events.isEmpty()) delay(10)
        transport.shutdown()
        job.join()
        assertEquals(1, events.size)
    }

    private fun idOf(line: String): String =
        Regex(""""id":"([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""

    private fun taskIdOf(line: String): String =
        Regex(""""taskId":"([^"]+)"""").find(line)?.groupValues?.get(1) ?: ""

    @Suppress("unused")
    private val jsonForCoverage: Json = Json
}
