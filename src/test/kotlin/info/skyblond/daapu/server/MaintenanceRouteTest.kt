package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.agent.chat.PostgresChatStore
import info.skyblond.daapu.agent.persona.DEFAULT_PERSONA_ID
import info.skyblond.daapu.db.setEltmMaintenanceMode
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.assistantMessage
import info.skyblond.daapu.testutil.DbTestBase
import info.skyblond.daapu.testutil.TestDb
import info.skyblond.daapu.testutil.testKoinApp
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ELTM maintenance mode's HTTP surface
 * (`server/endpoint/MaintenanceRoute.kt`): the toggle endpoints, the 503
 * block on the four guarded routes (fired before any body validation), the
 * read-only surface staying open while enabled, and the recovery after the
 * mode is turned off — plus the re-embed job's endpoints (the 409 gate,
 * the 202 start, the status shape). The worker-side pause is pinned by
 * `ExtractionQueueWorkerTest`; the job itself by `EmbeddingRefreshServiceTest`.
 */
class MaintenanceRouteTest : DbTestBase() {

    private val model = "bifrost/cerebras/gpt-oss-120b"

    private val json = Json { explicitNulls = false }

    /** Enable the flag directly in the DB (the route under test must not be needed to set up). */
    private fun enableMaintenance(enabled: Boolean = true) {
        runBlocking { withTransaction { setEltmMaintenanceMode(enabled) } }
    }

    /**
     * PUT the flag and pin the documented response contract (the applied
     * state): asserts 200 and returns the body's `enabled` field.
     */
    private suspend fun HttpClient.putMaintenance(enabled: Boolean): Boolean {
        val response = put("/api/maintenance") {
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":$enabled}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return json.parseToJsonElement(response.bodyAsText())
            .jsonObject["enabled"]!!.jsonPrimitive.boolean
    }

    /** `GET /api/maintenance`'s `enabled` field. */
    private suspend fun HttpClient.maintenanceEnabled(): Boolean =
        json.parseToJsonElement(get("/api/maintenance").bodyAsText())
            .jsonObject["enabled"]!!.jsonPrimitive.boolean

    private fun messageBody(): String = json.encodeToString(
        SendMessageRequest(
            text = "hi",
            model = model,
            images = emptyList(),
            personaId = DEFAULT_PERSONA_ID,
        )
    )

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
        createdAt = Instant.parse("2026-08-17T09:00:00Z"),
    )

    @Test
    fun `get reports the default disabled state`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/maintenance")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(false, client.maintenanceEnabled())
        }
    }

    @Test
    fun `put toggles the mode and get reflects it`() {
        testApplication {
            application { module(testKoinApp().koin) }
            assertEquals(true, client.putMaintenance(true), "PUT must answer the applied state")
            assertEquals(true, client.maintenanceEnabled())
            assertEquals(false, client.putMaintenance(false), "PUT must answer the applied state")
            assertEquals(false, client.maintenanceEnabled())
        }
    }

    @Test
    fun `chat send is blocked with 503 while enabled, before any validation`() {
        TestDb.seedChatRow("chat-1")
        enableMaintenance()
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            // a VALID send body (the block is the only thing stopping it)
            // and a blank one (the guard fires before validation, so the
            // 503 trumps the 400) — neither may reach the LLM
            listOf(messageBody(), """{"text":"   ","model":"$model","personaId":0}""").forEach { body ->
                val response = client.post("/api/chats/chat-1/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, "body: $body")
            }
        }
        assertTrue(hand.requests.isEmpty(), "a blocked send must not call the LLM")
    }

    @Test
    fun `chat delete is blocked with 503 and enqueues nothing`() {
        TestDb.seedChatRow(
            "chat-1",
            messages = listOf(user("u1"), assistantMessage("a1")),
        )
        enableMaintenance()
        val store = PostgresChatStore()
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.delete("/api/chats/chat-1")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            // the chat survives and nothing entered the extraction queue
            assertEquals(2, store.load("chat-1")!!.content.messages.size, "the blocked delete must keep the chat")
            assertTrue(TestDb.allExtractionJobs().isEmpty(), "the blocked delete must not enqueue extraction")
        }
    }

    @Test
    fun `eltm digest and import are blocked with 503 while enabled`() {
        enableMaintenance()
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val digest = client.post("/api/eltm/digest") {
                contentType(ContentType.Application.Json)
                setBody("""{"parts":[{"type":"text","text":"I like coffee"}]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, digest.status)
            val import = client.post("/api/eltm/import") {
                contentType(ContentType.Application.Json)
                setBody("""{"entities":{},"relationships":[]}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, import.status)
        }
        assertTrue(hand.requests.isEmpty(), "a blocked digest/import must not call the LLM")
    }

    @Test
    fun `read-only and chat-only operations stay open while enabled`() {
        TestDb.seedChatRow(
            "chat-1",
            messages = listOf(user("u1"), assistantMessage("a1"), user("u2"), assistantMessage("a2")),
        )
        enableMaintenance()
        testApplication {
            application { module(testKoinApp().koin) }
            assertEquals(HttpStatusCode.OK, client.get("/api/chats").status, "list chats")
            assertEquals(HttpStatusCode.Created, client.post("/api/chats").status, "create chat")
            val rename = client.put("/api/chats/chat-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"title":"renamed"}""")
            }
            assertEquals(HttpStatusCode.OK, rename.status, "rename chat")
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/chats/chat-1/messages/2").status, "truncate")
            assertEquals(HttpStatusCode.Created, client.post("/api/chats/chat-1/fork/1").status, "fork")
            assertEquals(HttpStatusCode.OK, client.get("/api/chats/chat-1/chat").status, "read chat")
            assertEquals(HttpStatusCode.OK, client.get("/api/eltm/entities").status, "browse entities")
            assertEquals(HttpStatusCode.OK, client.get("/api/eltm/export").status, "export ELTM")
            assertEquals(HttpStatusCode.OK, client.get("/api/personas").status, "list personas")
            assertEquals(HttpStatusCode.OK, client.get("/api/models").status, "list models")
        }
    }

    @Test
    fun `blocked operations recover once the mode is turned off`() {
        TestDb.seedChatRow("chat-1")
        enableMaintenance()
        val store = PostgresChatStore()
        testApplication {
            application { module(testKoinApp().koin) }
            assertEquals(HttpStatusCode.ServiceUnavailable, client.delete("/api/chats/chat-1").status)
            // the toggle itself is never blocked — turning the mode off is
            // the whole point
            assertEquals(false, client.putMaintenance(false))
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/chats/chat-1").status)
            assertTrue(store.load("chat-1") == null, "the delete went through after disabling")
        }
    }

    // ------------------------------------------------------------------
    // the re-embed job's HTTP surface (GET/POST /api/maintenance/reembed)
    // ------------------------------------------------------------------

    /** `GET /api/maintenance/reembed` parsed as JSON. */
    private suspend fun HttpClient.reembedStatus(): JsonObject =
        json.parseToJsonElement(get("/api/maintenance/reembed").bodyAsText()).jsonObject

    /** Poll until the job leaves the running phase (or fail on the deadline). */
    private suspend fun HttpClient.awaitReembedDone() {
        val deadline = System.currentTimeMillis() + 10_000
        while (reembedStatus()["state"]!!.jsonPrimitive.content == "running") {
            check(System.currentTimeMillis() < deadline) { "re-embed job did not finish within 10s" }
            delay(25)
        }
    }

    @Test
    fun `reembed status is readable and idle by default, without maintenance mode`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/maintenance/reembed")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("idle", client.reembedStatus()["state"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `reembed start is refused with 409 while maintenance mode is off, before any embed`() {
        val hand = FakeHand()
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val response = client.post("/api/maintenance/reembed")
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(hand.embedRequests.isEmpty(), "a refused start must not embed anything")
        }
    }

    @Test
    fun `reembed starts under maintenance mode, runs through the hand and reports finished`() {
        val entityId = runBlocking { TestDb.seedEltmEntity("kindle", "device", embedding = null) }
        runBlocking { TestDb.seedEltmNote(entityId, "a note") }
        enableMaintenance()
        val hand = FakeHand()
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val response = client.post("/api/maintenance/reembed")
            assertEquals(HttpStatusCode.Accepted, response.status)
            // the fake embed is instantaneous, so the 202's body may already
            // say finished — only the terminal status is deterministic
            client.awaitReembedDone()
            val status = client.reembedStatus()
            assertEquals("finished", status["state"]!!.jsonPrimitive.content)
            assertEquals(1L, status["entities"]!!.jsonPrimitive.long)
            assertEquals(1L, status["notes"]!!.jsonPrimitive.long)
            assertEquals(2, hand.embedRequests.size, "one embed batch for the entity, one for the note")
            assertEquals(1L, TestDb.eltmVersion(), "the finished job bumps the counter once")
        }
    }

    @Test
    fun `a second reembed start is refused with 409 while one runs`() {
        runBlocking { TestDb.seedEltmEntity("kindle", "device") }
        enableMaintenance()
        // the gated embed keeps the job in the running phase until released
        val gate = CompletableDeferred<Unit>()
        val hand = FakeHand(embedScript = { request ->
            gate.await()
            FakeHand().embed(request)
        })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(HttpStatusCode.Accepted, client.post("/api/maintenance/reembed").status)
            val second = client.post("/api/maintenance/reembed")
            assertEquals(HttpStatusCode.Conflict, second.status, "a running job refuses the second start")
            assertEquals("running", client.reembedStatus()["state"]!!.jsonPrimitive.content)

            gate.complete(Unit)
            client.awaitReembedDone()
            assertEquals("finished", client.reembedStatus()["state"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a failed reembed run reports the failure reason through the status endpoint`() {
        runBlocking { TestDb.seedEltmEntity("kindle", "device") }
        enableMaintenance()
        val hand = FakeHand(embedScript = { error("upstream broke") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(HttpStatusCode.Accepted, client.post("/api/maintenance/reembed").status)
            client.awaitReembedDone()
            val status = client.reembedStatus()
            assertEquals("failed", status["state"]!!.jsonPrimitive.content)
            assertEquals("upstream broke", status["error"]!!.jsonPrimitive.content)
        }
    }
}
