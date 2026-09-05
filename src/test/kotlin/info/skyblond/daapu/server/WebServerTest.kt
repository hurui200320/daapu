package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatCodec
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
import info.skyblond.daapu.hand.EmbeddingException
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `HEAD answers on GET routes - the AutoHeadResponse install`() {
        testApplication {
            application { module(testKoinApp().koin) }
            // the packaged web UI (the stub `frontend` test package, see
            // WebUiServingTest) and /api GET routes answer HEAD — without the
            // module's AutoHeadResponse install ktor 404s HEAD, so a
            // HEAD-based probe would report a healthy UI as down
            assertEquals(HttpStatusCode.OK, client.head("/").status)
            assertEquals(HttpStatusCode.OK, client.head("/api/models").status)
            // AutoHeadResponse mirrors GET routes only: POST-only paths stay
            // HEAD-less (404)
            assertEquals(HttpStatusCode.NotFound, client.head("/api/chats/chat-1/messages").status)
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
            assertEquals(1, store.listChats(null).chats.size)
        }
    }

    // ---- list (`GET /api/chats`, keyset pagination) ----

    @Test
    fun `list chats pages by cursor and rejects a malformed cursor`() {
        // ids in the REAL `$millis-$random` shape (see newChatId in
        // db/ChatIds.kt): the cursor must match it, so the paging assertion
        // cannot use an arbitrary string like "chat-b"
        val newest = "1700000000000-1"
        val oldest = "1600000000000-2"
        TestDb.seedChatRow(newest)
        TestDb.seedChatRow(oldest)
        testApplication {
            application { module(testKoinApp().koin) }
            // the first page answers the envelope; the list fits one page,
            // so no nextCursor (a null cursor is omitted on the wire)
            val first = Json.parseToJsonElement(client.get("/api/chats").bodyAsText()).jsonObject
            assertEquals(
                listOf(newest, oldest),
                first["chats"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )
            assertTrue("nextCursor" !in first, "an exhausted list must not carry a nextCursor")

            // a cursor pages from its POSITION in the newest-first id order
            val second = Json.parseToJsonElement(
                client.get("/api/chats?cursor=$newest").bodyAsText()
            ).jsonObject
            assertEquals(
                listOf(oldest),
                second["chats"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
            )

            // a well-shaped cursor below every id answers an EMPTY page
            // without a nextCursor — the shape a full walk relies on to
            // terminate (see listChats in frontend/src/lib/api.ts)
            val empty = Json.parseToJsonElement(
                client.get("/api/chats?cursor=1-1").bodyAsText()
            ).jsonObject
            assertTrue(empty["chats"]!!.jsonArray.isEmpty(), "a cursor below every id must page to nothing")
            assertTrue("nextCursor" !in empty, "an exhausted page must not carry a nextCursor")

            // a cursor is a chat id (`$millis-$random`, see newChatId in
            // db/ChatIds.kt): anything else fails fast instead of paging
            // from a bogus position
            listOf("garbage", "12345-abc", "abc-123", "12-34-56", "").forEach { cursor ->
                val response = client.get("/api/chats?cursor=$cursor")
                assertEquals(HttpStatusCode.BadRequest, response.status, "cursor: '$cursor'")
            }
        }
    }

    // ---- export/import (`GET /api/chats/{id}/export`, `POST /api/chats/import`) ----

    @Test
    fun `export answers the title plus the neutral-format history as an attachment`() {
        val store = PostgresChatStore()
        // a history WITH a tool round: the tool_result's `isError: false` is
        // an explicit default of the stored format (ChatCodec writes it,
        // `encodeDefaults = true`) — the one field ktor's ContentNegotiation
        // Json (`encodeDefaults = false`) would drop, so this history pins
        // the export's BYTE fidelity, not just its shape
        val history = listOf(
            user("u1"),
            assistantMessage(
                parts = listOf(
                    ChatMessagePart.ToolCall("call_1", "eltm__search", buildJsonObject { }),
                ),
                finishReason = "tool_calls",
            ),
            ChatMessage(
                ChatMessageRole.ToolResult,
                listOf(
                    ChatMessagePart.ToolResult(
                        id = "call_1",
                        tool = "eltm__search",
                        parts = listOf(ChatMessagePart.Text("ok")),
                    )
                ),
            ),
            assistantMessage("a1"),
        )
        TestDb.seedChatRow(
            "chat-1",
            title = "My chat",
            messages = history,
        )
        testApplication {
            application {
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
            }
            val response = client.get("/api/chats/chat-1/export")
            assertEquals(HttpStatusCode.OK, response.status)
            // the FILE is named by the chat id (filename-safe); the title
            // travels only in the payload, the id never does (ktor quotes
            // the filename only when the value needs it — digits+dash don't)
            assertEquals(
                "attachment; filename=chat-1.json",
                response.headers[HttpHeaders.ContentDisposition],
            )
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("My chat", body["title"]?.jsonPrimitive?.content)
            assertNull(body["id"])
            // the messages must BYTE-match the stored neutral format (the
            // same bytes `GET /chat` serves via ChatCodec): the route builds
            // the body by hand through the codec instead of letting ktor's
            // ContentNegotiation re-serialize with `encodeDefaults = false`
            // (see the export route for why)
            assertEquals(ChatCodec.encodeChat(history), body["messages"]!!.toString())
        }
    }

    @Test
    fun `export on a missing chat is 404`() {
        testApplication {
            application { module(testKoinApp().koin) }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/chats/nope/export").status)
        }
    }

    @Test
    fun `import creates a chat from an exported payload and answers 201 with its info`() {
        val store = PostgresChatStore()
        testApplication {
            application {
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
            }
            val payload = ChatExportPayload(
                title = "Imported chat",
                messages = listOf(user("u1"), assistantMessage("a1")),
            )
            val response = client.post("/api/chats/import") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(payload))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val newId = body["id"]?.jsonPrimitive?.content!!
            assertEquals("Imported chat", body["title"]?.jsonPrimitive?.content)
            assertEquals(
                listOf(user("u1"), assistantMessage("a1")),
                store.load(newId)!!.content.messages,
            )
            // fork-like fresh state: empty ELTM fingerprint, default persona record
            assertEquals("", store.load(newId)!!.content.eltmVersion)
            assertEquals(DEFAULT_PERSONA_ID, body["personaId"]?.jsonPrimitive?.long)
        }
    }

    @Test
    fun `import with an invalid payload is 400 and creates nothing`() {
        val store = PostgresChatStore()
        testApplication {
            application {
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
            }
            // a blank title fails the rename rule; a history ending with a
            // user message fails the stored-chat validation; a malformed
            // body fails the JSON decode — all client errors, no row written
            val badEnding = ChatExportPayload(
                title = "t",
                messages = listOf(
                    ChatMessage(
                        ChatMessageRole.User,
                        listOf(ChatMessagePart.Text("u1")),
                        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
                    )
                ),
            )
            listOf(
                """{"title": "   ", "messages": []}""",
                json.encodeToString(badEnding),
                """{"title": 
            """,
            ).forEach { body ->
                val response = client.post("/api/chats/import") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
            assertTrue(store.listChats(null).chats.isEmpty())
        }
    }

    @Test
    fun `import with an init-invalid message is 400 and creates nothing`() {
        val store = PostgresChatStore()
        testApplication {
            application {
                module(testKoinApp(testAppConfig(), chatStore = store).koin)
            }
            // an assistant message without meta violates ChatMessage's init
            // DURING the request decode — before ChatService.importChat's
            // catch can map the codec's IAE onto ChatValidationException. The
            // 400 comes from ktor's ContentNegotiation, which wraps ANY
            // converter failure (the init IAE included) in a
            // BadRequestException (RequestConverter.convertBody) — pinned
            // here because it depends on that ktor behavior. The payload is
            // raw JSON: the Kotlin DTO cannot even be constructed (the init
            // rejects it). The client sees the generic wrap message, not the
            // init's reason (that stays the cause) — service-level
            // violations are the ones that surface the precise reason.
            val body = """{"title": "t", "messages": [
                {"role": "assistant", "parts": [], "finishReason": "stop"}
            ]}"""
            val response = client.post("/api/chats/import") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(store.listChats(null).chats.isEmpty())
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

    // ---- ELTM digest route (`POST /api/eltm/digest`, the manual write path) ----

    /**
     * A fake hand dispatching on the one-shot system prompts: the
     * extraction one-shot answers [extraction] (the default echoes one
     * ready-made fact, the way the real extractor normalizes the text),
     * the writer runs [info.skyblond.daapu.testutil.writerRunFlow] against
     * [eltm] (one create_entity + add_entity_note round, executed through
     * the real [info.skyblond.daapu.memory.eltm.EltmToolProvider], then
     * the confirmation). The digest endpoint never calls anything else, so
     * any other request fails the test.
     */
    private fun digestHand(
        eltm: EltmService,
        extraction: suspend (HandRunRequest) -> List<HandEvent> = { textRunFlow("likes coffee") },
        writer: suspend (HandRunRequest) -> List<HandEvent> = { writerRunFlow(eltm) },
    ) = FakeHand(
        runScript = { request ->
            when {
                request.systemPrompt?.startsWith("You're extracting") == true -> extraction(request)
                request.systemPrompt?.startsWith("You're maintaining an external long-term memory") == true ->
                    writer(request)

                else -> error("unexpected run on the digest endpoint: ${request.systemPrompt}")
            }
        },
    )

    private fun digestText(text: String) = ChatMessagePart.Text(text)

    private fun digestImage(base64: String = "AAAA") = ChatMessagePart.Attachment(
        kind = AttachmentKind.Image,
        content = AttachmentContent.Base64(base64),
        mimeType = "image/png",
    )

    /**
     * The digest request body, serialized through the real DTO (the
     * polymorphic parts render with the `type` discriminator, exactly the
     * way a client would send them).
     */
    private fun digestBody(
        parts: List<ChatMessagePart> = emptyList(),
        date: String? = null,
    ): String = json.encodeToString(EltmDigestRequest(parts = parts, date = date))

    @Test
    fun `eltm digest rejects an empty or blank-parts request with 400`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            listOf(
                """{}""",
                """{"parts":[]}""",
                """{"parts":[{"type":"text","text":""}]}""",
                """{"parts":[{"type":"text","text":"   "}]}""",
            ).forEach { body ->
                val response = client.post("/api/eltm/digest") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a rejected text must not call the LLM")
    }

    @Test
    fun `eltm digest rejects a malformed or future date with 400`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            listOf(
                digestBody(listOf(digestText("User likes coffee")), date = "2026/01/01"),
                digestBody(listOf(digestText("User likes coffee")), date = "not-a-date"),
                digestBody(listOf(digestText("User likes coffee")), date = "2099-01-01"),
            ).forEach { body ->
                val response = client.post("/api/eltm/digest") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a rejected batch must not call the LLM")
    }

    @Test
    fun `eltm digest extracts the text and writes the facts through the ELTM writer`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // the route's own `LocalDate.now()` runs between the two reads,
            // so a midnight flip between the calls cannot flake the
            // "server's today" assertion below
            val before = LocalDate.now()
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("I like coffee"))))
            }
            val after = LocalDate.now()
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("", response.bodyAsText(), "201 Created carries no body")
            assertEquals(2, hand.requests.size, "extraction one-shot + writer run")
            assertTrue(
                hand.requests[0].systemPrompt!!.startsWith("You're extracting memories from a submission"),
                "the digest extraction uses the user-digest-flavored prompt: ${hand.requests[0].systemPrompt}",
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
        assertTrue(notes.contains("likes coffee"), "the digested fact must reach the ELTM")
    }

    @Test
    fun `eltm digest anchors the extraction at the optional date while the writer stamps today`() {
        val eltm = testPostgresEltmService(FakeHand())
        // the scripted writer reads the "current date" off its input the
        // way the real model does, so the recorded note stamps the write
        // day: the reference date only anchors the extraction — the writer
        // always writes with the server's today (the same write day the
        // discard pipeline uses)
        val hand = digestHand(
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
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("I like coffee")), date = "2026-01-01"))
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
    fun `eltm digest no-ops a pasted skip sentinel without an LLM call`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // the exact sentence and a casing/punctuation near-miss both hit
            // the tolerant input fast path (MemoryExtractionService
            // .isNothingToRemember, via digestUserInput) without any LLM
            // call, answered by the same 201 as a real digest
            listOf("Nothing worth remember.", "nothing worth remember").forEach { paste ->
                val response = client.post("/api/eltm/digest") {
                    contentType(ContentType.Application.Json)
                    setBody(digestBody(listOf(digestText(paste))))
                }
                assertEquals(HttpStatusCode.Created, response.status, "paste: $paste")
            }
        }
        assertTrue(hand.requests.isEmpty(), "the sentinel fast path makes no LLM call")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "the sentinel itself must never be recorded")
    }

    @Test
    fun `eltm digest no-ops when the extractor finds nothing worth remembering`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(
            eltm,
            extraction = { textRunFlow("Nothing worth remember.") },
            writer = { error("the writer must not run for an empty extraction") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("we just chatted about the weather"))))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
        assertEquals(1, hand.requests.size, "only the extraction one-shot happened")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "an empty extraction must not write the ELTM")
    }

    @Test
    fun `eltm digest no-ops a near-miss extraction sentinel without the writer`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(
            eltm,
            // a casing-variant near-miss of the sentinel: the post-extraction
            // check (MemoryExtractionService.digestUserInput) must be
            // tolerant too
            extraction = { textRunFlow("NOTHING WORTH REMEMBER.") },
            writer = { error("the writer must not run for an empty extraction") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("we just chatted about the weather"))))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
        assertEquals(1, hand.requests.size, "only the extraction one-shot happened")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "an empty extraction must not write the ELTM")
    }

    @Test
    fun `eltm digest surfaces a failed writer run as 502 with the reason`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(
            eltm,
            writer = { errorRunFlow("upstream", "provider exploded") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("I like coffee"))))
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
    fun `eltm digest surfaces a failed extraction as 502 with the reason`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(
            eltm,
            extraction = { errorRunFlow("upstream", "provider exploded") },
        )
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestText("I like coffee"))))
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
    fun `eltm digest passes interleaved parts to the extractor in order`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(
                    digestBody(
                        listOf(
                            digestText("I like coffee"),
                            digestImage(),
                            digestText("Also, I switched from editor A to editor B yesterday."),
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("", response.bodyAsText(), "201 Created carries no body")
            assertEquals(2, hand.requests.size, "extraction one-shot + writer run")
            // the synthetic input message carries the anchor and then the
            // parts VERBATIM in the given order, followed by the extraction
            // instruction
            val extractionMessages = hand.requests[0].messages
            assertEquals(2, extractionMessages.size, "the synthetic input message + the extraction instruction")
            assertTrue(
                ContextInjection().hasMetaPart(extractionMessages[0]),
                "the synthetic message carries its reference-date anchor",
            )
            val parts = extractionMessages[0].parts
            assertEquals(ChatMessagePart.Text("I like coffee"), parts[1], "the first text follows the anchor untouched")
            val attachment = parts[2] as ChatMessagePart.Attachment
            assertEquals(AttachmentKind.Image, attachment.kind)
            assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
            assertEquals("image/png", attachment.mimeType)
            assertEquals(
                ChatMessagePart.Text("Also, I switched from editor A to editor B yesterday."),
                parts[3],
                "the trailing text keeps its place after the image",
            )
        }
        // the writer run landed the fact in the ELTM diary
        val notes = runBlocking { TestDb.allEltmNotes().map { it.note } }
        assertTrue(notes.contains("likes coffee"), "the digested fact must reach the ELTM")
    }

    @Test
    fun `eltm digest accepts an images-only request without text`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // no text part at all: images alone are a valid digest (the
            // extraction model in the test config supports vision)
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestImage())))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals(2, hand.requests.size, "extraction one-shot + writer run")
            // the synthetic message carries only the anchor + the attachment
            val parts = hand.requests[0].messages[0].parts
            assertTrue(ContextInjection().hasMetaPart(hand.requests[0].messages[0]), "the anchor is prepended")
            assertEquals(2, parts.size, "anchor + attachment, no text part")
            val attachment = parts.last() as ChatMessagePart.Attachment
            assertEquals(AttachmentContent.Base64("AAAA"), attachment.content)
        }
        val notes = runBlocking { TestDb.allEltmNotes().map { it.note } }
        assertTrue(notes.contains("likes coffee"), "the digested fact must reach the ELTM")
    }

    @Test
    fun `eltm digest rejects a malformed or non-image part with 400 and no LLM call`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            listOf(
                // a non-image attachment kind (the wire carries video too)
                """{"parts":[{"type":"attachment","kind":"video","content":{"type":"base64","base64":"AAAA"},"mimeType":"video/mp4"}]}""",
                // an image-kind attachment with a non-image mimeType
                """{"parts":[{"type":"attachment","kind":"image","content":{"type":"base64","base64":"AAAA"},"mimeType":"text/plain"}]}""",
                // image-kind attachments with a mimeType the data-URL regex
                // would reject (see imageMimeTypeRegex): a bare "image/" and
                // one carrying parameters
                """{"parts":[{"type":"attachment","kind":"image","content":{"type":"base64","base64":"AAAA"},"mimeType":"image/"}]}""",
                """{"parts":[{"type":"attachment","kind":"image","content":{"type":"base64","base64":"AAAA"},"mimeType":"image/png;charset=x"}]}""",
                // an undecodable base64 payload (parity with parseImageDataUrl)
                """{"parts":[{"type":"attachment","kind":"image","content":{"type":"base64","base64":"!!!"},"mimeType":"image/png"}]}""",
                // a part type no digest accepts (tool reasoning)
                """{"parts":[{"type":"reasoning","content":"r"}]}""",
            ).forEach { body ->
                val response = client.post("/api/eltm/digest") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a rejected part must not call the LLM")
        assertTrue(runBlocking { TestDb.allEltmNotes() }.isEmpty(), "nothing recorded")
    }

    @Test
    fun `eltm digest folds whitespace out of base64 payloads like the chat-send path`() {
        val eltm = testPostgresEltmService(FakeHand())
        val hand = digestHand(eltm)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            // parity with parseImageDataUrl (see EltmRoute.kt): a folded
            // payload is accepted AND forwarded stripped — the same base64
            // the chat-send data-URL path would accept, so the wire never
            // carries whitespace on either path
            val response = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody(digestBody(listOf(digestImage("aGVs\nbG8= "))))
            }
            assertEquals(HttpStatusCode.Created, response.status)
            val attachment = hand.requests[0].messages[0].parts.last() as ChatMessagePart.Attachment
            assertEquals(
                AttachmentContent.Base64("aGVsbG8="),
                attachment.content,
                "the payload travels stripped",
            )
        }
        val notes = runBlocking { TestDb.allEltmNotes().map { it.note } }
        assertTrue(notes.contains("likes coffee"), "the folded-payload digest must reach the ELTM")
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

    // ---- persona export/import (`GET /api/personas/export`, `POST /api/personas/import`) ----

    @Test
    fun `persona export answers the transfer array as an attachment, empty whitelist included`() {
        runBlocking {
            TestDb.seedPersonaRow("Writer", "You are a writer.", listOf("gsg"))
            TestDb.seedPersonaRow("Poet", "You are a poet.", emptyList())
        }
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/personas/export")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "attachment; filename=personas.json",
                response.headers[HttpHeaders.ContentDisposition],
            )
            val entries = json.parseToJsonElement(response.bodyAsText()).jsonArray
            assertEquals(2, entries.size, "the code default is not exported")
            val writer = entries[0].jsonObject
            assertEquals("Writer", writer["name"]?.jsonPrimitive?.content)
            assertEquals("You are a writer.", writer["systemPrompt"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("gsg"),
                writer["allowedNamespaces"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            val poet = entries[1].jsonObject
            assertEquals("Poet", poet["name"]?.jsonPrimitive?.content)
            assertEquals("You are a poet.", poet["systemPrompt"]?.jsonPrimitive?.content)
            // the empty whitelist pins the encodeDefaults pitfall the transfer
            // types lean on (PersonaTransfer.kt): ktor's ContentNegotiation
            // Json serializes with `encodeDefaults = false`, so the payload
            // types must carry NO defaults — an `[]` (= all namespaces) must
            // reach the WIRE, not be dropped
            assertTrue(
                response.bodyAsText().contains("\"allowedNamespaces\":[]"),
                "an empty whitelist must serialize as an explicit [] on the wire",
            )
        }
    }

    @Test
    fun `persona import answers the created and skipped split and skips the matching rows`() {
        runBlocking { TestDb.seedPersonaRow("Poet", "You are a poet.", emptyList()) }
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.post("/api/personas/import") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    [
                      {"name":"Writer","systemPrompt":"You are a writer.","allowedNamespaces":["gsg"]},
                      {"name":"Poet","systemPrompt":"You are a poet.","allowedNamespaces":[]}
                    ]
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(
                listOf("Writer"),
                body["created"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("Poet"),
                body["skipped"]?.jsonArray?.map { it.jsonPrimitive.content },
            )
            // the skip must not mint a duplicate of the seeded Poet
            val personas = json.parseToJsonElement(client.get("/api/personas").bodyAsText()).jsonArray
            assertEquals(3, personas.size, "the code default, the created Writer, the single seeded Poet")
        }
    }

    @Test
    fun `persona import with an invalid entry is 400 and earlier creates stick`() {
        testApplication {
            application { module(testKoinApp().koin) }
            // `eltm` is not a loop namespace in the test app (only `gsg` is
            // served): the second entry fails the create validation and the
            // whole request 400s — but the first entry sticks (fail-fast
            // partial, see PersonaService.importPersonas)
            val response = client.post("/api/personas/import") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    [
                      {"name":"Writer","systemPrompt":"You are a writer.","allowedNamespaces":["gsg"]},
                      {"name":"Broken","systemPrompt":"You are broken.","allowedNamespaces":["eltm"]}
                    ]
                    """.trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("not served") == true,
                "the 400 must carry the validation reason",
            )
            val personas = json.parseToJsonElement(client.get("/api/personas").bodyAsText()).jsonArray
            assertEquals(2, personas.size, "the code default plus the row created before the failure")
            assertEquals("Writer", personas.last().jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `persona import rejects a non-array body or an entry missing a field with 400`() {
        testApplication {
            application { module(testKoinApp().koin) }
            // both fail DURING the request decode (a List cannot decode from
            // an object; the transfer type's fields are required, see
            // PersonaTransfer.kt), before the service runs — the same
            // decode-400 mapping the chat import tests pin
            listOf(
                """{"name":"W","systemPrompt":"p","allowedNamespaces":[]}""",
                """[{"name":"W"}]""",
            ).forEach { body ->
                val response = client.post("/api/personas/import") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
            }
            val personas = json.parseToJsonElement(client.get("/api/personas").bodyAsText()).jsonArray
            assertEquals(1, personas.size, "nothing was created")
        }
    }

    // ---- ELTM transfer (`GET /api/eltm/export`, `POST /api/eltm/import`) ----

    @Test
    fun `eltm export answers the transfer payload as an attachment and the file posts back verbatim`() {
        val eltm = testPostgresEltmService(FakeHand())
        runBlocking {
            val kindle = eltm.createEntity("kindle", "device").entity
            eltm.setEntityAttribute(kindle.id, "model", "k4")
            eltm.attachNoteToEntity(kindle.id, LocalDate.of(2026, 8, 17), "bought it")
            val alice = eltm.createEntity("alice", "person").entity
            val works = eltm.createRelationship(kindle.id, alice.id, "belongs to")
            eltm.attachNoteToRelationship(
                works.id, LocalDate.of(2026, 8, 18), "gave it away", valid = false,
            )
        }
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            val export = client.get("/api/eltm/export")
            assertEquals(HttpStatusCode.OK, export.status)
            assertEquals(
                "attachment; filename=eltm.json",
                export.headers[HttpHeaders.ContentDisposition],
            )
            val payload = json.parseToJsonElement(export.bodyAsText()).jsonObject
            val entities = payload["entities"]!!.jsonObject
            assertEquals(2, entities.size, "the entities are keyed by file uuids")
            val kindleEntry = entities.values
                .single { it.jsonObject["name"]!!.jsonPrimitive.content == "kindle" }.jsonObject
            assertEquals("device", kindleEntry["category"]!!.jsonPrimitive.content)
            assertEquals("k4", kindleEntry["attributes"]!!.jsonObject["model"]!!.jsonPrimitive.content)
            val relationships = payload["relationships"]!!.jsonArray
            assertEquals(1, relationships.size)
            val relationship = relationships[0].jsonObject
            assertTrue(relationship["srcUuid"]!!.jsonPrimitive.content in entities.keys)
            assertTrue(relationship["dstUuid"]!!.jsonPrimitive.content in entities.keys)
            assertEquals("belongs_to", relationship["verb"]!!.jsonPrimitive.content)
            assertFalse(relationship["valid"]!!.jsonPrimitive.boolean)
            // the exported file posts back verbatim as the import body
            // (no overwriteAttr field anywhere — the decision is the query
            // param's) and skips everything, already merged
            val import = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(export.bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, import.status)
            val summary = json.parseToJsonElement(import.bodyAsText()).jsonObject
            assertEquals(2, summary["entitiesMatched"]!!.jsonPrimitive.long)
            assertEquals(1, summary["relationshipsMatched"]!!.jsonPrimitive.long)
            assertEquals(2, summary["notesSkipped"]!!.jsonPrimitive.long)
            assertEquals(1, summary["attributesKept"]!!.jsonPrimitive.long)
        }
    }

    @Test
    fun `eltm import honors the overwriteAttr query param - default false`() {
        val eltm = testPostgresEltmService(FakeHand())
        val kindleId = runBlocking {
            eltm.createEntity("kindle", "device").entity.also {
                eltm.setEntityAttribute(it.id, "model", "k4")
            }.id
        }
        val file = """
            {"entities":{"uuid-a":{"name":"kindle","category":"device",
             "attributes":{"model":"k9"},"notes":[]}},"relationships":[]}
        """.trimIndent().replace("\n", "")
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            // no param: existing keys keep their values
            val kept = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(file)
            }
            assertEquals(HttpStatusCode.OK, kept.status)
            val keptSummary = json.parseToJsonElement(kept.bodyAsText()).jsonObject
            assertEquals(0, keptSummary["attributesWritten"]!!.jsonPrimitive.long)
            assertEquals(1, keptSummary["attributesKept"]!!.jsonPrimitive.long)
            assertEquals(
                "k4",
                client.get("/api/eltm/entities/$kindleId").bodyAsText()
                    .let { json.parseToJsonElement(it).jsonObject["attributes"]!!.jsonObject["model"]!! }
                    .jsonPrimitive.content,
            )

            // overwriteAttr=true: the file's value wins
            val overwritten = client.post("/api/eltm/import?overwriteAttr=true") {
                contentType(ContentType.Application.Json)
                setBody(file)
            }
            assertEquals(HttpStatusCode.OK, overwritten.status)
            assertEquals(
                "k9",
                client.get("/api/eltm/entities/$kindleId").bodyAsText()
                    .let { json.parseToJsonElement(it).jsonObject["attributes"]!!.jsonObject["model"]!! }
                    .jsonPrimitive.content,
            )

            // a garbage flag is a 400 before the body even matters
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/eltm/import?overwriteAttr=maybe") {
                    contentType(ContentType.Application.Json)
                    setBody(file)
                }.status,
            )
        }
    }

    @Test
    fun `eltm import rejects a broken file with 400 and creates nothing`() {
        val eltm = testPostgresEltmService(FakeHand())
        testApplication {
            application { module(testKoinApp(eltmService = eltm).koin) }
            // a dangling relationship endpoint: the service's validation
            // fails before the first write and the 400 carries the path
            val dangling = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"entities":{"uuid-a":{"name":"kindle","category":"device",
                       "attributes":{},"notes":[]}},
                       "relationships":[{"srcUuid":"uuid-a","verb":"knows",
                       "dstUuid":"uuid-ghost","valid":true,"notes":[]}]}""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, dangling.status)
            assertTrue(
                json.parseToJsonElement(dangling.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("does not reference an exported entity") == true,
                "the 400 must carry the validation reason",
            )
            // two entries resolving to the same (name, category) after
            // normalization: a corrupt backup (the DB holds the key once),
            // rejected with the duplicate named, nothing written
            val duplicate = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"entities":{"uuid-a":{"name":"kindle","category":"device",
                       "attributes":{},"notes":[]},
                       "uuid-b":{"name":"KINDLE","category":"device","attributes":{},"notes":[]}},
                       "relationships":[]}""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadRequest, duplicate.status)
            assertTrue(
                json.parseToJsonElement(duplicate.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("duplicates entities[uuid-a]") == true,
                "the 400 must name the duplicated key",
            )
            // a body that cannot decode (entities is an array) fails during
            // the request decode, before the service runs
            val undecodable = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody("""{"entities":[],"relationships":[]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, undecodable.status)
            assertEquals(
                0,
                client.get("/api/eltm/entities").bodyAsText()
                    .let { json.parseToJsonElement(it).jsonArray.size },
                "nothing was created",
            )
        }
    }

    @Test
    fun `eltm import answers 502 when an embedding call fails mid-merge - earlier writes stick`() {
        // the embed script fails the SECOND entity's create ("bob person"):
        // the failure is post-validation, so alice's row sticks and the 502
        // carries the upstream reason (the digest's 502 precedent)
        val hand = FakeHand(embedScript = { request ->
            if (request.input.any { "bob" in it }) {
                throw EmbeddingException(
                    "invalid_request", "content too large for the embedding model",
                )
            }
            FakeHand().embed(request)
        })
        val eltm = testPostgresEltmService(hand)
        testApplication {
            application { module(testKoinApp(hand = hand, eltmService = eltm).koin) }
            val response = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"entities":{"uuid-a":{"name":"alice","category":"person",
                       "attributes":{},"notes":[]},
                       "uuid-b":{"name":"bob","category":"person","attributes":{},"notes":[]}},
                       "relationships":[]}""".trimIndent(),
                )
            }
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(
                json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content
                    ?.contains("content too large") == true,
                "the 502 must carry the upstream reason",
            )
            val entities = json.parseToJsonElement(client.get("/api/eltm/entities").bodyAsText()).jsonArray
            assertEquals(1, entities.size, "the writes before the failure stick")
            assertEquals(
                "alice",
                entities[0].jsonObject["entity"]!!.jsonObject["canonicalName"]!!.jsonPrimitive.content,
            )
        }
    }
}
