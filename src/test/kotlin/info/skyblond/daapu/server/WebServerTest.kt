package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.config.TitleConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the HTTP status mapping of the `/api` routes. Only the DB-free paths
 * are exercised: every request below fails validation or the chat lock before
 * any `withTransaction` runs (a chat run or a successful delete would need a
 * live database, which unit tests deliberately avoid). The title route is the
 * exception: it goes through an injected in-memory [ChatStore] and a scripted
 * [FakeHand], so it needs no database either.
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

    private fun user(text: String) =
        ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text(text)))

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
            listOf(
                """{"text":"hi"}""",
                """{"text":"hi","model":"no/such-model"}"""
            ).forEach { body ->
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

    // ---- truncate (`DELETE /api/chats/{id}/messages/{index}`) ----

    @Test
    fun `truncate drops the tail and answers 204`() {
        val store = FakeChatStore()
        store.seed(
            "chat-1",
            chat = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2"))
        )
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = store),
                    PostgresSstmService()
                )
            }
            val response = client.delete("/api/chats/chat-1/messages/2")
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(
                listOf(user("u1"), assistantMessage("a1")),
                store.load("chat-1")!!.content.messages
            )
        }
    }

    @Test
    fun `truncate on a missing chat is 404`() {
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = FakeChatStore()),
                    PostgresSstmService()
                )
            }
            val response = client.delete("/api/chats/nope/messages/0")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `truncate with a bad index is 400`() {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = store),
                    PostgresSstmService()
                )
            }
            // non-numeric, out of bounds, and an index pointing at an assistant
            // message are all rejected before any store write
            listOf(
                "/api/chats/chat-1/messages/abc",
                "/api/chats/chat-1/messages/5",
                "/api/chats/chat-1/messages/1",
            ).forEach { path ->
                val response = client.delete(path)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path: $path")
            }
            assertEquals(2, store.load("chat-1")!!.content.messages.size)
        }
    }

    @Test
    fun `truncate on a chat with an active run is rejected with 409`() {
        val chatService = service()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(chatService, PostgresSstmService()) }
                val response = client.delete("/api/chats/$chatId/messages/0")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            chatService.releaseChatLock(chatId, lock)
        }
    }

    // ---- fork (`POST /api/chats/{id}/fork/{index}`) ----

    @Test
    fun `fork copies the prefix into a new chat and answers 201 with its info`() {
        val store = FakeChatStore()
        store.seed(
            "chat-1",
            chat = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2"))
        )
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = store),
                    PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/chat-1/fork/1")
            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val newId = body["id"]?.jsonPrimitive?.content!!
            assertTrue(newId != "chat-1")
            assertEquals("New chat", body["title"]?.jsonPrimitive?.content)
            // the fork carries the prefix, the source keeps everything
            assertEquals(
                listOf(user("u1"), assistantMessage("a1")),
                store.load(newId)!!.content.messages
            )
            assertEquals(4, store.load("chat-1")!!.content.messages.size)
        }
    }

    @Test
    fun `fork on a missing chat is 404`() {
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = FakeChatStore()),
                    PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/nope/fork/0")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `fork with a bad index is 400`() {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("u1"), assistantMessage("a1")))
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), chatStore = store),
                    PostgresSstmService()
                )
            }
            // non-numeric, out of bounds, and a user-message index are all
            // rejected; no fork chat is created
            listOf(
                "/api/chats/chat-1/fork/abc",
                "/api/chats/chat-1/fork/5",
                "/api/chats/chat-1/fork/0",
            ).forEach { path ->
                val response = client.post(path)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path: $path")
            }
            assertEquals(1, store.listChats().size)
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

    @Test
    fun `generate title persists and returns the generated title`() {
        val store = FakeChatStore()
        store.seed("chat-1", chat = listOf(user("hi"), assistantMessage("hello")))
        val hand = FakeHand(
            runScript = { textRunFlow("Generated title") }
        )
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), hand = hand, chatStore = store),
                    PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/chat-1/title")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("chat-1", body["id"]?.jsonPrimitive?.content)
            assertEquals("Generated title", body["title"]?.jsonPrimitive?.content)
        }
        assertEquals("Generated title", store.title("chat-1"))
        assertEquals(1, hand.requests.size)
    }

    @Test
    fun `generate title on a missing chat is 404`() {
        val hand = FakeHand()
        testApplication {
            application {
                module(
                    ChatRunService(
                        testAppConfig(),
                        hand = hand,
                        chatStore = FakeChatStore()
                    ), PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/nope/title")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        assertTrue(hand.requests.isEmpty(), "a missing chat must not call the LLM")
    }

    @Test
    fun `generate title on an empty chat is a no-op`() {
        val store = FakeChatStore()
        store.seed("chat-1", title = "My custom title")
        val hand = FakeHand()
        testApplication {
            application {
                module(
                    ChatRunService(testAppConfig(), hand = hand, chatStore = store),
                    PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/chat-1/title")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("My custom title", body["title"]?.jsonPrimitive?.content)
        }
        assertTrue(hand.requests.isEmpty(), "an empty chat must not call the LLM")
        assertEquals(
            "My custom title",
            store.title("chat-1"),
            "a custom title must never be clobbered"
        )
    }

    @Test
    fun `generate title with a title model that cannot see the history is 400`() {
        // a text-only title model with image history: a configuration error,
        // surfaced as a 400 with the reason instead of an opaque 500
        val store = FakeChatStore()
        store.seed(
            "chat-1",
            chat = listOf(
                user("hi"),
                ChatMessage(
                    ChatMessageRole.User,
                    listOf(
                        ChatMessagePart.Attachment(
                            kind = AttachmentKind.Image,
                            content = AttachmentContent.Base64("AAAA"),
                            mimeType = "image/png",
                        )
                    ),
                ),
            ),
        )
        val hand = FakeHand()
        testApplication {
            application {
                module(
                    ChatRunService(
                        testAppConfig().copy(title = TitleConfig(model = "bifrost/cerebras/gpt-oss-120b")),
                        hand = hand,
                        chatStore = store,
                    ),
                    PostgresSstmService()
                )
            }
            val response = client.post("/api/chats/chat-1/title")
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("does not support") == true,
                "the 400 must carry the capability reason"
            )
        }
        assertTrue(hand.requests.isEmpty(), "a capability mismatch must not call the LLM")
    }
}
