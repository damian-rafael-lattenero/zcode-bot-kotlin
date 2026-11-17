package dev.lattenero.bot.daemon.palette

import dev.lattenero.bot.channel.MenuCommand
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The refresher's laws: the first pass registers (no order is remembered
 * yet), an unchanged order registers NOTHING, a real move registers ONCE,
 * and a failed registration leaves the memory untouched so the next pass
 * retries.
 */
class PaletteRefresherTest {

    private val menu = listOf(
        MenuCommand("tasks", "t"),
        MenuCommand("status", "s"),
        MenuCommand("last", "l"),
    )

    private fun store(): PaletteStore =
        PaletteStore(File(Files.createTempDirectory("palette").toFile(), "usage.json"), menu)

    @Test
    fun theFirstPassRegistersAndTheSecondIsSilent() = runBlocking {
        val store = store()
        val sent = mutableListOf<List<MenuCommand>>()
        val refresher = PaletteRefresher(store) { ranked ->
            sent.add(ranked)
            true
        }
        assertTrue(refresher.pass(), "an empty registered order is a real change")
        assertEquals(1, sent.size)
        assertFalse(refresher.pass(), "the store now remembers the order")
        assertEquals(1, sent.size)
    }

    @Test
    fun aRankingMoveRegistersOnceWithTheNewOrder() = runBlocking {
        val store = store()
        val sent = mutableListOf<List<MenuCommand>>()
        val refresher = PaletteRefresher(store) { ranked ->
            sent.add(ranked)
            true
        }
        refresher.pass()
        store.record("last")
        store.record("last")
        assertTrue(refresher.pass())
        assertEquals(listOf("last", "tasks", "status"), sent.last().map { it.name })
        assertFalse(refresher.pass())
        assertEquals(2, sent.size)
    }

    @Test
    fun aFailedRegistrationRetriesOnTheNextPass() = runBlocking {
        val store = store()
        var reachable = false
        val refresher = PaletteRefresher(store) { reachable }
        assertFalse(refresher.pass())
        assertEquals(emptyList(), store.registeredOrder().names, "the memory must not move on a failure")
        reachable = true
        assertTrue(refresher.pass())
        assertEquals(listOf("tasks", "status", "last"), store.registeredOrder().names)
    }
}
