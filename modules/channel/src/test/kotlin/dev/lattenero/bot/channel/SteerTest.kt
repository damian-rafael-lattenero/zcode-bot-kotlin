package dev.lattenero.bot.channel

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STEER (30/10): the RUNNING turn's redirection from the phone. The
 * frame's wire shape is pinned byte-exact (the fork's `steer` request —
 * additive; an old host refuses by NAME and the command speaks that
 * honestly), and the command's every outcome is pinned against a fake
 * ops: delivered on a running turn, the idle-truth, the old fork, the
 * down channel, the bare command.
 */
class SteerTest {

    /** The fake ops: steer recorded, verdict canned; the queries canned by kind. */
    private class FakeOps : ChannelOps {
        val steered = mutableListOf<Pair<String, String>>()
        var steerAnswer: SteerAnswer = SteerAnswer.Delivered
        var botStatusJson: String? = null
        var activeIds: List<String> = emptyList()

        override suspend fun query(query: ChannelQuery): QueryAnswer = when (query) {
            ChannelQuery.BotStatus -> botStatusJson?.let { QueryAnswer.Ok(it) } ?: QueryAnswer.Unavailable
            is ChannelQuery.ActiveTaskIds -> QueryAnswer.Ok(
                kotlinx.serialization.json.buildJsonArray {
                    activeIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }.toString(),
            )
            else -> QueryAnswer.Failed("internal", "no canned answer")
        }

        override suspend fun sendInbound(text: String, messageId: String, receivedAt: Long) = InboundReceipt.UNAVAILABLE

        override suspend fun sendInboundAwait(text: String, messageId: String, receivedAt: Long) =
            InboundAnswer.Unavailable

        override suspend fun enqueueInbound(text: String, messageId: String, receivedAt: Long) = -1

        override suspend fun steer(taskId: String, text: String): SteerAnswer {
            steered.add(taskId to text)
            return steerAnswer
        }

        override suspend fun subscribe(taskId: String, workspacePath: String) {}
    }

    private val ws = "/Users/damian/Projects/app"

    /** A bound, ALIVE context pointing at task-77. */
    private val bound = """{"bot":{"id":"daemon-local","enabled":true},"context":{"workspacePath":"$ws","activeTaskId":"task-77","mode":"yolo"}}"""

    private fun ops(
        status: String? = bound,
        active: List<String> = listOf("task-77"),
    ) = FakeOps().apply {
        botStatusJson = status
        activeIds = active
    }

    private fun commands(o: ChannelOps): Pair<BotCommands, FakeOps> {
        val fake = o as FakeOps
        return BotCommands(
            ops = fake,
            hub = LiveHub(),
            daemonReset = { "noop" },
        ) to fake
    }

    // ---- the frame ----------------------------------------------------------

    @Test
    fun steerFrameRidesTheExactWireShape() {
        val line = encodeClient(ClientFrame.Steer("t7", "task-77", "usá redis"))
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("steer", json["type"]!!.jsonPrimitive.content)
        assertEquals("t7", json["id"]!!.jsonPrimitive.content)
        assertEquals("task-77", json["taskId"]!!.jsonPrimitive.content)
        assertEquals("usá redis", json["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun steerDescribeKeepsTheTextOutOfLogs() {
        val d = describe(ClientFrame.Steer("t1", "task-77", "una orden larga del dueño"))
        assertTrue("una orden" !in d)
        assertTrue("chars" in d)
    }

    // ---- the command --------------------------------------------------------

    @Test
    fun steerOnARunningTurnDeliversIntoTheLiveTurn() = runBlocking {
        val (bc, fake) = commands(ops(active = listOf("task-77")))
        val reply = bc.handle("/steer cambiá el enfoque a la API", 1L)!!
        assertEquals("task-77" to "cambiá el enfoque a la API", fake.steered.single())
        assertTrue("Steer entregado" in reply.text, reply.text)
        assertTrue("DENTRO del turno" in reply.text)
    }

    @Test
    fun steerOnAnIdleConversationSpeaksTheTruth() = runBlocking {
        val (bc, _) = commands(ops(active = emptyList()))
        val reply = bc.handle("/steer arrancá por la config", 2L)!!
        assertTrue("no tenía turno corriendo" in reply.text, reply.text)
    }

    @Test
    fun steerOnAnOldForkRefusesByTheHostsName() = runBlocking {
        val (bc, _) = commands(ops().apply { steerAnswer = SteerAnswer.Failed("unknown_request", "unknown request type") })
        val reply = bc.handle("/steer probando", 3L)!!
        assertTrue("no conoce el frame «steer»" in reply.text, reply.text)
        assertTrue("falta deployar el fork" in reply.text)
    }

    @Test
    fun steerFailureCarriesTheHostsOwnWord() = runBlocking {
        val (bc, _) = commands(ops().apply { steerAnswer = SteerAnswer.Failed("internal", "order to task-77 failed: no target") })
        val reply = bc.handle("/steer probando", 4L)!!
        assertTrue("internal" in reply.text && "no target" in reply.text, reply.text)
    }

    @Test
    fun steerWithTheChannelDownIsDegradedNotSilent() = runBlocking {
        val (bc, _) = commands(ops(status = null))
        val reply = bc.handle("/steer probando", 5L)!!
        assertTrue("canal" in reply.text.lowercase(), reply.text)
    }

    @Test
    fun bareSteerSpeaksTheUsage() = runBlocking {
        val (bc, fake) = commands(ops())
        val reply = bc.handle("/steer", 6L)!!
        assertTrue("/steer <texto>" in reply.text, reply.text)
        assertEquals(0, fake.steered.size) // nothing fired on a bare command
    }

    @Test
    fun steerWithoutAnActiveConversationIsHonest() = runBlocking {
        val (bc, fake) = commands(ops(status = """{"bot":{"id":"daemon-local","enabled":true},"context":null}"""))
        val reply = bc.handle("/steer probando", 7L)!!
        assertTrue("No active conversation" in reply.text, reply.text)
        assertEquals(0, fake.steered.size)
    }
}
