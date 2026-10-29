package dev.lattenero.bot.recorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * The I/O shell around [SnapshotEngine]: scans the state directory
 * (recursively; the mtime IS data — it is the house's shared clock), hashes
 * every file, appends the trace lines to the UTC day file, and keeps the
 * previous facts in recorder-state.json written with the house atomic
 * pattern (tmp + fsync + rename): a crash mid-write never truncates — the
 * next pass just re-records everything, and the trace tolerates the
 * duplicate by sha256.
 */
class Recorder(
    private val stateDir: File,
    private val outDir: File,
    private val capBytes: Long = DEFAULT_CAP_BYTES,
    private val clock: () -> Instant = Instant::now,
) {
    @Serializable
    private data class PrevFile(val size: Long, val mtimeMs: Long, val sha256: String)

    @Serializable
    private data class PrevState(val files: Map<String, PrevFile> = emptyMap())

    private val prevState = File(outDir, "recorder-state.json")
    private val dayFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC)

    /** Runs one pass; returns the number of trace lines appended. */
    fun runPass(): Int {
        val previous = readPrevious()
        val current = scan()
        val now = clock()
        val lines = SnapshotEngine.pass(
            DateTimeFormatter.ISO_INSTANT.format(now),
            previous,
            current,
        )
        outDir.mkdirs()
        File(outDir, "replay-" + dayFormat.format(now) + ".jsonl").let { day ->
            day.appendText(lines.joinToString("") { it.toJsonLine() })
        }
        writePrevious(current)
        return lines.size
    }

    private fun scan(): Map<String, FileSample> {
        val root = stateDir.toPath()
        if (!Files.isDirectory(root)) {
            throw IllegalStateException("state dir does not exist: $stateDir")
        }
        val result = LinkedHashMap<String, FileSample>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                .sorted()
                .forEach { path ->
                    val name = root.relativize(path).toString()
                    result[name] = sample(path, name)
                }
        }
        return result
    }

    private fun sample(path: Path, name: String): FileSample {
        val bytes = Files.readAllBytes(path)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha = digest.joinToString("") { "%02x".format(it) }
        val fact = FileFact(
            name = name,
            size = bytes.size.toLong(),
            mtimeMs = Files.getLastModifiedTime(path).toMillis(),
            sha256 = sha,
        )
        val overCap = bytes.size > capBytes
        if (overCap) return FileSample(fact, null, null, overCap = true)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        return FileSample(
            fact = fact,
            text = text,
            b64 = if (text == null) Base64.getEncoder().encodeToString(bytes) else null,
            overCap = false,
        )
    }

    private fun readPrevious(): Map<String, FileFact> {
        val text = runCatching { prevState.readText() }.getOrNull() ?: return emptyMap()
        val state = runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString<PrevState>(text)
        }.getOrNull() ?: return emptyMap()
        return state.files.mapValues { (_, f) -> FileFact("", f.size, f.mtimeMs, f.sha256) }
    }

    private fun writePrevious(current: Map<String, FileSample>) {
        val state = PrevState(
            current.mapValues { (_, s) -> PrevFile(s.fact.size, s.fact.mtimeMs, s.fact.sha256) },
        )
        val tmp = File(outDir, "recorder-state.json.tmp")
        tmp.outputStream().use { out ->
            out.write(Json.encodeToString(state).toByteArray(StandardCharsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), prevState.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** 512 KB: keeps every real state file inline (the largest cot cache is ~120 KB; agent files reach ~450 KB). */
        const val DEFAULT_CAP_BYTES: Long = 512L * 1024
    }
}
