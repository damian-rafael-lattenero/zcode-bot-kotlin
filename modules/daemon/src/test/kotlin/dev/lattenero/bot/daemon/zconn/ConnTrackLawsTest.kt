package dev.lattenero.bot.daemon.zconn

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The machine's algebra, property-checked (the KAP standard, policy §6):
 * what must hold for EVERY event sequence, not just the incidents we
 * already lived. kotest 6's checkAll is suspend — runTest hosts it.
 */
class ConnTrackLawsTest {

    private val kinds = Arb.of(
        "model.network.failed",
        "model.request.failed",
        "model.network.completed",
        "model.tokens.counted", // noise the scanner never yields, paranoia
    )

    /**
     * Law 1 — episode integrity: no two consecutive Downs, every Up anchors
     * at the announced (or silently re-opened) episode's first down, and
     * consecutive Ups (the sputter re-announce) share that same anchor.
     */
    @Test
    fun episodeAnchorsHold() = runTest {
        checkAll(Arb.list(Arb.pair(Arb.int(0..600), kinds), 0..200)) { raw ->
            var clock = 0.0
            var track = ConnTrack()
            val notices = raw.flatMap { (dt, ev) ->
                clock += dt
                val (next, spoken) = ConnTrackMachine.feed(track, clock, ev, "r", "p")
                track = next
                spoken
            }
            var expectedAnchor: Double? = null
            var lastUpTs = 0.0
            var lastKind: String? = null
            notices.forEach { n ->
                when (n) {
                    is ZconnNotice.Down -> {
                        assertTrue(lastKind != "Down", "two downs announced in a row")
                        expectedAnchor = n.ts
                        lastKind = "Down"
                    }

                    is ZconnNotice.Up -> {
                        assertEquals(expectedAnchor, n.firstDown, "the up must anchor its episode's first down")
                        assertTrue(n.ts >= lastUpTs, "ups are announced in order")
                        lastUpTs = n.ts
                        lastKind = "Up"
                    }
                }
            }
        }
    }

    @Test
    fun aRefailWithinTheSputterWindowIsSilentAndTheUpSpansTheWholeEpisode() = runTest {
        checkAll(Arb.int(1..500), Arb.int(0..119)) { upGap, refailGap ->
            var track = ConnTrack()
            track = ConnTrackMachine.feed(track, 100.0, "model.network.failed", "first", "").first
            track = ConnTrackMachine.feed(track, 100.0 + upGap, "model.network.completed", "", "").first
            val refailAt = 100.0 + upGap + refailGap
            val refail = ConnTrackMachine.feed(track, refailAt, "model.network.failed", "again", "")
            assertEquals(emptyList(), refail.second, "a refail ${refailGap}s after the recovery is the same episode")
            val up = ConnTrackMachine.feed(refail.first, refailAt + 1000.0, "model.network.completed", "", "")
            assertEquals(listOf(ZconnNotice.Up(100.0, refailAt + 1000.0)), up.second)
        }
    }

    @Test
    fun noiseNeverSpeaks() = runTest {
        checkAll(Arb.list(Arb.of("model.tokens.counted", "tool.call.started"), 0..100)) { events ->
            var clock = 0.0
            var track = ConnTrack()
            val spoke = events.any { ev ->
                clock += 30.0
                val (next, notices) = ConnTrackMachine.feed(track, clock, ev, "r", "p")
                track = next
                notices.isNotEmpty()
            }
            assertTrue(!spoke)
        }
    }
}
