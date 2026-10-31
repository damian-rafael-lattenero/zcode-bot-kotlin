package dev.lattenero.bot.channel

import dev.lattenero.bot.transport.TgApi
import dev.lattenero.bot.transport.TgConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ATTACHMENTS IN (30/10): the owner's photo/document rides the poller's
 * ladder as an [InboundUpdate.Attachment] (the largest photo size wins, the
 * document's own name travels, the caption is the prompt), the stranger's
 * photo never does, and the inbound frame carries the wire's attachment
 * shape (localPath, the fork's DaemonChannelInboundAttachment).
 */
class AttachmentInTest {

    private val cfg = TgConfig(token = "tok", chatId = JsonPrimitive(42), ownerUserId = 7L)

    private fun tempDir(): File = Files.createTempDirectory("attin").toFile()

    private fun poller(
        dir: File,
        body: String,
        dispatch: suspend (TgConfig, InboundUpdate) -> Unit = { _, _ -> },
    ): Poller {
        val api = TgApi({ cfg }, engineFactory = factory(body))
        return Poller(api = api, offsetFile = File(dir, "tg-offset"), dispatch = dispatch)
    }

    private fun factory(body: String): HttpClientEngineFactory<MockEngineConfig> =
        object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
                MockEngine(MockEngineConfig().apply(block).apply { addHandler { respond(body) } })
        }

    @Test
    fun theOwnersPhotoBecomesAnAttachmentUpdateWithTheLargestSize() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<InboundUpdate.Attachment>()
        val poller = poller(
            dir,
            """{"result":[{"update_id":20,"message":{"message_id":55,"from":{"id":7},
                "caption":"mirá este screenshot",
                "photo":[
                  {"file_id":"small-1","width":90,"height":90,"file_size":900},
                  {"file_id":"big-1","width":1280,"height":720,"file_size":204800}
                ]}}]}""",
        ) { _, m -> if (m is InboundUpdate.Attachment) seen.add(m) }
        poller.cycle(cfg)
        val att = seen.single()
        assertEquals("big-1", att.fileId) // the LARGEST size is the one worth downloading
        assertEquals("image", att.kind)
        assertEquals("image/jpeg", att.mimeType)
        assertEquals(204800L, att.sizeBytes)
        assertEquals("mirá este screenshot", att.caption)
        assertEquals(55L, att.messageId)
    }

    @Test
    fun theOwnersDocumentKeepsItsOwnNameAndMime() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<InboundUpdate.Attachment>()
        val poller = poller(
            dir,
            """{"result":[{"update_id":21,"message":{"message_id":56,"from":{"id":7},
                "document":{"file_id":"doc-9","file_name":"reporte Q4.pdf",
                            "mime_type":"application/pdf","file_size":99123}}}]}""",
        ) { _, m -> if (m is InboundUpdate.Attachment) seen.add(m) }
        poller.cycle(cfg)
        val att = seen.single()
        assertEquals("doc-9", att.fileId)
        assertEquals("file", att.kind)
        assertEquals("reporte Q4.pdf", att.filename)
        assertEquals("application/pdf", att.mimeType)
        assertEquals(99123L, att.sizeBytes)
        assertEquals("", att.caption)
    }

    @Test
    fun aStrangersPhotoNeverDispatches() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<InboundUpdate>()
        val poller = poller(
            dir,
            """{"result":[{"update_id":22,"message":{"from":{"id":8},"caption":"malicioso",
                "photo":[{"file_id":"evil","width":10,"height":10}]}}]}""",
        ) { _, m -> seen.add(m) }
        poller.cycle(cfg)
        assertEquals(0, seen.size) // fail-closed: the owner gate sits BEFORE the attachment ladder
    }

    @Test
    fun aDegeneratePhotoShapeIsSkippedNotCrashLooped() = runTest {
        val dir = tempDir()
        val seen = mutableListOf<InboundUpdate>()
        val poller = poller(
            dir,
            """{"result":[{"update_id":12,"message":{"photo":"x","from":{"id":7}}}]}""",
        ) { _, m -> seen.add(m) }
        val outcome = poller.cycle(cfg)
        assertEquals(0, seen.size) // the old pin: a malformed photo still never dispatches
        assertEquals(13L, outcome.offset)
    }

    @Test
    fun largestPhotoPrefersPixelsThenBytes() {
        val row = { id: String, w: Long, h: Long, s: Long ->
            kotlinx.serialization.json.buildJsonObject {
                put("file_id", kotlinx.serialization.json.JsonPrimitive(id))
                put("width", kotlinx.serialization.json.JsonPrimitive(w))
                put("height", kotlinx.serialization.json.JsonPrimitive(h))
                put("file_size", kotlinx.serialization.json.JsonPrimitive(s))
            }
        }
        val sizes = kotlinx.serialization.json.buildJsonArray {
            add(row("a", 90, 90, 900_000)) // more bytes, fewer pixels
            add(row("b", 1280, 720, 204_800))
        }
        assertEquals("b", largestPhoto(sizes)?.str("file_id"))
        assertNull(largestPhoto(kotlinx.serialization.json.buildJsonArray { }))
    }

    // ---- the wire -----------------------------------------------------------

    @Test
    fun theInboundFrameCarriesTheAttachmentsShape() {
        val line = encodeClient(
            ClientFrame.Inbound(
                id = "r9",
                text = "mirá esto",
                messageId = "tg-20-att",
                receivedAt = 1_000L,
                attachments = listOf(
                    InboundAttachment(
                        id = "att-20",
                        kind = "image",
                        filename = "photo-20.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 204800L,
                        localPath = "/state/attachments/20-photo-20.jpg",
                    ),
                ),
            ),
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(line) as JsonObject
        assertEquals("inbound", json.str("type"))
        val atts = json["attachments"] as kotlinx.serialization.json.JsonArray
        val att = atts.single() as kotlinx.serialization.json.JsonObject
        assertEquals("image", att.str("kind"))
        assertEquals("/state/attachments/20-photo-20.jpg", att.str("localPath"))
        assertEquals(204800L, att.long("sizeBytes"))
        assertNull(json.obj("taskId")) // the ordinary routing: the bound conversation
    }

    @Test
    fun anInboundWithoutAttachmentsStaysShapeIdentical() {
        val line = encodeClient(ClientFrame.Inbound("r1", "hola", "m1", 5L))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(line) as JsonObject
        assertNotNull(json.str("text"))
        assertNull(json["attachments"]) // additive: the field only exists when it has content
        assertTrue("attachment" !in describe(ClientFrame.Inbound("r1", "hola", "m1", 5L)))
    }
}
