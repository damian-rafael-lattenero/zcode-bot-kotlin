package dev.lattenero.bot.channel

/**
 * The TRANSCRIPT FEED's level (the owner's 30/10 order: verbose BY
 * DEFAULT — «yo quiero VER los archivos, leerlos»). The publisher gates
 * its mid-turn sends per level; the status card exists and ticks at every
 * level, and the seal's answer + summary speak at every level.
 */
enum class FeedVerbosity {
    /** The transient status card only — no feed messages; the seal's answer + summary at the end. */
    SUMMARY,

    /** The feed as specced: each sealed CoT block, each tool CLOSE with its real content. */
    NORMAL,

    /** Everything as it happens: the tool OPEN one-liners ride too — the full transcript. */
    VERBOSE;

    companion object {
        /** The owner's default — the full transcript. */
        val DEFAULT = VERBOSE

        /**
         * `/verbosity <arg>`'s parse: the three tokens plus the Spanish
         * aliases (user-facing words are Spanish). Null = unknown arg —
         * the command answers with the picker instead of guessing.
         */
        fun of(arg: String): FeedVerbosity? = when (arg.trim().lowercase()) {
            "verbose", "detallado", "vv" -> VERBOSE
            "normal", "vn" -> NORMAL
            "summary", "resumen", "vs" -> SUMMARY
            else -> null
        }
    }
}

/**
 * The level's read/write seam: /verbosity writes it, the publisher reads
 * it PER TICK (the daemon injects the file-backed store — the publisher
 * never reads files; policy §7 the effects live at the edge).
 */
interface VerbositySetting {
    fun current(): FeedVerbosity

    fun set(level: FeedVerbosity)
}

/** The unwired default (tests, unwired daemons): in-memory, verbose. */
class VolatileVerbosity : VerbositySetting {
    @Volatile
    private var level: FeedVerbosity = FeedVerbosity.DEFAULT

    override fun current(): FeedVerbosity = level

    override fun set(level: FeedVerbosity) {
        this.level = level
    }
}
