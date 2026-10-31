package dev.lattenero.bot.daemon

import dev.lattenero.bot.channel.FeedVerbosity
import dev.lattenero.bot.channel.VerbositySetting
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * The /verbosity level's FILE-BACKED store — the daemon's edge (policy §7:
 * the publisher reads the setting PER TICK through the seam, it never
 * reads files; THIS class is the only one that does). One line, the
 * canonical level name; the owner's choice SURVIVES the daemon's
 * restarts — a nuclear must not reset his verbosity. The write is the
 * house invariant: tmp + fsync + rename (ZconnShadow's atomicWrite).
 */
class FileVerbositySetting(private val file: File) : VerbositySetting {

    @Volatile
    private var cached: FeedVerbosity? = null

    override fun current(): FeedVerbosity {
        cached?.let { return it }
        val read = try {
            if (file.exists()) FeedVerbosity.of(file.readText().trim()) else null
        } catch (_: IOException) {
            null // unreadable = the default; nothing invented
        } ?: FeedVerbosity.DEFAULT
        cached = read
        return read
    }

    override fun set(level: FeedVerbosity) {
        cached = level // the memory holds even if the disk refuses
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            FileOutputStream(tmp).use { stream ->
                stream.write(level.name.toByteArray())
                stream.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("atomic rename failed: $file")
        } catch (e: IOException) {
            logger.warn(e) { "verbosity persist failed — the level holds in memory: ${e.message}" }
        }
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
