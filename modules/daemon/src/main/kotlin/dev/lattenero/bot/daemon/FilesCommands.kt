package dev.lattenero.bot.daemon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * FILES OUT + /diff (30/10): the pure half of the two commands the wiring
 * intercepts BEFORE the text dispatcher (the document's upload needs the
 * transport's multipart leg; the diff's render is pure JSON-in). Honest by
 * construction: every refusal names its reason, nothing is ever invented —
 * a path that isn't absolute says so, a missing file says so, an oversized
 * file says the cap, a turn without file changes says «nothing changed».
 */
internal object FilesCommands {

    /** /file <path> — the plan: absolute only, exists, a file, 20 MB. */
    internal data class FilePlan(val file: File?, val refusal: String?)

    @Suppress("ReturnCount") // one guard per honest refusal — the plan's own shape
    internal fun planFileSend(rest: String): FilePlan {
        val path = rest.trim()
        if (path.isEmpty()) {
            return FilePlan(null, "📎 /file <absolute-path> — I send you the file as a Telegram document.")
        }
        if (!path.startsWith("/")) {
            return FilePlan(
                null,
                "⚠️ ABSOLUTE paths only («/…») — «$path» will not work: the bot lives on the owner's Mac.",
            )
        }
        val f = File(path)
        if (!f.exists()) return FilePlan(
            null,
            "⚠️ Does not exist: «$path» — " +
                "check the path (/file neither lists nor guesses).",
        )
        if (!f.isFile) {
            return FilePlan(null, "⚠️ Not a file: «$path» (a directory?) — I send files, not folders.")
        }
        if (f.length() > MAX_FILE_BYTES) {
            return FilePlan(
                null,
                "⚠️ «${f.name}» is ${f.length() / MB} MB — Telegram's ceiling is ${MAX_FILE_BYTES / MB} MB " +
                    "per document. Split it or pass it another way.",
            )
        }
        return FilePlan(f, null)
    }

    // ---- /diff ---------------------------------------------------------------

    /**
     * The snapshot's fileChanges (the fileChangeTracker's own serialized
     * form: rows of {turnIndex, snapshots: [{path, beforeContent,
     * afterContent, writeCount}]}) parsed into per-file rows of the LAST
     * turn — «the current turn». `all` walks every turn (the session's whole
     * file story, first-write-merged by the tracker itself).
     */
    internal data class ChangedFile(val path: String, val before: String?, val after: String, val writeCount: Int)

    @Suppress("ReturnCount") // one guard per honesty tier: unparseable, no rows, empty turn
    internal fun changedFilesOf(snapshotJson: String, all: Boolean): List<ChangedFile> {
        val root = runCatching { Json.parseToJsonElement(snapshotJson) as? JsonObject }.getOrNull()
            ?: return emptyList()
        val rows = root["fileChanges"] as? JsonArray ?: return emptyList()
        val turns = rows.mapNotNull { it as? JsonObject }
        if (turns.isEmpty()) return emptyList()
        val picked = if (all) turns else listOf(turns.last())
        return picked.flatMap { turn ->
            ((turn["snapshots"] as? JsonArray) ?: JsonArray(emptyList()))
                .mapNotNull { it as? JsonObject }
                .mapNotNull { snap ->
                    val path = snap.str("path") ?: return@mapNotNull null
                    val after = snap.str("afterContent") ?: return@mapNotNull null
                    ChangedFile(
                        path = path,
                        // JSON null (a NEW file) already decodes absent — str() demands a string
                        before = snap.str("beforeContent"),
                        after = after,
                        writeCount = snap.long("writeCount")?.toInt() ?: 1,
                    )
                }
        }
    }

    /**
     * /diff's render: the changed-files list with +A/−D per file and the
     * hunks INLINE when small (per-file cap, then a whole-reply cap — the
     * pipe's law). A file over the inline cap says so honestly and points
     * at /file for the full body. Zero invented content: a file whose
     * before was null renders «(new)».
     */
    internal fun renderDiff(files: List<ChangedFile>, all: Boolean, maxReplyChars: Int): String {
        if (files.isEmpty()) return "📭 The turn changed no files (or the fork does not record it yet)."
        val scope = if (all) "the whole session" else "the last turn"
        val out = StringBuilder("📄 <b>Changed files</b> ($scope) — ${files.size}:\n")
        for (f in files) {
            val (added, removed) = MiniDiff.stats(f.before, f.after)
            val origin = if (f.before == null) " (new)" else ""
            val head = "• <code>${esc(f.path)}</code>$origin +$added/−$removed"
            val hunks = MiniDiff.render(f.before, f.after, PER_FILE_DIFF_LINES, path = f.path)
            val body = buildString {
                if (hunks.size >= PER_FILE_DIFF_LINES) {
                    append("\n   ✂️ the full diff does not fit — /file ${f.path}")
                } else {
                    // A `pre` keeps the alignment AND the escaping: a diff line
                    // is file content, never markup (measured 24/11: a hunk with
                    // `<` could break the whole HTML parse).
                    append("\n<pre>")
                    hunks.forEach { append(esc(it)).append('\n') }
                    append("</pre>")
                }
            }
            val candidate = out.length + head.length + body.length + 2
            if (candidate > maxReplyChars) {
                out.append("\n• ✂️ …and ").append(files.size - files.indexOf(f))
                    .append(" more file(s) — the reply has a roof; /file <path> for any of them.")
                break
            }
            out.append(head).append(body).append("\n")
        }
        return out.toString().trimEnd()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    internal const val PER_FILE_DIFF_LINES = 24

    private const val MB = 1024L * 1024L
    internal const val MAX_FILE_BYTES = 20L * MB
}

/**
 * A tiny LCS line diff — the bot's own, no dependency: enough for «what
 * changed in this file this turn». Inputs are line-capped (a 4K-line file
 * diffed line-by-line is already beyond a phone reply): beyond the cap the
 * renderer says so instead of pretending.
 */
internal object MiniDiff {

    private const val LINE_CAP = 400 // above this the file points at /file — a phone never reads a 500-line diff inline

    /** (added, removed) line counts — before null means every after line is added. */
    internal fun stats(before: String?, after: String): Pair<Int, Int> {
        if (before == null) return after.count { it == '\n' } + 1 to 0
        val ops = ops(before.lines(), after.lines())
        return ops.count { it is Op.Add } to ops.count { it is Op.Del }
    }

    /** The diff as phone-friendly lines: "@@ a→b @@" markers, "+ " / "− " bodies, capped. */
    @Suppress("ReturnCount") // the before/after/empty trio — the render's own shapes
    internal fun render(before: String?, after: String, maxLines: Int, path: String = "<path>"): List<String> {
        val beforeLines = before?.lines()
        val afterLines = after.lines()
        if ((beforeLines?.size ?: 0) > LINE_CAP || afterLines.size > LINE_CAP) {
            return listOf("✂️ file too large for an inline diff — /file $path")
        }
        val ops = ops(beforeLines ?: emptyList(), afterLines)
        val out = mutableListOf<String>()
        // THE OWNER'S LINE-NUMBER LAW (3/11): every body line carries its
        // REAL position — new file for +, old file for − — so a hunk read
        // on the phone is never an anonymous snippet. No @@ header: the
        // file's own +A/−D head already speaks the totals.
        var oldNo = 1
        var newNo = 1
        for (op in ops) {
            when (op) {
                is Op.Add -> {
                    out.add("+ $newNo│ ${op.line}")
                    newNo++
                }

                is Op.Del -> {
                    out.add("− $oldNo│ ${op.line}")
                    oldNo++
                }

                is Op.Same -> {
                    oldNo++
                    newNo++
                }
            }
            if (out.size >= maxLines) return out.take(maxLines) + listOf("✂️ …")
        }
        if (out.isEmpty()) out.add("(no text changes)")
        return out
    }

    private sealed interface Op {
        data class Add(val line: String) : Op

        data class Del(val line: String) : Op

        data class Same(val line: String) : Op
    }

    /** Classic LCS table diff (O(n·m) — the phone-side files are small). */
    private fun ops(a: List<String>, b: List<String>): List<Op> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    out.add(Op.Same(a[i]))
                    i++
                    j++
                }

                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.add(Op.Del(a[i]))
                    i++
                }

                else -> {
                    out.add(Op.Add(b[j]))
                    j++
                }
            }
        }
        while (i < n) out.add(Op.Del(a[i++]))
        while (j < m) out.add(Op.Add(b[j++]))
        return out
    }
}

// tiny JSON readers (the channel module's own are internal there — the daemon keeps its two)
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
