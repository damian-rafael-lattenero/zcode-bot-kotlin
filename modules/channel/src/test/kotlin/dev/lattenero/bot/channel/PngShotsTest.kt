package dev.lattenero.bot.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 📸 door's two halves: the registry behind the id (bounded, honest
 * miss) and the keyboard combinator (existing rows keep their order, the
 * image door lands last).
 */
class PngShotsTest {

    private fun rows(markup: JsonObject): JsonArray = markup["inline_keyboard"]!! as JsonArray

    private fun data(row: kotlinx.serialization.json.JsonElement): String =
        (row as JsonArray)[0].jsonObject["callback_data"]!!.jsonPrimitive.content

    @Test
    fun `register answers fresh ids and get returns the whole shot`() {
        val shots = PngShots()
        val a = shots.register(PngRender.Shot("ls", "nu"))
        val b = shots.register(PngRender.Shot("val x = 1", "kotlin"))
        assertTrue(a != b, "every shot owns its id")
        assertEquals("ls", shots.get(a)?.text)
        assertEquals("kotlin", shots.get(b)?.language)
    }

    @Test
    fun `an unknown id is the honest miss, never a blank image`() {
        val shots = PngShots()
        assertNull(shots.get(404))
        assertTrue("registro" in PngShots.MISS, "the miss names the registry: ${PngShots.MISS}")
    }

    @Test
    fun `the cap drops the oldest shot first`() {
        val shots = PngShots(cap = 2)
        val first = shots.register(PngRender.Shot("one", null))
        shots.register(PngRender.Shot("two", null))
        shots.register(PngRender.Shot("three", null))
        assertEquals(2, shots.size(), "the registry stays bounded")
        assertNull(shots.get(first), "the oldest shot is gone")
    }

    @Test
    fun `the anchor binds the photo to the button's message`() {
        val shots = PngShots()
        val id = shots.register(PngRender.Shot("ls", "nu"))
        assertNull(shots.anchor(id), "no send yet = no anchor")
        shots.attach(id, 73123)
        assertEquals(73123L, shots.anchor(id), "the photo will reply to that message")
    }

    @Test
    fun `an attach for a pruned id is a silent no-op`() {
        val shots = PngShots(cap = 1)
        val id = shots.register(PngRender.Shot("one", null))
        shots.register(PngRender.Shot("two", null)) // evicts `one`
        shots.attach(id, 99)
        assertNull(shots.anchor(id), "a shot the registry dropped keeps no anchor")
    }

    @Test
    fun `withPng keeps the existing rows and lands the image door last`() {
        val existing = Keyboards.boxOpen(7)
        val merged = Keyboards.withPng(existing, 3)
        val mergedRows = rows(merged)
        assertEquals(2, mergedRows.size, "the door pager row survives and the PNG row is appended")
        assertEquals("bp:7:0", data(mergedRows[0]), "the existing row keeps its place")
        assertEquals("png:3", data(mergedRows[1]))
    }

    @Test
    fun `a null keyboard becomes the one-row PNG keyboard`() {
        val merged = Keyboards.withPng(null, 5)
        val mergedRows = rows(merged)
        assertEquals(1, mergedRows.size)
        assertEquals("png:5", data(mergedRows[0]))
        assertTrue(Keyboards.callbackDataValid(data(mergedRows[0])), "six bytes, far under the 64-byte law")
    }
}
