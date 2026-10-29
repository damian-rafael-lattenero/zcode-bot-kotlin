package dev.lattenero.bot.transport

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler

/**
 * A MockEngine dressed as the factory TgApi takes: the client's own
 * config block (the timeout install) is honored and the handler decides
 * every response. ZERO network — what these tests pin is the wire
 * contract (URL, body, headers) and the error algebra, never a live
 * Telegram call.
 */
internal fun mockEngineFactory(handler: MockRequestHandler): HttpClientEngineFactory<MockEngineConfig> =
    object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
            MockEngine(MockEngineConfig().apply(block).apply { addHandler(handler) })
    }
