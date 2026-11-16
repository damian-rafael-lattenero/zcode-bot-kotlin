package dev.lattenero.bot.daemon.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The verb vocabulary's laws: a text verb IS the command token (bare, no
 * @BotName), free words are `text`, and a tap's verb is its callback prefix
 * (never the whole data — the label cardinality stays bounded).
 */
class HandlerVerbTest {

    @Test
    fun textVerbsAreTheCommandTokens() {
        assertEquals("/tasks", HandlerVerb.ofText("/tasks"))
        assertEquals("/all_tasks", HandlerVerb.ofText("  /all_tasks   "))
        assertEquals("/tasks", HandlerVerb.ofText("/tasks@MyBot"))
        assertEquals("/stop", HandlerVerb.ofText("/stop ahora"))
        assertEquals(HandlerVerb.TEXT, HandlerVerb.ofText("hola"))
        assertEquals(HandlerVerb.TEXT, HandlerVerb.ofText("/"))
    }

    @Test
    fun callbackVerbsAreThePrefixes() {
        assertEquals("t:", HandlerVerb.ofCallback("t:abc123"))
        assertEquals("sa:", HandlerVerb.ofCallback("sa:abc"))
        assertEquals("sst:", HandlerVerb.ofCallback("sst:42"))
        assertEquals("cb:plain", HandlerVerb.ofCallback("plain"))
        assertEquals("cb:0123456789ab", HandlerVerb.ofCallback("0123456789abcdef"))
    }
}
