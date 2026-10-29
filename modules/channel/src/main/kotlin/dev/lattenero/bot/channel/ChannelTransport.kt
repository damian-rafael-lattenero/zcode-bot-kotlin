package dev.lattenero.bot.channel

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.SocketChannel

private val logger = KotlinLogging.logger {}

/**
 * The socket EFFECT behind the channel, as one injected seam (policy §1:
 * the protocol is pure values; THIS is the only impure edge). Tests run
 * the whole client against an in-memory fake; production runs
 * [UnixSocketTransport].
 *
 * Contract:
 * - [connect] is false when the socket is absent/dead — the fork's host
 *   is not deployed (B3b's degraded mode, never a crash);
 * - [readLine] suspends until a full JSONL line arrives, returns null on
 *   the peer's orderly close, and throws [IOException] on transport
 *   failure — the client's Kap retry owns the difference;
 * - [shutdown] kills the connection from ANY thread (the ping keepalive's
 *   timeout path) and unblocks a suspended [readLine].
 */
interface ChannelTransport {
    suspend fun connect(): Boolean
    suspend fun writeLine(line: String)
    suspend fun readLine(): String?
    fun shutdown()
}

/**
 * The production transport: the JDK's own AF_UNIX (`SocketChannel` +
 * `UnixDomainSocketAddress`, JVM 16+). No third-party socket library —
 * junixsocket would add a dependency to buy exactly this, and
 * ktor-network's unix sockets would drag the engine's lifecycle into a
 * blocking line protocol; the JDK channel in blocking mode under
 * `Dispatchers.IO` IS the injected-effect shape the policy asks for.
 * Framing: UTF-8, `\n`, one line ≤ [ChannelSpec.MAX_LINE_BYTES].
 */
class UnixSocketTransport(private val socketPath: () -> File) : ChannelTransport {

    @Volatile
    private var channel: SocketChannel? = null
    private val readBuf: ByteBuffer = ByteBuffer.allocateDirect(READ_CHUNK)
    private var carry = ByteArray(0)

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val path = socketPath()
        if (!path.exists()) return@withContext false
        runCatching {
            val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
            ch.connect(UnixDomainSocketAddress.of(path.absolutePath))
            ch.configureBlocking(true)
            channel = ch
            carry = ByteArray(0)
            true
        }.getOrElse {
            logger.debug { "channel connect failed (${it::class.simpleName}) — degraded" }
            false
        }
    }

    override suspend fun writeLine(line: String) {
        val ch = channel ?: throw IOException("channel not connected")
        val payload = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        withContext(Dispatchers.IO) {
            ch.write(payload) // blocking mode: the call returns when the buffer is drained
        }
    }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        val ch = channel ?: throw IOException("channel not connected")
        readNext(ch)
    }

    /** The line loop as tail recursion — U0 no-manual-concurrency rule, structural. */
    @Suppress("ThrowsCount") // three IOException sites, one meaning: the transport is down
    private tailrec suspend fun readNext(ch: SocketChannel): String? {
        val nl = carry.indexOf('\n'.code.toByte())
        if (nl >= 0) {
            val line = carry.copyOf(nl).toString(Charsets.UTF_8)
            carry = carry.copyOfRange(nl + 1, carry.size)
            if (line.length > ChannelSpec.MAX_LINE_BYTES) {
                throw IOException("channel line over the ${ChannelSpec.MAX_LINE_BYTES}B budget")
            }
            return line
        }
        if (carry.size > ChannelSpec.MAX_LINE_BYTES) {
            throw IOException("channel line over the ${ChannelSpec.MAX_LINE_BYTES}B budget")
        }
        readBuf.clear()
        val n = try {
            ch.read(readBuf)
        } catch (e: AsynchronousCloseException) {
            throw IOException("channel closed while reading (ping shutdown or stop)", e)
        }
        return when {
            n < 0 -> null // orderly EOF: the host closed
            n == 0 -> readNext(ch)
            else -> {
                readBuf.flip()
                val chunk = ByteArray(n)
                readBuf.get(chunk)
                carry += chunk
                readNext(ch)
            }
        }
    }

    override fun shutdown() {
        runCatching { channel?.close() } // unblocks a blocked read with AsynchronousCloseException
    }

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
