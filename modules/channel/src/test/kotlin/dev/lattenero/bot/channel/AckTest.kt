package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * F23 (updates.py react, ported): the 👀/✅ cycle answers the owner's
 * ORIGINAL message — one reaction, zero new messages, best-effort by
 * design.
 */
class AckTest {

    /** transport's test pattern: a MockEngine dressed as the factory TgApi takes. */
    private fun mockFactory(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
        }

    private val cfg = TgConfig(token = "T0K", chatId = JsonPrimitive(555))

    @Test
    fun theReactionAnswersTheOriginalMessage() {
        val wire = ReactRequest.compose(cfg, 4242, Ack.RECEIVED)
        assertEquals(JsonPrimitive(555), wire["chat_id"]!!.jsonPrimitive)
        assertEquals(4242L, wire["message_id"]!!.jsonPrimitive.longOrNull)
        val reaction = wire["reaction"]!!.jsonArray.single().jsonObject
        assertEquals("emoji", reaction["type"]!!.jsonPrimitive.content)
        assertEquals("👀", reaction["emoji"]!!.jsonPrimitive.content)
    }

    @Test
    fun noMessageIdMeansNoReactionPythonsGuard() = runTest {
        // mid<=0: ZERO wire traffic — Python's `if not mid: return`
        val api = TgApi({ cfg }, engineFactory = mockFactory { _ -> fail("the guard must skip the wire entirely") })
        Ack.react(api, cfg, messageId = 0, emoji = Ack.RECEIVED)
    }

    @Test
    fun aRejectedReactionIsSilentNeverFatal() = runTest {
        // the server says no: deliberate degradation, nothing raises
        val api = TgApi(
            { cfg },
            engineFactory = mockFactory { _ -> respond("""{"ok": false}""", io.ktor.http.HttpStatusCode.BadRequest) },
        )
        Ack.react(api, cfg, messageId = 7, emoji = Ack.HANDLED)
    }
}
