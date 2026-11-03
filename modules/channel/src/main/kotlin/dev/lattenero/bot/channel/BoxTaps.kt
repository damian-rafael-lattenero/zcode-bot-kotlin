package dev.lattenero.bot.channel

/**
 * The collapsed tool outputs' taps (the `bp:`/`bf:` buttons, the owner's
 * 3/11): open/flip a numbered page IN PLACE, or fold the message back to
 * its one-line header — a shell's output travels COMPRESSED behind one
 * button and opens in pages, never a wall of text. U5b: the STATE is the
 * fold's ([RenderState.boxes]); the implementor is the tap EDGE (the
 * publisher) — it renders FROM the fold, edits the message, and folds what
 * landed ([RenderEvent.Page]).
 */
interface BoxTaps {
    /** Opens (or flips to) a page; a non-null String is the toast (a miss). */
    suspend fun open(registryId: Int, page: Int): String?

    /** Folds the message back to its one-line header. */
    suspend fun fold(registryId: Int): String?

    companion object {
        /** A tap on an output the fold no longer knows (an old turn, a bot restart). */
        const val MISS = "🤷 Esa salida ya no está en el registro — el botón quedó de otro turno."
    }
}
