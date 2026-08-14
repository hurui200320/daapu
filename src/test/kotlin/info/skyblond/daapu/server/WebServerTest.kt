package info.skyblond.daapu.server

import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the HTTP status mapping of the `/api` routes. Only the DB-free paths
 * are exercised: every request below fails validation or the chat lock before
 * any `withTransaction` runs (a chat run or a successful delete would need a
 * live database, which unit tests deliberately avoid).
 */
class WebServerTest {

    private fun service() = ChatRunService(testAppConfig())

    private val model = "bifrost/cerebras/gpt-oss-120b"

    private val json = Json { explicitNulls = false }

    private fun messageBody(
        text: String = "hi",
        model: String = this.model,
        images: List<String> = emptyList(),
    ): String = json.encodeToString(
        SendMessageRequest(text = text, model = model, images = images.map { ImagePart(it) })
    )

    @Test
    fun `blank message is rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.post("/api/chats/chat-1/messages") {
                contentType(ContentType.Application.Json)
                setBody(messageBody(text = "   "))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `missing and unknown models are rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            listOf("""{"text":"hi"}""", """{"text":"hi","model":"no/such-model"}""").forEach { body ->
                val response = client.post("/api/chats/chat-1/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
    }

    @Test
    fun `malformed image data url is rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.post("/api/chats/chat-1/messages") {
                contentType(ContentType.Application.Json)
                setBody(messageBody(images = listOf("http://example.com/image.png")))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `wrong content type is rejected`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.post("/api/chats/chat-1/messages") {
                contentType(ContentType.Text.Plain)
                setBody("not json")
            }
            // ktor's transformation check surfaces an unparseable request
            // Content-Type as a ContentTransformationException, which
            // StatusPages maps to 400 before any route handler runs —
            // pin the rejection, not the exact status
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `message on a chat with an active run is rejected with 409`() {
        val chatService = service()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(chatService, PostgresSstmService()) }
                val response = client.post("/api/chats/$chatId/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(messageBody())
                }
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            chatService.releaseChatLock(chatId, lock)
        }
    }

    @Test
    fun `delete on a chat with an active run is rejected with 409`() {
        val chatService = service()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(chatService, PostgresSstmService()) }
                val response = client.delete("/api/chats/$chatId")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            chatService.releaseChatLock(chatId, lock)
        }
    }

    @Test
    fun `blank or missing chat title is rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            listOf("""{"title":"   "}""", """{}""").forEach { body ->
                val response = client.put("/api/chats/chat-1") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
    }

    @Test
    fun `empty memory content is rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.post("/api/memories") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `non-numeric memory id is rejected with 400`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.put("/api/memories/abc") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"memory"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `model catalog is served`() {
        testApplication {
            application { module(service(), PostgresSstmService()) }
            val response = client.get("/api/models")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
