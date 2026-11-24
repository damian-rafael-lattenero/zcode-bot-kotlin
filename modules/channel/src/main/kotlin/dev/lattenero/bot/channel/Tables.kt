package dev.lattenero.bot.channel

import dev.lattenero.bot.contracts.PyText

/**
 * TABLES (24/11): the ONE builder for Telegram's native rich table. The bot
 * already turns a nu box table into this shape ([NuTable]); the command
 * cards that ARE data grids (help, tasks, quota…) build it directly.
 *
 * `<table bordered>` is the rich-HTML tag the phone renders natively; every
 * cell is escaped and inline-only (the API's law: no block content inside a
 * cell).
 */
object Tables {

    /** Header row + data rows as a bordered native table. */
    fun html(header: List<String>, rows: List<List<String>>): String = buildString {
        append("<table bordered><tr>")
        header.forEach { cell -> append("<th>").append(PyText.esc(cell)).append("</th>") }
        append("</tr>")
        rows.forEach { row ->
            append("<tr>")
            row.forEach { cell -> append("<td>").append(PyText.esc(cell)).append("</td>") }
            append("</tr>")
        }
        append("</table>")
    }
}
