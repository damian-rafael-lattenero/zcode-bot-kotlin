package dev.lattenero.bot.transport

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ONE composition path (B2's knob): what dry-run logs is what live
 * posts. These pins are the wire contract — chat_id passes through with
 * the file's type, loud resolves exactly like send()'s `loud is None`
 * branch, markup becomes Python's json.dumps STRING inside the params.
 */
class SendRequestTest {

    private val cfg = TgConfig(token = "T0K", chatId = JsonPrimitive(555))

    @Test
    fun `loud null resolves through loud_all`() {
        val loudCfg = cfg.copy(loudAll = false)
        val dn = SendRequest.compose(loudCfg, "x", loud = null)
            .jsonObject["disable_notification"]!!.jsonPrimitive.content
        assertEquals("true", dn, "loud_all=false → silent")
    }

    @Test
    fun `loud true and false force the notification bit`() {
        assertEquals(
            "false",
            SendRequest.compose(cfg, "x", loud = true).jsonObject["disable_notification"]!!.jsonPrimitive.content,
            "zconn's ⚠️/✅ notify",
        )
        assertEquals(
            "true",
            SendRequest.compose(cfg, "x", loud = false).jsonObject["disable_notification"]!!.jsonPrimitive.content,
            "the spectator's boxes are silent",
        )
    }

    @Test
    fun `chat_id keeps the file's type - Python passes cfg chat_id through`() {
        val intId = SendRequest.compose(cfg, "x").jsonObject["chat_id"]!!.jsonPrimitive.content
        assertEquals("555", intId)

        val strCfg = TgConfig(token = "T", chatId = JsonPrimitive("555"))
        val strId = SendRequest.compose(strCfg, "x").jsonObject["chat_id"] as JsonPrimitive
        assertTrue(strId.isString, "a string chat_id stays a string on the wire")
    }

    @Test
    fun `the body is capped at CH_REPLY code points`() {
        val long = "a".repeat(CH_REPLY + 500)
        val text = SendRequest.compose(cfg, long).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.length <= CH_REPLY, "html_truncate capped it: ${text.length}")
    }

    @Test
    fun `markup becomes a json dumps STRING with python separators`() {
        val markup = JsonObject(
            mapOf(
                "inline_keyboard" to kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf("text" to JsonPrimitive("CoT 1"), "callback_data" to JsonPrimitive("c1")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val params = SendRequest.compose(cfg, "x", loud = false, markup = markup)
        val rm = params.jsonObject["reply_markup"] as JsonPrimitive
        assertTrue(rm.isString, "reply_markup travels as a STRING (json.dumps(markup))")
        assertEquals(
            """{"inline_keyboard": [[{"text": "CoT 1", "callback_data": "c1"}]]}""",
            rm.content,
            "Python's ', '/': ' separators",
        )
    }

    @Test
    fun `no markup - no reply_markup key`() {
        assertFalse("reply_markup" in SendRequest.compose(cfg, "x").jsonObject)
    }

    @Test
    fun `parse_mode is HTML like every python send`() {
        assertEquals(
            "HTML",
            SendRequest.compose(cfg, "x").jsonObject["parse_mode"]!!.jsonPrimitive.content,
        )
    }
}
