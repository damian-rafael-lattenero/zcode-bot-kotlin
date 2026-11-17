package dev.lattenero.bot.daemon.palette

import dev.lattenero.bot.channel.MenuCommand
import dev.lattenero.bot.channel.PaletteOrder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The store's laws: counts persist across instances, unknown names never
 * count, the registered order is remembered, a broken file is an empty
 * memory that heals on the next write.
 */
class PaletteStoreTest {

    private val menu = listOf(
        MenuCommand("tasks", "t"),
        MenuCommand("status", "s"),
        MenuCommand("last", "l"),
    )

    private fun tempFile(): File = File(Files.createTempDirectory("palette").toFile(), "usage.json")

    @Test
    fun recordsPersistAcrossInstances() {
        val file = tempFile()
        val first = PaletteStore(file, menu)
        first.record("last")
        first.record("last")
        first.record("tasks")

        val second = PaletteStore(file, menu)
        assertEquals(2L, second.ledger().of("last"))
        assertEquals(1L, second.ledger().of("tasks"))
        assertEquals(listOf("last", "tasks", "status"), second.ranked().map { it.name })
    }

    @Test
    fun unknownNamesAreIgnored() {
        val store = PaletteStore(tempFile(), menu)
        store.record("retry") // dispatched, but not on the palette
        assertEquals(0L, store.ledger().of("retry"))
        assertEquals(0L, store.ledger().of("tasks"))
    }

    @Test
    fun theRegisteredOrderIsRemembered() {
        val file = tempFile()
        val store = PaletteStore(file, menu)
        assertEquals(emptyList(), store.registeredOrder().names)
        store.markRegistered(PaletteOrder(listOf("status", "tasks")))
        assertEquals(listOf("status", "tasks"), PaletteStore(file, menu).registeredOrder().names)
    }

    @Test
    fun aBrokenFileIsAnEmptyMemoryThatHealsOnWrite() {
        val file = tempFile()
        file.writeText("{not json")
        val store = PaletteStore(file, menu)
        assertEquals(0L, store.ledger().of("tasks"))
        store.record("tasks")
        assertEquals(1L, PaletteStore(file, menu).ledger().of("tasks"))
    }
}
