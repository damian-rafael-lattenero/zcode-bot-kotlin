package dev.lattenero.bot.daemon.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sink's torn-line law: every append is ONE complete JSON line ended by
 * `\n` (agent-brain tails by byte offset), and a failed write never throws
 * into the producer.
 */
class BusSinkTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("bus-sink").toFile()
        return File(dir, "bus-events.jsonl")
    }

    @Test
    fun everyAppendIsOneCompleteLineEndedByNewline() = runBlocking {
        val file = tempFile()
        val sink = BusSink(file)
        assertTrue(sink.append("""{"id":"a","n":1}"""))
        assertTrue(sink.append("""{"id":"b","n":2}"""))
        assertEquals(2, sink.written)
        assertEquals(0, sink.failures)
        val bytes = file.readBytes()
        assertEquals('\n'.code.toByte(), bytes.last())
        val lines = file.readText().split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        lines.forEach { Json.parseToJsonElement(it).jsonObject } // each line stands alone
    }

    @Test
    fun aMissingNewlineIsAddedNeverDuplicated() = runBlocking {
        val file = tempFile()
        val sink = BusSink(file)
        sink.append("{\"id\":\"a\"}\n")
        assertEquals("""{"id":"a"}""" + "\n", file.readText())
    }

    @Test
    fun parentDirectoriesAreCreated() = runBlocking {
        val file = File(tempFile().parentFile, "deep/er/bus-events.jsonl")
        val sink = BusSink(file)
        assertTrue(sink.append("""{"id":"a"}"""))
        assertTrue(file.exists())
    }

    @Test
    fun aFailedWriteIsCountedLoggedAndNeverThrown() = runBlocking {
        val dir = Files.createTempDirectory("bus-sink-dir").toFile() // a DIRECTORY is not appendable
        val logged = mutableListOf<String>()
        val sink = BusSink(dir, log = { logged += it })
        assertFalse(sink.append("""{"id":"a"}"""))
        assertEquals(1, sink.failures)
        assertEquals(0, sink.written)
        assertTrue(logged.single().contains("bus append failed"))
    }

    @Test
    fun concurrentAppendsNeverTearALine() = runBlocking {
        val file = tempFile()
        val sink = BusSink(file)
        val jobs = (1..60).map { n ->
            async(Dispatchers.Default) {
                sink.append("""{"id":"e$n","pad":"${"x".repeat(n * 7)}"}""")
            }
        }
        assertTrue(jobs.awaitAll().all { it })
        val lines = file.readText().split("\n").filter { it.isNotEmpty() }
        assertEquals(60, lines.size)
        lines.forEach { line -> Json.parseToJsonElement(line).jsonObject }
    }

    /**
     * THE SINGLE-WRITER LAW under the suspending mutex (the audit's BUS
     * finding): two continuous appenders on real worker threads, 100 lines
     * each — 200 intact lines, no torn tail, counters exact. The mutex makes
     * the interleaving serial at the line's granularity; the O_APPEND one-
     * write law keeps each landed line whole.
     */
    @Test
    fun twoConcurrentAppendersWriteTwoHundredIntactLinesWithExactCounters() = runBlocking {
        val file = tempFile()
        val sink = BusSink(file)
        val jobs = listOf(
            launch(Dispatchers.Default) {
                repeat(100) { n -> sink.append("""{"w":"a","n":$n,"pad":"${"x".repeat(n * 3)}"}""") }
            },
            launch(Dispatchers.Default) {
                repeat(100) { n -> sink.append("""{"w":"b","n":$n,"pad":"${"y".repeat(n * 5)}"}""") }
            },
        )
        jobs.joinAll()
        assertEquals(200, sink.written, "every append landed — the mutex loses none")
        assertEquals(0, sink.failures)
        val lines = file.readText().split("\n").filter { it.isNotEmpty() }
        assertEquals(200, lines.size)
        lines.forEach { line -> Json.parseToJsonElement(line).jsonObject } // no torn tail anywhere
    }
}
