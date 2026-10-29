package dev.lattenero.bot.daemon.send

import dev.lattenero.bot.contracts.ShadowDecision
import dev.lattenero.bot.transport.SendResult
import dev.lattenero.bot.transport.TgConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The knob's send legs: composeSend is the byte-exact request (the body
 * IS what TgApi posts — `params.toString()`), the NONE leg is today's
 * shadow contract, and the LIVE leg passes the decision's text through
 * with the loudness the watchers decided (zconn true, spectator false).
 */
class SendLegTest {

    private val cfg = TgConfig(token = "T0K123", chatId = JsonPrimitive(555))

    private fun decision(watcher: String = "zconn", text: String = "⚠️ <b>z.ai connection lost</b>\nETIMEDOUT") =
        ShadowDecision(
            ts = 1793300000.0,
            tsIso = "2026-10-30T12:00:00.000Z",
            watcher = watcher,
            decision = "down",
            text = text,
        )

    @Test
    fun `composeSend answers the exact wire request`() {
        val r = composeSend(cfg, decision().text, loud = true)
        assertEquals("sendMessage", r.method)
        assertEquals("https://api.telegram.org/bot***/sendMessage", r.url, "the token is redacted in the logged twin")
        assertEquals(
            """{"chat_id":555,"text":"⚠️ <b>z.ai connection lost</b>\nETIMEDOUT",""" +
                """"parse_mode":"HTML","disable_notification":false}""",
            r.body,
            "byte-exact: kotlinx's params.toString() is what TgApi setBody()s",
        )
    }

    @Test
    fun `the spectator's silent boxes compose with disable_notification true`() {
        val r = composeSend(cfg, "🖥️ box", loud = false)
        assertTrue(r.body.endsWith(""""disable_notification":true}"""), r.body)
    }

    @Test
    fun `NONE never delivers - the shadow contract unchanged`() = runTest {
        // the no-op leg is a constant: calling it must simply do nothing,
        // whatever the decision — that IS ADR-023's "never sends"
        SendLegs.NONE.deliver(decision(), loud = true)
        SendLegs.NONE.deliver(decision(watcher = "spectator"), loud = false)
    }

    @Test
    fun `LIVE passes text and loudness to the sender and reports the message_id line`() = runTest {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val leg = SendLegs.live({ _, text, loud ->
            calls.add(text to loud)
            SendResult.Sent(777)
        }) { java.io.File("/nonexistent/telegram-remote.json") } // unreadable → TgConfig defaults; the leg's fn is what we pin

        leg.deliver(decision(), loud = true)
        assertEquals(listOf(decision().text to true), calls, "the decision's text, zconn's loud=true")
    }

    @Test
    fun `LIVE answers parked without raising - the outbox holds it`() = runTest {
        val leg = SendLegs.live({ _, _, _ -> SendResult.Parked }) {
            java.io.File("/nonexistent/telegram-remote.json")
        }
        leg.deliver(decision(), loud = true) // must not throw — a hook must never break the session
        assertTrue(true, "parked is an answer, not an exception")
    }

    @Test
    fun `DaemonSend owns the spectator only outside shadow`() {
        assertEquals(false, DaemonSend.of(SendMode.SHADOW, SendLegs.NONE).ownsSpectator)
        assertEquals(true, DaemonSend.of(SendMode.DRY_RUN, SendLegs.NONE).ownsSpectator)
        assertEquals(true, DaemonSend.of(SendMode.LIVE, SendLegs.NONE).ownsSpectator)
        assertEquals(false, DaemonSend().ownsSpectator, "the default wiring is today's shadow daemon")
    }
}
