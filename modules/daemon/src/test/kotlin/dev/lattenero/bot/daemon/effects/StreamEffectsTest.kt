package dev.lattenero.bot.daemon.effects

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mailbox's laws: effects run IN ORDER, a failing effect never stops
 * the next one (the pump's isolation), and a full queue DROPS AND COUNTS
 * instead of suspending the reader.
 */
class StreamEffectsTest {

    @Test
    fun effectsRunInOrder() = runBlocking {
        val effects = StreamEffects()
        val seen = mutableListOf<String>()
        val worker = launch { effects.drain { effect -> seen.add(effect::class.simpleName ?: "?") } }
        effects.emit(StreamEffect.DrainQueue)
        effects.emit(StreamEffect.EnforceMode("task-a"))
        effects.emit(StreamEffect.SealMirror("task-b"))
        withTimeout(2_000) { while (seen.size < 3) delay(10) }
        assertEquals(listOf("DrainQueue", "EnforceMode", "SealMirror"), seen)
        worker.cancelAndJoin()
    }

    @Test
    fun aFailingEffectNeverStopsTheNext() = runBlocking {
        val effects = StreamEffects()
        val seen = mutableListOf<String>()
        val worker = launch {
            effects.drain { effect ->
                if (effect is StreamEffect.EnforceMode) error("boom")
                seen.add(effect::class.simpleName ?: "?")
            }
        }
        effects.emit(StreamEffect.EnforceMode("task-a"))
        effects.emit(StreamEffect.SealMirror("task-b"))
        withTimeout(2_000) { while (seen.isEmpty()) delay(10) }
        assertEquals(listOf("SealMirror"), seen)
        worker.cancelAndJoin()
    }

    @Test
    fun aFullQueueDropsAndCounts() {
        val effects = StreamEffects(capacity = 1)
        effects.emit(StreamEffect.DrainQueue)
        effects.emit(StreamEffect.SealMirror("task-b"))
        assertEquals(1L, effects.dropped, "the pump never suspends on a full queue")
    }
}
