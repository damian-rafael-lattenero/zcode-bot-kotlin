package dev.lattenero.bot.channel

/**
 * THE CONNECTION'S SEQ FLOOR (14/11): `seq` is the fork's PER-CONNECTION
 * counter — one monotonic tally shared by EVERY subscribed task's stream
 * events AND every subscription's keepalive (the fork's daemonChannel law:
 * `connection.seq += 1` per delivered frame, any task). The old per-task
 * gap check read that shared counter as if it were per-task: with two live
 * subscriptions (a watched task plus its auto-subscribed children, or the
 * any-task watcher's follows), every event or heartbeat of the OTHER task
 * consumed seqs the watched task's state never saw, and its next frame
 * read as a lost-event GAP — a FALSE [RenderEvent.Resync] that wiped the
 * accumulating windows mid-stream (measured 14/11: 1095 spurious
 * «channel seq GAP» resyncs in one day, one every ~13s — the keepalive
 * cadence — while the owner watched a subagent with other sessions
 * streaming on the same connection; the day before: 94).
 *
 * The floor is the connection's OWN accounting: every frame the wire
 * delivers — stream event OR keepalive, ANY task — admits its seq here,
 * and only a jump past the LAST ADMITTED seq is a real loss. A seq at or
 * below the floor is a RECONNECT epoch (the fork restarts the counter per
 * connection): it re-baselines silently — the resubscribe path owns that
 * resync, not the gap rule.
 *
 * Single-reader law: the client's line pump serializes its callbacks, so
 * the floor carries no lock — one coroutine admits, nothing else touches.
 */
class ConnSeqFloor {

    private var last: Long? = null

    /**
     * Admits one delivered frame's seq. True ONLY when events were really
     * lost on this connection — the seq jumped past everything admitted so
     * far; false for the ordinary advance, the first frame, and the epoch
     * restart (a seq at or below the floor re-baselines it).
     */
    fun admit(seq: Long): Boolean {
        val previous = last
        val gap = previous != null && seq > previous + 1
        last = seq
        return gap
    }
}
