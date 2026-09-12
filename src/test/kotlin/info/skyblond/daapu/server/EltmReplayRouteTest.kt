package info.skyblond.daapu.server

import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.db.setEltmMaintenanceMode
import info.skyblond.daapu.db.withTransaction
import info.skyblond.daapu.hand.FakeHand
import info.skyblond.daapu.hand.textRunFlow
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ELTM replay's HTTP surface (`server/endpoint/EltmRoute.kt`'s
 * `/replay` pair): the idle status read, the synchronous 400s (a body that
 * fails the stored-chat invariants, an empty chat, bad knobs — all before
 * any LLM spend), the 503 maintenance block (fired before any body
 * parsing, while the status read stays open), the 202 start with the walk
 * running in the background over the REAL extraction queue, and the 409
 * single-flight refusal. The walk's own semantics are pinned by
 * `EltmReplayServiceTest`.
 */
class EltmReplayRouteTest : DbTestBase() {

    private val json = Json { explicitNulls = false }

    /** One user/assistant round, the neutral format's stored shape. */
    private fun round(n: Int): List<ChatMessage> = listOf(
        ChatMessage(
            role = ChatMessageRole.User,
            parts = listOf(ChatMessagePart.Text("u$n")),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        ),
        ChatMessage(
            role = ChatMessageRole.Assistant,
            parts = listOf(ChatMessagePart.Text("a$n")),
            meta = ChatMessageMeta(0, 0, 0, null),
            finishReason = "stop",
        ),
    )

    /** The upload body of a valid [rounds]-round chat. */
    private fun chatBody(rounds: Int): String =
        ChatCodec.encodeChat((1..rounds).flatMap { round(it) })

    /** Enable the flag directly in the DB (the route under test must not be needed to set up). */
    private fun enableMaintenance(enabled: Boolean = true) {
        runBlocking { withTransaction { setEltmMaintenanceMode(enabled) } }
    }

    /** `GET /api/eltm/replay` parsed as JSON. */
    private suspend fun HttpClient.replayStatus(): JsonObject =
        json.parseToJsonElement(get("/api/eltm/replay").bodyAsText()).jsonObject

    /** POST the replay body; [query] is the optional `?k=v&k=v` knob string. */
    private suspend fun HttpClient.postReplay(body: String, query: String = ""): HttpResponse =
        post("/api/eltm/replay$query") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** Poll until the walk leaves the running phase (or fail on the deadline). */
    private suspend fun HttpClient.awaitReplayDone() {
        val deadline = System.currentTimeMillis() + 10_000
        while (replayStatus()["state"]!!.jsonPrimitive.content == "running") {
            check(System.currentTimeMillis() < deadline) { "replay did not finish within 10s" }
            delay(25)
        }
    }

    @Test
    fun `status is readable and idle by default`() {
        testApplication {
            application { module(testKoinApp().koin) }
            val response = client.get("/api/eltm/replay")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("idle", client.replayStatus()["state"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a body failing the stored-chat invariants is a 400 before any LLM call`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            // not JSON at all
            assertEquals(HttpStatusCode.BadRequest, client.postReplay("not json").status)
            // decodes, but violates the trailing-assistant-stop invariant
            // (ends on a user message)
            val midTurn = ChatCodec.encodeChat((1..3).flatMap { round(it) }.dropLast(1))
            assertEquals(HttpStatusCode.BadRequest, client.postReplay(midTurn).status)
            // an empty array decodes but cannot be replayed
            assertEquals(HttpStatusCode.BadRequest, client.postReplay("[]").status)
        }
        assertTrue(hand.requests.isEmpty(), "a refused start must not call the LLM")
        assertTrue(runBlocking { TestDb.allExtractionJobs().isEmpty() }, "nothing was enqueued")
    }

    @Test
    fun `a decode-valid chat without user messages is a 400 before any LLM call`() {
        // a lone assistant message passes ChatCodec.validateChat, but the
        // walk's window arithmetic is round-based — start refuses it
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val body = ChatCodec.encodeChat(
                listOf(
                    ChatMessage(
                        role = ChatMessageRole.Assistant,
                        parts = listOf(ChatMessagePart.Text("prologue")),
                        meta = ChatMessageMeta(0, 0, 0, null),
                        finishReason = "stop",
                    )
                )
            )
            assertEquals(HttpStatusCode.BadRequest, client.postReplay(body).status)
        }
        assertTrue(hand.requests.isEmpty(), "a refused start must not call the LLM")
        assertTrue(runBlocking { TestDb.allExtractionJobs().isEmpty() }, "nothing was enqueued")
    }

    @Test
    fun `bad knobs are a 400 before any LLM call`() {
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(
                HttpStatusCode.BadRequest,
                client.postReplay(chatBody(4), "?compactionRounds=1").status,
                "compactionRounds=1 cannot shrink the chat",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.postReplay(chatBody(4), "?contextRounds=0").status,
                "contextRounds=0 keeps no reference round",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.postReplay(chatBody(4), "?compactionRounds=abc").status,
                "a non-integer knob is rejected",
            )
        }
        assertTrue(hand.requests.isEmpty(), "a refused start must not call the LLM")
    }

    @Test
    fun `replay is blocked with 503 while maintenance mode is on, before any body parsing`() {
        enableMaintenance()
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            // a garbage body still answers 503: the guard fires first
            assertEquals(HttpStatusCode.ServiceUnavailable, client.postReplay("not json").status)
            // the status read is never blocked
            assertEquals("idle", client.replayStatus()["state"]!!.jsonPrimitive.content)
        }
        assertTrue(hand.requests.isEmpty(), "a blocked replay must not call the LLM")
    }

    @Test
    fun `replay walks the chat, answers 202 and enqueues the regions into the real queue`() {
        val hand = FakeHand(runScript = { textRunFlow("summary") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val response = client.postReplay(chatBody(20))
            assertEquals(HttpStatusCode.Accepted, response.status)
            // the fake compaction is instantaneous, so the 202's body may
            // already say finished — only the terminal status is
            // deterministic
            client.awaitReplayDone()
            val status = client.replayStatus()
            assertEquals("finished", status["state"]!!.jsonPrimitive.content)
            assertEquals(40, status["messagesTotal"]!!.jsonPrimitive.int)
            assertEquals(2, status["windowsCompacted"]!!.jsonPrimitive.int)
            assertEquals(3, status["jobsQueued"]!!.jsonPrimitive.int)
            // two dropped regions plus the residue, in the real queue —
            // no worker runs in the test server, so they stay queued
            assertEquals(3, TestDb.allExtractionJobs().size)
            assertEquals(2, hand.requests.size, "two compaction calls for two full windows")
        }
    }

    @Test
    fun `the 202 body reports the fresh-start running snapshot with the zeroed counters on the wire`() {
        // the gated compaction pins the walk at Running(0, 0) while the
        // route answers: the counters' zero values are exactly what the
        // ContentNegotiation Json's encodeDefaults = false would drop —
        // the DTO's @EncodeDefault annotations (Dtos.kt) must keep them
        // on the wire, or the web UI renders "undefined"
        val gate = CompletableDeferred<Unit>()
        val hand = FakeHand(runScript = {
            gate.await()
            textRunFlow("summary")
        })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            val response = client.postReplay(chatBody(20))
            assertEquals(HttpStatusCode.Accepted, response.status)
            val status = client.replayStatus()
            assertEquals("running", status["state"]!!.jsonPrimitive.content)
            assertEquals(0, status["windowsCompacted"]!!.jsonPrimitive.int)
            assertEquals(0, status["jobsQueued"]!!.jsonPrimitive.int)
            gate.complete(Unit)
            client.awaitReplayDone()
        }
    }

    @Test
    fun `a chat within one window finishes with zero windows and the counters on the wire`() {
        // 3 rounds never exceed one window: the whole chat enqueues as the
        // residue, no compaction runs — windowsCompacted's 0 default would
        // be dropped from the wire without the DTO's @EncodeDefault
        val hand = FakeHand(runScript = { error("the LLM must not be called") })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(HttpStatusCode.Accepted, client.postReplay(chatBody(3)).status)
            client.awaitReplayDone()
            val status = client.replayStatus()
            assertEquals("finished", status["state"]!!.jsonPrimitive.content)
            assertEquals(6, status["messagesTotal"]!!.jsonPrimitive.int)
            assertEquals(0, status["windowsCompacted"]!!.jsonPrimitive.int)
            assertEquals(1, status["jobsQueued"]!!.jsonPrimitive.int)
            assertEquals(1, TestDb.allExtractionJobs().size, "the whole chat is the one enqueued region")
        }
        assertTrue(hand.requests.isEmpty(), "a within-window chat must not compact")
    }

    @Test
    fun `a second replay start is refused with 409 while one walks`() {
        // the gated compaction keeps the walk in the running phase until
        // released
        val gate = CompletableDeferred<Unit>()
        val hand = FakeHand(runScript = {
            gate.await()
            textRunFlow("summary")
        })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(HttpStatusCode.Accepted, client.postReplay(chatBody(20)).status)
            val second = client.postReplay(chatBody(5))
            assertEquals(HttpStatusCode.Conflict, second.status, "a running walk refuses the second start")
            assertEquals("running", client.replayStatus()["state"]!!.jsonPrimitive.content)
            assertTrue(TestDb.allExtractionJobs().isEmpty(), "the gated first window has not dropped anything yet")

            gate.complete(Unit)
            client.awaitReplayDone()
            assertEquals("finished", client.replayStatus()["state"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a mid-walk failure surfaces through the status endpoint, keeping enqueued regions`() {
        var calls = 0
        val hand = FakeHand(runScript = {
            calls++
            if (calls == 1) textRunFlow("S1") else error("upstream broke")
        })
        testApplication {
            application { module(testKoinApp(hand = hand).koin) }
            assertEquals(HttpStatusCode.Accepted, client.postReplay(chatBody(20)).status)
            client.awaitReplayDone()
            val status = client.replayStatus()
            assertEquals("failed", status["state"]!!.jsonPrimitive.content)
            assertTrue(
                status["error"]!!.jsonPrimitive.content.contains("Compaction summarization failed"),
                "the failure reason: ${status["error"]!!.jsonPrimitive.content}",
            )
            // the at-failure counters ride the wire too (one window
            // compacted, its region enqueued, then the failure)
            assertEquals(1, status["windowsCompacted"]!!.jsonPrimitive.int)
            assertEquals(1, status["jobsQueued"]!!.jsonPrimitive.int)
            // the first window's region was already enqueued and stays queued
            assertEquals(1, TestDb.allExtractionJobs().size)
        }
    }
}
