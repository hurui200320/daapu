package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatService
import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.context.ContextInjection
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.config.TitleConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.HandEvent
import info.skyblond.daapu.hand.HandRunRequest
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.hand.errorRunFlow
import info.skyblond.daapu.hand.textRunFlow
import info.skyblond.daapu.memory.eltm.EltmService
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testKoinApp
import info.skyblond.daapu.testutil.testPostgresEltmService
import info.skyblond.daapu.testutil.writerRunFlow
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the HTTP status mapping of the `/api` routes. Most requests below
 * fail validation or the chat lock before any store write; the ones that do
 * write (truncate, fork, title, personas, the ELTM browse routes) run the
 * production stores against the testcontainers database (see `TestDb`), with
 * a scripted [FakeHand] wherever a one-shot would fire.
 */
class WebServerTest : DbTestBase() {

    private val model = "bifrost/cerebras/gpt-oss-120b"

    private val json = Json { explicitNulls = false }

    private fun messageBody(
        text: String = "hi",
        model: String = this.model,
        images: List<String> = emptyList(),
        personaId: Long = DEFAULT_PERSONA_ID,
    ): String = json.encodeToString(
        SendMessageRequest(
            text = text,
            model = model,
            images = images.map { ImagePart(it) },
            personaId = personaId,
        )
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
    fun `missing and unknown personas are rejected with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            listOf(
                """{"text":"hi","model":"bifrost/cerebras/gpt-oss-120b"}""",
                """{"text":"hi","model":"bifrost/cerebras/gpt-oss-120b","personaId":999}"""
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
        val chatService = koinApp.koin.get<ChatService>()
        val chatId = "chat-running"
        val lock = runBlocking { chatService.acquireChatLock(chatId) }
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
            runBlocking { lock.release() }
        }
    }

    @Test
    fun `an exhausted chat-lock pool is rejected with 503`() {
        // a pool of ONE whose only connection is pinned: the next acquire
        // cannot get a pool connection within the budget — a capacity limit
        // (503, any chat id), NOT a per-chat conflict (409). The budget is
        // generous (see the matching ChatServiceLockTest): the holder's
        // FIRST acquire pays the fresh-connection setup out of it, which
        // must not flake against a loaded CI — the exhaustion itself stays
        // deterministic (a pool at max can only queue, never serve)
        val base = testAppConfig()
        val koinApp = testKoinApp(
            base.copy(
                database = base.database.copy(lockPoolSize = 1, lockConnectionTimeout = 5_000)
            )
        )
        val chatService = koinApp.koin.get<ChatService>()
        val holder = runBlocking { chatService.acquireChatLock("chat-holder") }
        try {
            testApplication {
                application { module(koinApp.koin) }
                val response = client.post("/api/chats/chat-other/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(messageBody())
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            }
        } finally {
            runBlocking { holder.release() }
        }
    }

    @Test
    fun `delete on a chat with an active run is rejected with 409`() {
        val koinApp = testKoinApp()
        val chatService = koinApp.koin.get<ChatService>()
        val chatId = "chat-running"
        val lock = runBlocking { chatService.acquireChatLock(chatId) }
        try {
            testApplication {
                application { module(koinApp.koin) }
                val response = client.delete("/api/chats/$chatId")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            runBlocking { lock.release() }
        }
    }

    // ---- truncate (`DELETE /api/chats/{id}/messages/{index}`) ----

    @Test
    fun `truncate drops the tail and answers 204`() {
        val store = PostgresChatStore()
        TestDb.seedChatRow(
            "chat-1",
            messages = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2"))
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
                module(testKoinApp(testAppConfig()).koin)
            }
            val response = client.delete("/api/chats/nope/messages/0")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `truncate with a bad index is 400`() {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
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
        val chatService = koinApp.koin.get<ChatService>()
        val chatId = "chat-running"
        val lock = runBlocking { chatService.acquireChatLock(chatId) }
        try {
            testApplication {
                application { module(koinApp.koin) }
                val response = client.delete("/api/chats/$chatId/messages/0")
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
        } finally {
            runBlocking { lock.release() }
        }
    }

    // ---- fork (`POST /api/chats/{id}/fork/{index}`) ----

    @Test
    fun `fork copies the prefix into a new chat and answers 201 with its info`() {
        val store = PostgresChatStore()
        TestDb.seedChatRow(
            "chat-1",
            messages = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2"))
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
                module(testKoinApp(testAppConfig()).koin)
            }
            val response = client.post("/api/chats/nope/fork/0")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `fork with a bad index is 400`() {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("u1"), assistantMessage("a1")))
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
        val eltm = testPostgresEltmService(FakeHand())
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
        val eltm = testPostgresEltmService(FakeHand())
        runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            eltm.createEntity("Bob", "person")
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 1, 1), "first note")
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 2, 1), "second note")
            eltm.setEntityAttribute(alice.id, "nickname", "Ali")
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
            assertEquals(
                "Ali",
                first["attributes"]?.jsonObject?.get("nickname")?.jsonPrimitive?.content,
                "current-state attributes ride the entity view"
            )
            assertEquals(
                0,
                body[1].jsonObject["attributes"]?.jsonObject?.size,
                "an entity without facts carries an empty attributes object"
            )
        }
    }

    @Test
    fun `eltm entities list paginates`() {
        val eltm = testPostgresEltmService(FakeHand())
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
        val eltm = testPostgresEltmService(FakeHand())
        runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            eltm.createRelationship(alice.id, bob.id, "works with")
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
        val eltm = testPostgresEltmService(FakeHand())
        val (aliceId, relId) = runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val rel = eltm.createRelationship(alice.id, bob.id, "works with")
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 1, 1), "note text")
            eltm.attachNoteToRelationship(rel.id, LocalDate.of(2026, 1, 2), "collaborate")
            alice.id to rel.id
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val notes = client.get("/api/eltm/entities/$aliceId/notes")
            assertEquals(HttpStatusCode.OK, notes.status)
            val notesBody = json.parseToJsonElement(notes.bodyAsText()).jsonArray
            assertEquals(1, notesBody.size)
            assertEquals("note text", notesBody[0].jsonObject["note"]?.jsonPrimitive?.content)

            val rels = client.get("/api/eltm/entities/$aliceId/relationships")
            assertEquals(HttpStatusCode.OK, rels.status)
            val relsBody = json.parseToJsonElement(rels.bodyAsText()).jsonArray
            assertEquals(1, relsBody.size)
            assertEquals(1, relsBody[0].jsonObject["noteCount"]?.jsonPrimitive?.content?.toInt())

            val relNotes = client.get("/api/eltm/relationships/$relId/notes")
            assertEquals(HttpStatusCode.OK, relNotes.status)
            val relNotesBody = json.parseToJsonElement(relNotes.bodyAsText()).jsonArray
            assertEquals(1, relNotesBody.size)
            assertEquals("collaborate", relNotesBody[0].jsonObject["note"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `eltm entity drill-down hides invalidated relationships unless requested`() {
        val eltm = testPostgresEltmService(FakeHand())
        val aliceId = runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val rel = eltm.createRelationship(alice.id, bob.id, "works with")
            eltm.attachNoteToRelationship(rel.id, LocalDate.of(2026, 3, 1), "left", valid = false)
            alice.id
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val hidden = client.get("/api/eltm/entities/$aliceId/relationships")
            assertEquals(HttpStatusCode.OK, hidden.status)
            val hiddenBody = json.parseToJsonElement(hidden.bodyAsText()).jsonArray
            assertEquals(0, hiddenBody.size)

            val shown = client.get("/api/eltm/entities/$aliceId/relationships?includeInvalid=true")
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
        val eltm = testPostgresEltmService(FakeHand())
        runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            val bob = eltm.createEntity("Bob", "person").entity
            val rel = eltm.createRelationship(alice.id, bob.id, "works with")
            eltm.attachNoteToRelationship(rel.id, LocalDate.of(2026, 3, 1), "left", valid = false)
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
        val eltm = testPostgresEltmService(FakeHand())
        val aliceId = runBlocking {
            val alice = eltm.createEntity("Alice", "person").entity
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 1, 1), "jan")
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 2, 1), "feb")
            eltm.attachNoteToEntity(alice.id, LocalDate.of(2026, 3, 1), "mar")
            alice.id
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val response = client.get("/api/eltm/entities/$aliceId/notes?from=2026-02-01&to=2026-02-28")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(1, body.size)
            assertEquals("feb", body[0].jsonObject["note"]?.jsonPrimitive?.content)
        }
    }

    // ---- ELTM import route (`POST /api/eltm/import`, the manual write path) ----

    /**
     * A fake hand dispatching on the one-shot system prompts: the
     * extraction one-shot answers [extraction] (the default echoes one
     * ready-made fact, the way the real extractor normalizes the text),
     * the writer runs [info.skyblond.daapu.testutil.writerRunFlow] against
     * [eltm] (one create_entity + add_entity_note round, executed through
     * the real [info.skyblond.daapu.memory.eltm.EltmToolProvider], then
     * the confirmation). The import endpoint never calls anything else, so
     * any other request fails the test.
     */
    private fun importHand(
        eltm: EltmService,
        extraction: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("likes coffee") },
        writer: suspend (HandRunRequest) -> List<HandEvent> = { writerRunFlow(eltm) },
    ) = FakeHand(
        runScript = { request ->
            when {
                request.systemPrompt?.startsWith("You're extracting") == true -> extraction(request)
                request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                    writer(request)

                else -> error("unexpected run on the import endpoint: ${request.systemPrompt}")
            }
        },
    )

    private fun importBody(text: String, date: String? = null): String =
        buildString {
            append("""{"text":""")
            append(json.encodeToString(text))
            if (date != null) {
                append(""","date":""")
                append(json.encodeToString(date))
            }
            append("}")
        }

    @Test
    fun `eltm import rejects a blank text with 400`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            listOf(
                """{}""",
                """{"text":""}""",
                """{"text":"   "}""",
            ).forEach { body ->
                val response = client.post("/api/eltm/import") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a rejected text must not call the LLM")
    }

    @Test
    fun `eltm import rejects a malformed or future date with 400`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            listOf(
                importBody("User likes coffee", date = "2026/01/01"),
                importBody("User likes coffee", date = "not-a-date"),
                importBody("User likes coffee", date = "2099-01-01"),
            ).forEach { body ->
                val response = client.post("/api/eltm/import") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a rejected batch must not call the LLM")
    }

    @Test
    fun `eltm import extracts the text and writes the facts through the ELTM writer`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // the route's own `LocalDate.now()` runs between the two reads,
            // so a midnight flip between the calls cannot flake the
            // "server's today" assertion below
            val before = LocalDate.now()
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("I like coffee"))
            }
            val after = LocalDate.now()
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("", response.bodyAsText(), "201 Created carries no body")
            assertEquals(2, hand.requests.size, "extraction one-shot + writer run")
            assertTrue(
                hand.requests[0].systemPrompt!!.startsWith("You're extracting memories from a piece of text"),
                "the import extraction uses the text-flavored prompt: ${hand.requests[0].systemPrompt}",
            )
            // the extraction one-shot received the text as ONE user message
            // carrying its reference-date anchor (relative dates resolve
            // against it), followed by the extraction instruction
            val extractionMessages = hand.requests[0].messages
            assertEquals(2, extractionMessages.size, "the synthetic text message + the extraction instruction")
            assertTrue(
                ContextInjection().hasMetaPart(extractionMessages[0]),
                "the synthetic message carries its reference-date anchor",
            )
            assertEquals(
                listOf(ChatMessagePart.Text("I like coffee")),
                extractionMessages[0].parts.drop(1),
                "the anchor is prepended, the text untouched",
            )
            // no explicit date: the writer input carries the server's today
            // (the same default the extraction pipeline writes with)
            val input = hand.requests[1].messages.single().parts
                .filterIsInstance<ChatMessagePart.Text>().single().text
            val inputDate = LocalDate.parse(
                Regex("Current date: (\\d{4}-\\d{2}-\\d{2})").find(input)!!.groupValues[1]
            )
            assertTrue(
                inputDate in before..after,
                "the absent date must default to the server's today (got $inputDate)",
            )
            assertTrue(
                input.contains("likes coffee"),
                "the writer receives the extractor's output, not the raw text: $input",
            )
        }
        // the writer run landed the fact in the ELTM diary
        val notes = runBlocking { TestDb.allEltmNotes().map { it.note } }
        assertTrue(notes.contains("likes coffee"), "the imported fact must reach the ELTM")
    }

    @Test
    fun `eltm import anchors the extraction at the optional date while the writer stamps today`() {
        val eltm = testPostgresEltmService(FakeHand())
        // the scripted writer reads the "current date" off its input the
        // way the real model does, so the recorded note stamps the write
        // day: the reference date only anchors the extraction — the writer
        // always writes with the server's today (the same write day the
        // discard pipeline uses)
        val hand = importHand(
            eltm,
            writer = { request ->
                val input = request.messages.single().parts
                    .filterIsInstance<ChatMessagePart.Text>().single().text
                val date = Regex("Current date: (\\S+)").find(input)!!.groupValues[1]
                writerRunFlow(eltm, date = date)
            },
        )
        val before = LocalDate.now()
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("I like coffee", date = "2026-01-01"))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            // the reference date also anchors the synthetic text message, so
            // the extractor resolves the text's relative dates against it
            // (start of day, rendered in the server's zone)
            val anchor = hand.requests[0].messages[0].parts.first() as ChatMessagePart.Text
            assertTrue(
                anchor.text.contains("2026-01-01T00:00:00"),
                "the synthetic message is anchored at the reference date: ${anchor.text}",
            )
            // the writer's "current date" is the server's today — never the
            // reference date (whose event dates must not run ahead of the
            // write day)
            val writerInput = hand.requests[1].messages.single().parts
                .filterIsInstance<ChatMessagePart.Text>().single().text
            val writeDate = LocalDate.parse(
                Regex("Current date: (\\d{4}-\\d{2}-\\d{2})").find(writerInput)!!.groupValues[1]
            )
            assertTrue(
                !writeDate.isBefore(before) && !writeDate.isAfter(LocalDate.now()),
                "the writer must write with the server's today (got $writeDate, not the reference date)",
            )
        }
        // the scripted writer stamped the note with the write day it read
        // off its input — never the past reference date
        val noteDate = runBlocking { TestDb.allEltmNotes().single().eventDate }
        assertTrue(
            !noteDate.isBefore(before) && !noteDate.isAfter(LocalDate.now()),
            "the note must stamp the write day (the server's today), got $noteDate",
        )
    }

    @Test
    fun `eltm import no-ops a pasted skip sentinel without an LLM call`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // the exact sentence and a casing/punctuation near-miss both hit
            // the tolerant input fast path (MemoryExtractionService
            // .isNothingToRemember, via processUserText) without any LLM
            // call, answered by the same 201 as a real import
            listOf("Nothing worth remember.", "nothing worth remember").forEach { paste ->
                val response = client.post("/api/eltm/import") {
                    contentType(ContentType.Application.Json)
                    setBody(importBody(paste))
                }
                assertEquals(HttpStatusCode.Created, response.status, "paste: $paste")
            }
        }
        assertTrue(hand.requests.isEmpty(), "the sentinel fast path makes no LLM call")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "the sentinel itself must never be recorded")
    }

    @Test
    fun `eltm import no-ops when the extractor finds nothing worth remembering`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(
            eltm,
            extraction = { textRunFlow("Nothing worth remember.") },
            writer = { error("the writer must not run for an empty extraction") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("we just chatted about the weather"))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
        assertEquals(1, hand.requests.size, "only the extraction one-shot happened")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "an empty extraction must not write the ELTM")
    }

    @Test
    fun `eltm import no-ops a near-miss extraction sentinel without the writer`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(
            eltm,
            // a casing-variant near-miss of the sentinel: the post-extraction
            // check (MemoryExtractionService.processUserText) must be
            // tolerant too
            extraction = { textRunFlow("NOTHING WORTH REMEMBER.") },
            writer = { error("the writer must not run for an empty extraction") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("we just chatted about the weather"))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
        assertEquals(1, hand.requests.size, "only the extraction one-shot happened")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "an empty extraction must not write the ELTM")
    }

    @Test
    fun `eltm import surfaces a failed writer run as 502 with the reason`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(
            eltm,
            writer = { errorRunFlow("upstream", "provider exploded") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("I like coffee"))
            }
            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val error = body["error"]?.jsonPrimitive?.content
            assertNotNull(error)
            assertTrue(error.startsWith("ELTM write failed"), "error: $error")
            assertTrue(error.contains("provider exploded"), "error: $error")
        }
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "a failed writer must not record anything")
    }

    @Test
    fun `eltm import surfaces a failed extraction as 502 with the reason`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = importHand(
            eltm,
            extraction = { errorRunFlow("upstream", "provider exploded") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(importBody("I like coffee"))
            }
            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val error = body["error"]?.jsonPrimitive?.content
            assertNotNull(error)
            assertTrue(error.startsWith("Memory extraction failed"), "error: $error")
            assertTrue(error.contains("provider exploded"), "error: $error")
        }
        assertEquals(1, hand.requests.size, "only the extraction one-shot happened")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "a failed extraction must not record anything")
    }

    @Test
    fun `model catalog is served`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/models")
            assertEquals(HttpStatusCode.OK, response.status)
            // the wire shape is hand-mirrored in the frontend's ModelInfo
            // type: pin it here, in the test config's catalog order, with
            // the budgets ALWAYS present (LLM validates both > 0 at boot)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(
                listOf(
                    "bifrost/cerebras/gpt-oss-120b",
                    "bifrost/cerebras/gemma-4-31b",
                    "bifrost/novita/google/gemma-4-31b-it",
                ),
                body.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )
            assertTrue(
                body.all {
                    it.jsonObject["contextLength"]!!.jsonPrimitive.long > 0 &&
                            it.jsonObject["maxOutputTokens"]!!.jsonPrimitive.long > 0
                },
            )
            // the vision flag mirrors each model's image capability
            assertEquals(false, body[0].jsonObject["vision"]!!.jsonPrimitive.boolean)
            assertEquals(true, body[1].jsonObject["vision"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `generate title persists and returns the generated title`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", messages = listOf(user("hi"), assistantMessage("hello")))
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
        assertEquals("Generated title", store.load("chat-1")!!.info.title)
        assertEquals(1, hand.requests.size)
    }

    @Test
    fun `generate title on a missing chat is 404`() {
        val hand = FakeHand()
        testApplication {
            application {
                module(testKoinApp(testAppConfig(), hand = hand).koin)
            }
            val response = client.post("/api/chats/nope/title")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        assertTrue(hand.requests.isEmpty(), "a missing chat must not call the LLM")
    }

    @Test
    fun `generate title on an empty chat is a no-op`() = runBlocking {
        val store = PostgresChatStore()
        TestDb.seedChatRow("chat-1", title = "My custom title")
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
            store.load("chat-1")!!.info.title,
            "a custom title must never be clobbered"
        )
    }

    @Test
    fun `generate title with a title model that cannot see the history is 400`() = runBlocking {
        // a text-only title model with image history: a configuration error,
        // surfaced as a 400 with the reason instead of an opaque 500
        val store = PostgresChatStore()
        TestDb.seedChatRow(
            "chat-1",
            messages = listOf(
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
                assistantMessage("here you go"),
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

    // ---- personas (`/api/personas`) ----

    @Test
    fun `personas list leads with the code default and includes the rows`() {
        runBlocking { TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg")) }
        testApplication {
            application {
                module(testKoinApp().koin)
            }
            val response = client.get("/api/personas")
            assertEquals(HttpStatusCode.OK, response.status)
            val personas = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, personas.size)
            assertEquals(DEFAULT_PERSONA_ID, personas[0].jsonObject["id"]?.jsonPrimitive?.long)
            assertEquals(
                "Default (GSG)",
                personas[0].jsonObject["name"]?.jsonPrimitive?.content,
            )
            assertEquals(1L, personas[1].jsonObject["id"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `creating a persona validates and returns the row`() {
        testApplication {
            application {
                module(testKoinApp().koin)
            }
            val created = client.post("/api/personas") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Writer","systemPrompt":"You are a writer.","allowedNamespaces":["gsg"]}"""
                )
            }
            assertEquals(HttpStatusCode.Created, created.status)
            val body = json.parseToJsonElement(created.bodyAsText()).jsonObject
            val id = assertNotNull(body["id"]?.jsonPrimitive?.long)
            assertEquals("Writer", body["name"]?.jsonPrimitive?.content)
            val personas = json.parseToJsonElement(client.get("/api/personas").bodyAsText()).jsonArray
            assertEquals(2, personas.size, "the code default plus the created row")
            assertEquals(
                listOf("gsg"),
                Json.parseToJsonElement(client.get("/api/personas").bodyAsText())
                    .jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.long == id }
                    .jsonObject["allowedNamespaces"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
        }
    }

    @Test
    fun `creating a persona with an unserved namespace is 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            // the test loop set serves only `gsg` (the MCP provider is empty)
            val response = client.post("/api/personas") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Bad","systemPrompt":"You are bad.","allowedNamespaces":["eltm"]}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("not served") == true,
                "the 400 must carry the validation reason",
            )
        }
    }

    @Test
    fun `updating a persona persists the new text and whitelist`() {
        val writer = runBlocking {
            TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        }
        testApplication {
            application {
                module(testKoinApp().koin)
            }
            val response = client.put("/api/personas/${writer.id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"Poet","systemPrompt":"You are a poet.","allowedNamespaces":[]}"""
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val updated = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("Poet", updated["name"]?.jsonPrimitive?.content)
            assertEquals("You are a poet.", updated["systemPrompt"]?.jsonPrimitive?.content)
            assertEquals(
                emptyList(),
                updated["allowedNamespaces"]?.jsonArray?.map { it.jsonPrimitive.content },
                "[] = all namespaces",
            )
        }
    }

    @Test
    fun `updating or deleting the default persona is 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val put = client.put("/api/personas/$DEFAULT_PERSONA_ID") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"X","systemPrompt":"Y","allowedNamespaces":[]}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, put.status)
            val delete = client.delete("/api/personas/$DEFAULT_PERSONA_ID")
            assertEquals(HttpStatusCode.BadRequest, delete.status)
        }
    }

    @Test
    fun `updating or deleting a non-numeric persona id is 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val put = client.put("/api/personas/nope") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"X","systemPrompt":"Y","allowedNamespaces":[]}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, put.status)
            val delete = client.delete("/api/personas/nope")
            assertEquals(HttpStatusCode.BadRequest, delete.status)
        }
    }

    @Test
    fun `updating or deleting an unknown numeric persona id is 404`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val put = client.put("/api/personas/999") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"name":"X","systemPrompt":"Y","allowedNamespaces":[]}"""
                )
            }
            assertEquals(HttpStatusCode.NotFound, put.status)
            val delete = client.delete("/api/personas/999")
            assertEquals(HttpStatusCode.NotFound, delete.status)
        }
    }

    @Test
    fun `deleting a persona answers 204 and removes the row`() {
        val writer = runBlocking {
            TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
        }
        testApplication {
            application {
                module(testKoinApp().koin)
            }
            val response = client.delete("/api/personas/${writer.id}")
            assertEquals(HttpStatusCode.NoContent, response.status)
            // there is no GET-by-id route: verify the removal through the list
            val remaining = json.parseToJsonElement(client.get("/api/personas").bodyAsText()).jsonArray
            assertEquals(1, remaining.size, "only the code default remains")
            assertEquals(
                DEFAULT_PERSONA_ID,
                remaining.single().jsonObject["id"]?.jsonPrimitive?.long,
            )
        }
    }
}
