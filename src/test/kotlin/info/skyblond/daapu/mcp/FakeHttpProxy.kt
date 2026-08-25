package info.skyblond.daapu.mcp

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A minimal HTTP forward proxy for tests: accepts a client connection, reads
 * the request line to learn the target, records it, connects upstream, and
 * relays raw bytes both ways — everything else (headers, bodies, keep-alive,
 * long-lived streams) is transparent at the TCP level. Handles the two
 * shapes the Java engine (JDK HttpClient) sends behind an HTTP proxy:
 *
 * - `CONNECT host:port HTTP/1.1` (HTTPS targets) — answers
 *   `200 Connection Established` then tunnels;
 * - `METHOD http://host:port/path HTTP/1.1` (absolute-form, plain-HTTP
 *   targets) — connects upstream and relays.
 */
internal class FakeHttpProxy : Closeable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "fake-http-proxy").apply { isDaemon = true }
    }
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    val port: Int get() = serverSocket.localPort

    /** Every routed request line in order ("CONNECT host:port HTTP/1.1", ...). */
    val requestLines = CopyOnWriteArrayList<String>()

    init {
        executor.execute {
            try {
                while (true) {
                    val client = serverSocket.accept()
                    openSockets += client
                    executor.execute { handle(client) }
                }
            } catch (_: Exception) {
                // the server socket was closed
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { socket ->
                val requestLine = readRequestLine(socket) ?: return
                requestLines += requestLine
                val parts = requestLine.split(' ')
                val method = parts[0]
                val target = parts.getOrElse(1) { "" }
                when {
                    method == "CONNECT" -> {
                        val (host, port) = hostPort(target)
                        try {
                            Socket().use { upstream ->
                                upstream.connect(InetSocketAddress(host, port), 5_000)
                                // the client expects the status line, then tunneled bytes
                                socket.getOutputStream().write(
                                    "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()
                                )
                                socket.getOutputStream().flush()
                                relay(socket, upstream)
                            }
                        } catch (_: Exception) {
                            writeError(socket, 502)
                        }
                    }

                    target.startsWith("http://") -> {
                        val (host, port) = hostPort(target.removePrefix("http://").substringBefore('/'))
                        try {
                            Socket().use { upstream ->
                                upstream.connect(InetSocketAddress(host, port), 5_000)
                                // re-emit the consumed request line, then let the
                                // client's remaining headers + body flow through
                                upstream.getOutputStream().write("$requestLine\r\n".toByteArray())
                                upstream.getOutputStream().flush()
                                relay(socket, upstream)
                            }
                        } catch (_: Exception) {
                            writeError(socket, 502)
                        }
                    }

                    else -> writeError(socket, 400)
                }
            }
        } finally {
            openSockets -= client
        }
    }

    private fun hostPort(value: String): Pair<String, Int> {
        val (host, port) = value.split(':').let { it[0] to it.getOrElse(1) { "80" } }
        return host to port.toInt()
    }

    private fun writeError(socket: Socket, status: Int) {
        runCatching {
            socket.getOutputStream().write("HTTP/1.1 $status Error\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
        }
    }

    /** Blocks until one direction ends, pumping bytes in both directions. */
    private fun relay(client: Socket, upstream: Socket) {
        val threads = listOf(
            Thread { pump(client.getInputStream(), upstream.getOutputStream()) },
            Thread { pump(upstream.getInputStream(), client.getOutputStream()) },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: Exception) {
            // the peer closed; the socket `use` blocks clean up
        }
    }

    /** Reads exactly the request line (up to CRLF); leaves the headers in the stream. */
    private fun readRequestLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\r'.code) continue
            if (b == '\n'.code) return line.toString()
            line.append(b.toChar())
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
        executor.shutdownNow()
    }
}
