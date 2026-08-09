package info.skyblond.daapu.server

import info.skyblond.daapu.AppConfig
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

    private fun service() = ChatRunService(
        AppConfig(
            databaseUrl = "jdbc:postgresql://localhost:5432/postgres",
            databaseUser = "postgres",
            databasePassword = "postgres",
            llmApiKey = "test",
            llmBaseUrl = "http://localhost:9",
            httpPort = 8080,
        )
    )

    private val model = "cerebras/gpt-oss-120b"

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
            application { module(service(), SstmService()) }
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
            application { module(service(), SstmService()) }
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
            application { module(service(), SstmService()) }
            val response = client.post("/api/chats/chat-1/messages") {
                contentType(ContentType.Application.Json)
                setBody(messageBody(images = listOf("http://example.com/image.png")))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `wrong content type is rejected with 415`() {
        testApplication {
            application { module(service(), SstmService()) }
            val response = client.post("/api/chats/chat-1/messages") {
                contentType(ContentType.Text.Plain)
                setBody("not json")
            }
            // ktor's own default transformation checker answers 415 (without a
            // body) for an unparseable request Content-Type, before StatusPages
            // is involved — pin the status, not the body
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        }
    }

    @Test
    fun `message on a chat with an active run is rejected with 409`() {
        val chatService = service()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(chatService, SstmService()) }
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
                application { module(chatService, SstmService()) }
                val response = client.delete("/api/chats/$chatId")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            chatService.releaseChatLock(chatId, lock)
        }
    }

    @Test
    fun `empty memory content is rejected with 400`() {
        testApplication {
            application { module(service(), SstmService()) }
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
            application { module(service(), SstmService()) }
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
            application { module(service(), SstmService()) }
            val response = client.get("/api/models")
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }
}
