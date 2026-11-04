package dev.lattenero.bot.transport

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * tg-offset (updates.py): the getUpdates cursor. The write is atomic
 * (tmp + fsync + rename) because a crash mid-write used to leave the
 * offset truncated → ValueError → offset 0 → massive re-processing.
 * The tmp carries the pid: the poller is not the only writer, and with
 * a fixed name the loser did os.replace on a tmp the other one had
 * already moved (20/10 18:10: ENOENT).
 */
object TgOffset {

    /**
     * How the cursor file answered — the CALLER needs the difference:
     * a fresh install ([ABSENT]) and a poisoned file ([CORRUPT]) both
     * read as 0, and 0 means "Telegram, replay up to 24 h of updates".
     * That replay is not a harmless re-read: every old `/nuclear go` or
     * `/deploy go` in the backlog would run again (U9 finding F1). The
     * poller refuses to dispatch a batch whose cursor was never live.
     */
    enum class CursorState { LIVE, ABSENT, CORRUPT }

    /** The cursor with its provenance: absent/corrupt both answer 0. */
    data class CursorReading(val value: Long, val state: CursorState)

    /**
     * [CursorReading] of [path]: LIVE only when the file exists AND parses
     * as a decimal update_id; ABSENT is a first run; anything else is
     * CORRUPT (the truncated-write story).
     */
    fun read(path: File): CursorReading = when {
        !path.exists() -> CursorReading(0L, CursorState.ABSENT)

        else -> try {
            CursorReading(path.readText().trim().toLong(), CursorState.LIVE)
        } catch (_: Exception) {
            CursorReading(0L, CursorState.CORRUPT)
        }
    }

    /** current_offset(): the stored update_id, or 0. */
    fun current(path: File): Long = read(path).value

    /** save_offset(value): pid-suffixed tmp, fsync, atomic replace. */
    fun save(path: File, value: Long) {
        path.parentFile?.mkdirs()
        val tmp = File("${path.path}.tmp.${ProcessHandle.current().pid()}")
        FileOutputStream(tmp).use { fos ->
            fos.write(value.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
