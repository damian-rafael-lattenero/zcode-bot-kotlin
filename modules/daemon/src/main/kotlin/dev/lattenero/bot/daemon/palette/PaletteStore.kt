package dev.lattenero.bot.daemon.palette

import dev.lattenero.bot.channel.BotCommands
import dev.lattenero.bot.channel.CommandUsage
import dev.lattenero.bot.channel.MenuCommand
import dev.lattenero.bot.channel.PaletteOrder
import dev.lattenero.bot.channel.PaletteNames
import dev.lattenero.bot.channel.UsageLedger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * M1's persisted memory: the owner's [UsageLedger] and the [PaletteOrder]
 * Telegram holds RIGHT NOW. One JSON file under the daemon's home, atomic
 * tmp+fsync+rename (the house write invariant).
 *
 * The store is the ORDER's single writer: [record] counts a dispatch,
 * [ranked] folds the ledger through the pure [CommandUsage.rank], and
 * [markRegistered] remembers a flight — so the refresher re-registers only
 * when the order really changed (never one setMyCommands per keystroke).
 * A broken file is an empty memory (the ranking degrades, never crashes).
 */
class PaletteStore(
    private val file: File,
    private val menu: List<MenuCommand> = BotCommands.MENU,
) {
    private data class Memory(
        val ledger: UsageLedger = UsageLedger.EMPTY,
        val registered: PaletteOrder = PaletteOrder(emptyList()),
    )

    /** The palette's own names, as one value — [record]'s gate. */
    private val paletteNames: PaletteNames = PaletteNames.of(menu)

    private var memory: Memory = load()

    /** One dispatch: the canonical palette name (the wiring's tap). Unknown names are ignored. */
    @Synchronized
    fun record(name: String) {
        if (name !in paletteNames) return
        memory = memory.copy(ledger = memory.ledger.plusOne(name))
        save()
    }

    /** The menu ordered by REAL usage — the palette's next body. */
    @Synchronized
    fun ranked(): List<MenuCommand> = CommandUsage.rank(menu, memory.ledger)

    /** The order Telegram received last (empty = this store never registered). */
    @Synchronized
    fun registeredOrder(): PaletteOrder = memory.registered

    /** One successful registration's flight. */
    @Synchronized
    fun markRegistered(order: PaletteOrder) {
        if (memory.registered == order) return
        memory = memory.copy(registered = order)
        save()
    }

    /** The ledger as it stands (diagnostics and tests). */
    @Synchronized
    fun ledger(): UsageLedger = memory.ledger

    private fun load(): Memory {
        if (!file.exists()) return Memory()
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return@runCatching Memory()
            val counts = (root["counts"] as? JsonObject).orEmpty().mapNotNull { (command, value) ->
                (value as? JsonPrimitive)?.longOrNull?.let { command to it }
            }
            val registered = (root["registered"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            Memory(ledger = UsageLedger(counts.toMap()), registered = PaletteOrder(registered))
        }.getOrElse { error ->
            logger.warn { "palette store unreadable (${error.message}) — starting empty" }
            Memory()
        }
    }

    private fun save() {
        val root = buildJsonObject {
            putJsonObject("counts") {
                memory.ledger.entries().forEach { use -> put(use.command, use.uses) }
            }
            putJsonArray("registered") { memory.registered.names.forEach { add(JsonPrimitive(it)) } }
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(root.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("atomic rename failed: $file")
        } catch (e: IOException) {
            logger.warn { "palette store save failed: ${e.message} (the ranking degrades, never crashes)" }
        }
    }
}
