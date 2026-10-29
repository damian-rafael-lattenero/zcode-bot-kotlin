package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * register_bot_menu's parity: the «/» menu the daemon posts at boot —
 * the EXACT eight, in order, Python's scope (the owner's chat only).
 */
class BotMenuTest {

    /** transport's test pattern: a MockEngine dressed as the factory TgApi takes. */
    private fun mockFactory(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
        }

    @Test
    fun bodyCarriesTheEightInOrder() {
        val body = BotMenu.body(TgConfig(token = "t", chatId = JsonNull))
        val commands = body["commands"]!!.jsonArray
        assertEquals(8, commands.size)
        assertEquals(
            listOf("tasks", "all_tasks", "status", "stop", "nuclear", "quota", "subagents", "help"),
            commands.map { it.jsonObject["command"]!!.jsonPrimitive.content },
        )
        commands.forEach { c ->
            assertTrue(c.jsonObject["description"]!!.jsonPrimitive.content.isNotEmpty())
        }
    }

    @Test
    fun bodyScopesToTheOwnersChatLikePython() {
        val withChat = BotMenu.body(TgConfig(token = "t", chatId = JsonPrimitive(424242)))
        val scope = withChat["scope"]!!.jsonObject
        assertEquals("chat", scope["type"]!!.jsonPrimitive.content)
        assertEquals("424242", scope["chat_id"]!!.jsonPrimitive.content)
        assertTrue(!BotMenu.body(TgConfig(token = "t", chatId = JsonNull)).containsKey("scope"))
    }

    @Test
    fun registerPostsSetMyCommandsOnce() = runBlocking {
        val methods = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val factory = mockFactory { request ->
            methods.add(request.url.encodedPath.substringAfterLast('/'))
            bodies.add((request.body as io.ktor.http.content.TextContent).text)
            respond("""{"ok":true}""", HttpStatusCode.OK)
        }
        val cfg = TgConfig(token = "123:abc", chatId = JsonPrimitive(1))
        TgApi({ cfg }, engineFactory = factory).use { api ->
            assertTrue(BotMenu.register(api, cfg))
        }
        assertEquals(listOf("setMyCommands"), methods)
        val posted = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(8, posted["commands"]!!.jsonArray.size) // the exact eight on the wire
    }

    @Test
    fun registerSurvivesATelegramHiccup() = runBlocking {
        val factory = mockFactory { respond("""{"ok":false}""", HttpStatusCode.InternalServerError) }
        val cfg = TgConfig(token = "123:abc", chatId = JsonPrimitive(1))
        TgApi({ cfg }, engineFactory = factory).use { api ->
            assertEquals(false, BotMenu.register(api, cfg)) // logged, never fatal
        }
    }
}
