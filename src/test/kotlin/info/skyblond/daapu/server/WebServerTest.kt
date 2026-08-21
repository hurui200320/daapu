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
import info.skyblond.daapu.testutil.FakeEltmService
import info.skyblond.daapu.testutil.testKoinApp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
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

    private val model = "bifrost/cerebras/gpt-oss-120b"

    private val json = Json { explicitNulls = false }

    private fun messageBody(
        text: String = "hi",
        model: String = this.model,
        images: List<String> = emptyList(),
    ): String = json.encodeToString(
        SendMessageRequest(text = text, model = model, images = images.map { ImagePart(it) })
    )

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
    )

    @Test
    fun `blank message is rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
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
            application { module(testKoinApp().koin) }
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
            application { module(testKoinApp().koin) }
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
            application { module(testKoinApp().koin) }
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
        val koinApp = testKoinApp()
        val chatService = koinApp.koin.get<ChatRunService>()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(koinApp.koin) }
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
        val koinApp = testKoinApp()
        val chatService = koinApp.koin.get<ChatRunService>()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(koinApp.koin) }
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
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
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
                module(testKoinApp(testAppConfig(), chatStore = FakeChatStore()).koin)
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
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
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
        val koinApp = testKoinApp()
        val chatService = koinApp.koin.get<ChatRunService>()
        val chatId = "chat-running"
        val lock = chatService.acquireChatLock(chatId)
        try {
            testApplication {
                application { module(koinApp.koin) }
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
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
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
                module(testKoinApp(testAppConfig(), chatStore = FakeChatStore()).koin)
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
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
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
            application { module(testKoinApp().koin) }
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
            application { module(testKoinApp().koin) }
            val response = client.post("/api/sstm") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"   "}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `non-numeric memory id is rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.put("/api/sstm/abc") {
                contentType(ContentType.Application.Json)
                setBody("""{"content":"memory"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---- ELTM browse routes (`/api/eltm`, read-only) ----

    @Test
    fun `eltm non-numeric ids are rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            listOf(
                "/api/eltm/entities/abc",
                "/api/eltm/entities/abc/notes",
                "/api/eltm/entities/abc/relationships",
                "/api/eltm/relationships/xyz",
                "/api/eltm/relationships/xyz/notes",
            ).forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path: $path")
            }
        }
    }

    @Test
    fun `eltm bad list params are rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            listOf(
                "/api/eltm/entities?limit=0",
                "/api/eltm/entities?limit=-1",
                "/api/eltm/entities?limit=abc",
                "/api/eltm/entities?limit=501",
                "/api/eltm/entities?offset=-1",
                "/api/eltm/entities?offset=abc",
                "/api/eltm/relationships?limit=0",
                "/api/eltm/relationships?limit=501",
                "/api/eltm/entities/1/notes?limit=501",
            ).forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path: $path")
            }
        }
    }

    @Test
    fun `eltm bad note filters are rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            listOf(
                "/api/eltm/entities/1/notes?from=not-a-date",
                "/api/eltm/entities/1/notes?to=2025/01/01",
                "/api/eltm/entities/1/notes?from=2026-01-01&to=2025-01-01",
                "/api/eltm/relationships/1/notes?from=not-a-date",
                "/api/eltm/relationships/1/notes?from=2026-01-01&to=2025-01-01",
            ).forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.BadRequest, response.status, "path: $path")
            }
        }
    }

    @Test
    fun `eltm bad includeInvalid is rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/eltm/entities/1/relationships?includeInvalid=maybe")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `eltm missing subjects are 404`() {
        val eltm = FakeEltmService()
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            listOf(
                "/api/eltm/entities/1",
                "/api/eltm/entities/1/notes",
                "/api/eltm/entities/1/relationships",
                "/api/eltm/relationships/1",
                "/api/eltm/relationships/1/notes",
            ).forEach { path ->
                val response = client.get(path)
                assertEquals(HttpStatusCode.NotFound, response.status, "path: $path")
            }
        }
    }

    @Test
    fun `eltm entities list returns seeded entities with counts and latest notes`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 1, 1), "first note")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 2, 1), "second note")
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/entities")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, body.size)
            val first = body[0].jsonObject
            assertEquals("alice", first["entity"]?.jsonObject?.get("canonicalName")?.jsonPrimitive?.content)
            assertEquals("person", first["entity"]?.jsonObject?.get("category")?.jsonPrimitive?.content)
            assertEquals(2, first["noteCount"]?.jsonPrimitive?.content?.toInt())
            assertEquals(0, first["relationshipCount"]?.jsonPrimitive?.content?.toInt())
            assertEquals(
                "second note",
                first["latestNote"]?.jsonObject?.get("note")?.jsonPrimitive?.content
            )
            assertEquals(
                "2026-02-01",
                first["latestNote"]?.jsonObject?.get("eventDate")?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `eltm entities list paginates`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.createEntity("Carol", "person")
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/entities?limit=1&offset=1")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, body.size)
            assertEquals("bob", body[0].jsonObject["entity"]?.jsonObject?.get("canonicalName")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `eltm relationships list returns seeded relationships`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.createRelationship(1, 2, "works with")
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/relationships")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, body.size)
            val rel = body[0].jsonObject
            assertEquals("alice", rel["srcName"]?.jsonPrimitive?.content)
            assertEquals("bob", rel["dstName"]?.jsonPrimitive?.content)
            assertEquals("works_with", rel["relationship"]?.jsonObject?.get("verb")?.jsonPrimitive?.content)
            assertEquals("true", rel["relationship"]?.jsonObject?.get("valid")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `eltm entity drill-down serves relationships and notes`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.createRelationship(1, 2, "works with")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 1, 1), "note text")
            eltm.attachNoteToRelationship(1, LocalDate.of(2026, 1, 2), "collaborate")
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val notes = client.get("/api/eltm/entities/1/notes")
            assertEquals(HttpStatusCode.OK, notes.status)
            val notesBody = json.parseToJsonElement(notes.bodyAsText()).jsonArray
            assertEquals(1, notesBody.size)
            assertEquals("note text", notesBody[0].jsonObject["note"]?.jsonPrimitive?.content)

            val rels = client.get("/api/eltm/entities/1/relationships")
            assertEquals(HttpStatusCode.OK, rels.status)
            val relsBody = json.parseToJsonElement(rels.bodyAsText()).jsonArray
            assertEquals(1, relsBody.size)
            assertEquals(1, relsBody[0].jsonObject["noteCount"]?.jsonPrimitive?.content?.toInt())

            val relNotes = client.get("/api/eltm/relationships/1/notes")
            assertEquals(HttpStatusCode.OK, relNotes.status)
            val relNotesBody = json.parseToJsonElement(relNotes.bodyAsText()).jsonArray
            assertEquals(1, relNotesBody.size)
            assertEquals("collaborate", relNotesBody[0].jsonObject["note"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `eltm entity drill-down hides invalidated relationships unless requested`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.createRelationship(1, 2, "works with")
            eltm.attachNoteToRelationship(1, LocalDate.of(2026, 3, 1), "left", valid = false)
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val hidden = client.get("/api/eltm/entities/1/relationships")
            assertEquals(HttpStatusCode.OK, hidden.status)
            val hiddenBody = json.parseToJsonElement(hidden.bodyAsText()).jsonArray
            assertEquals(0, hiddenBody.size)

            val shown = client.get("/api/eltm/entities/1/relationships?includeInvalid=true")
            assertEquals(HttpStatusCode.OK, shown.status)
            val shownBody = json.parseToJsonElement(shown.bodyAsText()).jsonArray
            assertEquals(1, shownBody.size)
            assertEquals(
                "false",
                shownBody[0].jsonObject["relationship"]?.jsonObject?.get("valid")?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `eltm relationships list includes invalidated relationships`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.createEntity("Bob", "person")
            eltm.createRelationship(1, 2, "works with")
            eltm.attachNoteToRelationship(1, LocalDate.of(2026, 3, 1), "left", valid = false)
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/relationships")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, body.size)
            assertEquals(
                "false",
                body[0].jsonObject["relationship"]?.jsonObject?.get("valid")?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `eltm notes are filtered by date range`() {
        val eltm = FakeEltmService()
        runBlocking {
            eltm.createEntity("Alice", "person")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 1, 1), "jan")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 2, 1), "feb")
            eltm.attachNoteToEntity(1, LocalDate.of(2026, 3, 1), "mar")
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/entities/1/notes?from=2026-02-01&to=2026-02-28")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, body.size)
            assertEquals("feb", body[0].jsonObject["note"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `model catalog is served`() {
        testApplication {
            application { module(testKoinApp().koin) }
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
                module(testKoinApp(testAppConfig(), hand = hand, chatStore = store).koin)
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
                module(testKoinApp(testAppConfig(), hand = hand, chatStore = FakeChatStore()).koin)
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
                module(testKoinApp(testAppConfig(), hand = hand, chatStore = store).koin)
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
                    createdAt = Instant.parse("2026-08-17T09:00:00Z"),
                ),
            ),
        )
        val hand = FakeHand()
        testApplication {
            application {
                module(
                    testKoinApp(
                        testAppConfig().copy(title = TitleConfig(model = "bifrost/cerebras/gpt-oss-120b")),
                        hand = hand,
                        chatStore = store,
                    ).koin
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
