package info.skyblond.daapu.agent.lc4j

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What the mock SSE server answers for one connection: an HTTP status (a
 * non-2xx exercises the retry policy's HTTP-error path) and the canned SSE
 * data lines.
 */
internal data class MockSseResponse(val status: Int = 200, val lines: List<String>)

/**
 * Minimal SSE server on a random localhost port: captures the full request
 * (headers + body), then answers with the canned event lines. [respond] is
 * called per connection with the 1-based attempt ordinal, so a test can serve
 * different responses across retries (e.g. 500 first, success second).
 */
internal class MockSseServer(private val respond: (attempt: Int) -> MockSseResponse) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    private val capturedRequests = CopyOnWriteArrayList<String>()
    private val attempts = AtomicInteger(0)
    val port: Int get() = server.localPort
    val count: Int get() = attempts.get()

    init {
        thread(isDaemon = true, name = "mock-sse") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    try {
                        MockConnection(socket, capturedRequests).use { conn ->
                            val attempt = attempts.incrementAndGet()
                            conn.respond(respond(attempt))
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun lastRequest(): String? = capturedRequests.lastOrNull()

    fun close() {
        server.close()
    }

    class MockConnection(
        private val socket: Socket,
        private val captured: MutableList<String>,
    ) : AutoCloseable {
        private val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        private val output = socket.getOutputStream()

        init {
            var contentLength = 0
            val lines = mutableListOf<String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Content-Length:", true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                lines += line
            }
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                buf.concatToString()
            } else ""
            captured += lines.joinToString("\n") + "\n\n" + body
        }

        fun respond(response: MockSseResponse) {
            val reason = when (response.status) {
                200 -> "OK"
                500 -> "Internal Server Error"
                429 -> "Too Many Requests"
                else -> "Error"
            }
            val payload = response.lines.joinToString("\n\n") + "\n\n"
            val header = "HTTP/1.1 ${response.status} $reason\r\n" +
                "Content-Type: text/event-stream\r\nConnection: close\r\n" +
                "Content-Length: ${payload.toByteArray().size}\r\n\r\n"
            output.write((header + payload).toByteArray())
            output.flush()
        }

        override fun close() {
            socket.close()
        }
    }
}

/** One SSE data line. */
internal fun sseEvent(json: String) = "data: $json"

/** The terminal `[DONE]` line of an OpenAI-style stream. */
internal const val SSE_DONE = "data: [DONE]"

/**
 * One `chat.completion.chunk` SSE payload. [delta] is the raw JSON object
 * body of the `delta` field (e.g. `{"content":"hi"}`); [finishReason] and
 * [usage] are optional top-level fields.
 */
internal fun sseChunk(
    delta: String = "{}",
    finishReason: String? = null,
    usage: String? = null,
): String {
    val usagePart = usage?.let { ",\"usage\":$it" } ?: ""
    return """{"id":"chatcmpl-test","object":"chat.completion.chunk","created":1,"model":"mock",""" +
        """"choices":[{"index":0,"delta":$delta,"finish_reason":${if (finishReason == null) "null" else "\"$finishReason\""}}]$usagePart}"""
}
