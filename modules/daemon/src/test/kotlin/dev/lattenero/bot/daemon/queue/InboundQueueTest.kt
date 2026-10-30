package dev.lattenero.bot.daemon.queue

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The dormant queue's file contract: park, count, drain-takes-all, crash-safe rewrite. */
class InboundQueueTest {

    @TempDir
    lateinit var dir: File

    private val queueFile: File get() = File(dir, "inbound-queue.json")

    @Test
    fun addCountsAndDrainTakesEverything() {
        val q = InboundQueue(queueFile)
        assertEquals(1, q.add("hola", "tg-1", 100L))
        assertEquals(2, q.add("y esto", "tg-2", 200L))
        val drained = q.drain()
        assertEquals(listOf("hola" to "tg-1", "y esto" to "tg-2"), drained.map { it.text to it.messageId })
        assertEquals(100L, drained[0].receivedAt)
        assertTrue(q.drain().isEmpty()) // taken: the second drain sees nothing
        assertEquals(1, q.add("otra", "tg-3", 300L)) // the file keeps working after a drain
    }

    @Test
    fun missingOrBrokenFileAnswersEmptyAndKeepsWorking() {
        val q = InboundQueue(queueFile)
        assertTrue(q.drain().isEmpty())
        queueFile.writeText("not json at all")
        assertTrue(q.drain().isEmpty())
        assertEquals(1, q.add("de nuevo", "tg-9", 1L))
    }

    @Test
    fun entriesPeeksWithoutDraining() {
        val q = InboundQueue(queueFile)
        assertTrue(q.entries().isEmpty())
        q.add("hola", "tg-1", 100L)
        q.add("cha", "tg-2", 200L)
        assertEquals(listOf("hola" to "tg-1", "cha" to "tg-2"), q.entries().map { it.text to it.messageId })
        assertEquals(2, q.entries().size) // the peek is repeatable — nothing was taken
    }

    @Test
    fun takeRemovesOneByUpdateIdentity() {
        val q = InboundQueue(queueFile)
        q.add("hola", "tg-1", 100L)
        q.add("cha", "tg-2", 200L)
        val hit = q.take("tg-2")
        assertEquals("cha", hit?.text)
        assertEquals(listOf("hola" to "tg-1"), q.entries().map { it.text to it.messageId })
        assertEquals(null, q.take("tg-2")) // already gone: the honest null, not an error
        assertEquals(null, q.take("tg-404"))
    }
}
