package dev.lattenero.bot.channel

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The orphan seal's pointer: one durable fact, roundtrip + corrupt-tolerant. */
class LiveOrphanStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun saveReadClearRoundtrip() {
        val store = LiveOrphanStore(File(dir, "live-view-active.json"))
        assertNull(store.readLast(), "empty boot: no orphan")
        store.save(4242, "task-77")
        assertEquals(LiveOrphanStore.Live(4242, "task-77"), store.readLast())
        store.clear()
        assertNull(store.readLast())
    }

    @Test
    fun aCorruptFileIsNoOrphanNotACrash() {
        val f = File(dir, "live-view-active.json")
        f.writeText("{ not json at all")
        assertNull(LiveOrphanStore(f).readLast())
    }

    @Test
    fun anAtomicSaveLeavesNoTmpBehind() {
        val f = File(dir, "live-view-active.json")
        LiveOrphanStore(f).save(1, "t")
        assertTrue(f.exists())
        assertTrue(
            dir.listFiles()!!.none { it.name.endsWith(".tmp") },
            "the tmp+rename dance left no tmp behind",
        )
    }
}
