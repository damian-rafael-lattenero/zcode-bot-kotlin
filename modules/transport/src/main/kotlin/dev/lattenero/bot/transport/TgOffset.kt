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

    /** current_offset(): the stored update_id, or 0. */
    fun current(path: File): Long = try {
        path.readText().trim().toLong()
    } catch (_: Exception) {
        0L
    }

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
