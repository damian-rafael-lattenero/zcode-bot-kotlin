package dev.lattenero.bot.daemon.brain

import dev.lattenero.bot.channel.BrainReply
import dev.lattenero.bot.daemon.bus.PromptTap
import dev.lattenero.bot.daemon.metrics.MicrometerMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * memory_inject (ADR-020): ON + results prepends a bounded block; empty /
 * down / timeout / HTTP error send the RAW text (silence, never a block)
 * each with one daemon.jsonl line; OFF is byte-for-byte the old prompt.
 */
class MemoryInjectorTest {

    private val searchJson = """
        {"query":"cómo despliego","embedModel":"bge-m3","memories":[
          {"id":42,"kind":"preference","content":"commits chicos","score":0.83},
          {"id":41,"kind":"fact","content":"el daemon usa launchd","score":0.61}
        ]}
    """.trimIndent()

    private fun injector(
        reply: BrainReply = BrainReply.Response(200, searchJson),
        log: (String) -> Unit = {},
        timeout: kotlin.time.Duration = MemoryInjector.DEFAULT_TIMEOUT,
        calls: MutableList<Pair<String, Int>>? = null,
    ): MemoryInjector = MemoryInjector(
        search = { query, k ->
            calls?.add(query to k)
            reply
        },
        log = log,
        timeout = timeout,
    )

    @Test
    fun onWithResultsPrependsTheBlockBeforeThePrompt() = runBlocking {
        val text = "cómo despliego el daemon"
        val out = injector().prepend(text)
        assertTrue(out.startsWith("Contexto de memoria (agent-brain):"))
        assertTrue("- [preference #42] commits chicos" in out)
        assertTrue("- [fact #41] el daemon usa launchd" in out)
        assertTrue(out.endsWith("\n\n$text"), "the owner's words stay last, whole")
        assertTrue(out.indexOf("Contexto de memoria") < out.indexOf(text))
    }

    @Test
    fun theSearchAsksForTopKThree() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        injector(calls = calls).prepend("x")
        assertEquals(listOf("x" to 3), calls)
    }

    @Test
    fun downSendsTheRawTextAndLogsOnce() = runBlocking {
        val logged = mutableListOf<String>()
        val text = "arreglá el login"
        val out = injector(reply = BrainReply.Down("ConnectException"), log = { logged += it }).prepend(text)
        assertEquals(text, out) // byte for byte
        assertTrue(logged.single().contains("caído"))
    }

    @Test
    fun aTimeoutSendsTheRawTextFast() = runBlocking {
        val logged = mutableListOf<String>()
        val text = "texto"
        val started = System.currentTimeMillis()
        val out = MemoryInjector(
            search = { _, _ ->
                delay(2_000)
                BrainReply.Response(200, searchJson)
            },
            log = { logged += it },
            timeout = 100.milliseconds,
        ).prepend(text)
        val elapsed = System.currentTimeMillis() - started
        assertEquals(text, out)
        assertTrue(elapsed < 1_500, "the deadline must not block the send (took ${elapsed}ms)")
        assertTrue(logged.single().contains("timeout"))
    }

    @Test
    fun emptyResultsSendTheRawText() = runBlocking {
        val logged = mutableListOf<String>()
        val out = injector(
            reply = BrainReply.Response(200, """{"query":"x","embedModel":"m","memories":[]}"""),
            log = { logged += it },
        ).prepend("hola")
        assertEquals("hola", out)
        assertTrue(logged.single().contains("sin recuerdos"))
    }

    @Test
    fun anHttpErrorSendsTheRawText() = runBlocking {
        val logged = mutableListOf<String>()
        val out = injector(reply = BrainReply.Response(500, "boom"), log = { logged += it }).prepend("hola")
        assertEquals("hola", out)
        assertTrue(logged.single().contains("HTTP 500"))
    }

    @Test
    fun aThrowingSearchNeverBreaksTheSend() = runBlocking {
        val logged = mutableListOf<String>()
        val out = MemoryInjector(
            search = { _, _ -> throw IllegalStateException("kaboom") },
            log = { logged += it },
        ).prepend("hola")
        assertEquals("hola", out)
        assertTrue(logged.single().contains("search threw"))
    }

    @Test
    fun aBlankPromptNeverCallsTheBrain() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val text = "   "
        assertEquals(text, injector(calls = calls).prepend(text))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun offIsByteForByteTheOldPrompt() = runBlocking {
        val text = "ñandú 🧠 <b>crudo</b>"
        assertEquals(text, PromptTap(null).prepare(text))
    }

    @Test
    fun aCommandLineIsNeverInjected() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val tap = PromptTap(null, inject = injector(calls = calls))
        assertEquals("/new", tap.prepare("/new"))
        assertEquals("   ", tap.prepare("   ")) // blank yet unchanged: nothing to inject
        assertTrue(calls.isEmpty(), "the brain is never asked for a command line")
    }

    @Test
    fun theKnobDefaultsOffAndReadsTheSameFile() {
        val dir = Files.createTempDirectory("memory-inject-knob").toFile()
        val file = File(dir, "daemon.json")
        assertFalse(MemoryInjectKnob.load(File(dir, "missing.json")).on)
        file.writeText("""{"send":"live","event_bus":true}""")
        assertFalse(MemoryInjectKnob.load(file).on)
        file.writeText("""{"memory_inject":true}""")
        assertTrue(MemoryInjectKnob.load(file).on)
        file.writeText("""{"memory_inject":"ture"}""")
        val typo = MemoryInjectKnob.load(file)
        assertFalse(typo.on)
        assertTrue(typo.warning!!.contains("ture"))
    }

    /** E2's taps: the same events the log lines speak, counted for real. */
    @Test
    fun theMetricsTapCountsTheRealOutcomes() = runBlocking {
        val reg = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val m = MicrometerMetrics(reg)
        val injected = MemoryInjector(search = { _, _ -> BrainReply.Response(200, searchJson) }, metrics = m)
        val timingOut = MemoryInjector(search = { _, _ -> BrainReply.Timeout }, metrics = m)
        val httpError = MemoryInjector(search = { _, _ -> BrainReply.Response(500, "boom") }, metrics = m)
        val empty = MemoryInjector(search = { _, _ -> BrainReply.Response(200, """{"memories":[]}""") }, metrics = m)
        injected.prepend("uno")
        timingOut.prepend("dos")
        httpError.prepend("tres")
        empty.prepend("cuatro") // "sin recuerdos" is neither an injection nor a failure
        val scrape = reg.scrape()
        assertEquals(1.0, valueOf(scrape, "bot_memory_injected_total"))
        assertEquals(2.0, valueOf(scrape, "bot_memory_inject_failures_total"))
        assertEquals(4.0, valueOf(scrape, "bot_memory_fetch_seconds_count"), "every fetch rode the timer")
    }

    private fun valueOf(scrape: String, metric: String): Double? =
        scrape.lineSequence()
            .firstOrNull { it.startsWith(metric) }
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()
}
