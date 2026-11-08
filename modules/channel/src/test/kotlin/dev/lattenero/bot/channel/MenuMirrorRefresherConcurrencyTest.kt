package dev.lattenero.bot.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE MIRROR'S RACE LAW (the audit's M finding): the poller's speakReply
 * path ([MenuMirrorRefresher.noteMenu]) and the channel's pump
 * ([MenuMirrorRefresher.seal] / [MenuMirrorRefresher.onConfigUpdate]) are
 * two different coroutines on different threads folding the SAME registry.
 * The old `@Volatile var` answered `state = state.register(...)` —
 * visibility WITHOUT atomicity — so two concurrent folds could lose one
 * registration for good, and a register could resurrect a sealed entry by
 * re-writing a state that predated the seal.
 *
 * The fold invariant this test pins, with the mutex-backed kap `Ref`:
 * 1. every registered menu that was NEVER sealed is still present;
 * 2. every sealed menu stays sealed — a seal that linearizes after a
 *    register wins; no later fold resurrects the entry.
 *
 * REAL THREADS (`runBlocking` + `Dispatchers.Default`): the bug was
 * cross-thread; a single-threaded or virtual-time test would lie.
 */
class MenuMirrorRefresherConcurrencyTest {

    @Test
    fun racingRegistersAndSealsNeverLoseARegistrationNorResurrectASealedOne() = runBlocking {
        // The observation channel: a config event for a LIVE menu edits it
        // exactly once (the fresh read always differs from the baseline);
        // for a dead one, nothing. The list is only touched after joinAll.
        val edits = mutableListOf<Long>()
        val refresher = MenuMirrorRefresher.create(
            readMode = { _, _ -> "changed" }, // differs from the registered text → an edit whenever open
            readPlan = { null },
            titleOf = { null },
            edit = { _, messageId, _, _ ->
                edits.add(messageId)
                true
            },
        )
        val chat = JsonPrimitive(7L)
        val tasks = (0 until 2_000).map { "task-%04d".format(it) }
        val messageIdOf = tasks.withIndex().associate { (i, sid) -> sid to (i + 1).toLong() }
        // the EVEN tasks are the sealed ones; the odd ones are never sealed
        val sealedSet = tasks.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val writersDone = CompletableDeferred<Unit>()

        // TWO writers on real worker threads — the exact shape that lost
        // registrations on the volatile cell: every registration is a
        // read-modify-write of the SAME registry, interleaved arbitrarily.
        val writers = (0..1).map { writer ->
            launch(Dispatchers.Default) {
                tasks.withIndex().forEach { (i, sid) ->
                    if (i % 2 == writer) {
                        refresher.noteMenu(
                            sid,
                            MenuMirror.Mark(sid, "/ws", MenuMirror.Kind.MODE),
                            messageId = messageIdOf.getValue(sid),
                            chatId = chat,
                            text = "baseline",
                        )
                    }
                }
            }
        }
        // TWO sealers hammering the even tasks while the registrations fly:
        // whichever operation linearizes after a register must win for that
        // task — a seal after a register removes it, a register after a seal
        // keeps the registry consistent either way.
        val sealers = (0..1).map {
            launch(Dispatchers.Default) {
                while (!writersDone.isCompleted) sealedSet.forEach { refresher.seal(it) }
            }
        }

        writers.joinAll()
        writersDone.complete(Unit)
        sealers.joinAll()

        // One last SYNCHRONOUS seal sweep: from here every even task has been
        // sealed after any register that could have landed — absence is the
        // only honest answer for a sealed menu.
        sealedSet.forEach { refresher.seal(it) }

        // The fold invariant, observed through the refresher's own gate: the
        // mirror edits a live menu once and a dead one never.
        tasks.forEach { sid ->
            refresher.onConfigUpdate(sid, listOf(ConfigOptionsUpdate.Option("mode", "plan")))
        }

        val neverSealed = tasks.filterIndexed { i, _ -> i % 2 == 1 }
        val expected = neverSealed.map { messageIdOf.getValue(it) }.toSet()
        assertEquals(expected, edits.toSet(), "every never-sealed registration survived the race — no lost update")
        assertEquals(expected.size, edits.size, "each live menu edits exactly once — no ghost folds, no resurrection")
    }
}
