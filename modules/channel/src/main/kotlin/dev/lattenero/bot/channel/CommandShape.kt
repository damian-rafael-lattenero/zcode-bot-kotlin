package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * COMMAND SHAPING (25/11, measured with four live probes): this client
 * WRAPS every code block — classic and rich, any language slug, alone or
 * under a header — so a long one-liner arrives cut at an arbitrary token
 * («| from» / «json | get»). The wrap cannot be disabled, so the bot breaks
 * the line itself at the only semantically safe seams: the UNQUOTED pipes.
 * Each stage gets its line, the continuations align under a leading `|` —
 * valid Nushell, so the copied command still runs, and the pipeline reads
 * as the plan it is.
 *
 * Only COMMANDS are shaped: output, file pages and diffs are data, and a
 * data line is never reflowed.
 */
object CommandShape {

    /** The line budget: ~50 monospace cells fit the owner's chat width (measured). */
    const val LINE_CP = 50

    /** The continuation prefix — the pipe that keeps the pipeline alive. */
    private const val PIPE = "  | "

    /** Shape each long line at its unquoted ` | ` seams; short lines pass untouched. */
    fun shape(command: String, maxCp: Int = LINE_CP): String =
        PyText.splitLines(command).joinToString("\n") { shapeLine(it, maxCp) }

    private fun shapeLine(line: String, maxCp: Int): String {
        if (PyText.cpLength(line) <= maxCp) return line
        val stages = splitStages(line)
        return if (stages.size < 2) line else pack(stages, maxCp)
    }

    /** Greedy fill: stages share a line while the budget holds, else a `|`-led line. */
    private fun pack(stages: List<String>, maxCp: Int): String {
        val out = mutableListOf<String>()
        var current = stages.first()
        for (stage in stages.drop(1)) {
            val candidate = "$current | $stage"
            if (PyText.cpLength(candidate) <= maxCp) {
                current = candidate
            } else {
                out += current
                current = "$PIPE$stage"
            }
        }
        out += current
        return out.joinToString("\n")
    }

    /** The unquoted ` | ` seams — a pipe inside quotes is data, never a split. */
    @Suppress("CyclomaticComplexMethod") // a char scanner: one branch per char class, the shape itself
    internal fun splitStages(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var single = false
        var double = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && double && i + 1 < line.length -> {
                    current.append(c).append(line[i + 1])
                    i += 1
                }

                c == '\'' && !double -> {
                    single = !single
                    current.append(c)
                }

                c == '"' && !single -> {
                    double = !double
                    current.append(c)
                }

                c == '|' && !single && !double && current.isNotEmpty() && current.last() == ' ' &&
                    i + 1 < line.length && line[i + 1] == ' ' -> {
                    out += current.toString().trimEnd()
                    current.clear()
                    i += 1 // the space after the pipe — never part of the next stage
                }

                else -> current.append(c)
            }
            i += 1
        }
        out += current.toString().trimEnd()
        return out.filter { it.isNotBlank() }
    }
}
