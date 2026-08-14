package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.model.LLMCapability
import info.skyblond.daapu.agent.model.ModelProvider
import info.skyblond.daapu.agent.ModelCatalog
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.mcp.McpTransportException
import info.skyblond.daapu.memory.sstm.PostgresSstmService
import info.skyblond.daapu.server.ChatRunService
import info.skyblond.daapu.server.module
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the hand-pi tool callback route (`POST /api/hand/tool`):
 * token check, runId lookup, tool execution with the isError/fatal
 * split, and the result-attachment capability check (today's per-round
 * `LLM.checkPromptContentCapabilities` equivalent for tool results).
 */
class HandCallbackTest {

    private fun json() = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.runApplicationWithService(block: suspend (ChatRunService) -> Unit) {
        val service = ChatRunService(testAppConfig())
        application {
            module(service, PostgresSstmService())
        }
        kotlinx.coroutines.runBlocking { block(service) }
    }

    private suspend fun io.ktor.client.HttpClient.callback(
        token: String? = "dev-token",
        body: HandToolCallbackRequest,
    ): Pair<HttpStatusCode, String> {
        val response = post("/api/hand/tool") {
            contentType(ContentType.Application.Json)
            token?.let { header("x-daapu-token", it) }
            setBody(json().encodeToString(HandToolCallbackRequest.serializer(), body))
        }
        return response.status to response.bodyAsText()
    }

    private fun textModel() = ModelCatalog(
        mapOf("bifrost" to ModelProvider(id = "bifrost", baseUrl = "http://127.0.0.1:9/v1", apiKey = "test"))
    ).findModel("bifrost/cerebras/gpt-oss-120b")!!

    private fun callbackRequest(runId: String = "run-1", name: String = "flag", args: JsonObject = JsonObject(emptyMap())) =
        HandToolCallbackRequest(runId = runId, chatId = "chat-1", id = "call_1", name = name, args = args)

    @Test
    fun `missing or wrong token is rejected`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", EmptyProvider(), textModel())
            val (status, _) = client.callback(token = null, body = callbackRequest())
            assertEquals(HttpStatusCode.Unauthorized, status)
            val (status2, _) = client.callback(token = "wrong", body = callbackRequest())
            assertEquals(HttpStatusCode.Unauthorized, status2)
        }
    }

    @Test
    fun `an unknown runId answers fatal`() = testApplication {
        runApplicationWithService { service ->
            val (status, body) = client.callback(body = callbackRequest(runId = "nope"))
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertNotNull(response["fatal"])
        }
    }

    @Test
    fun `a tool call executes and returns its parts`() = testApplication {
        runApplicationWithService { service ->
            val provider = EchoProvider()
            service.handCallback.register("run-1", provider, textModel())
            val (status, body) = client.callback(body = callbackRequest(args = buildJsonObject { put("text", "hi") }))
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertNull(response["fatal"], "a successful tool must not answer fatal: $body")
            assertEquals("""[{"type":"text","text":"hi"}]""", response["parts"].toString())
            assertEquals("false", response["isError"].toString())
            val executed = provider.executed.single()
            assertEquals("call_1", executed.id)
            assertEquals("flag", executed.name)
            assertEquals(buildJsonObject { put("text", "hi") }, executed.args)
        }
    }

    @Test
    fun `a tool-level error is an isError result, not fatal`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", FailingProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertNull(response["fatal"])
            assertEquals("true", response["isError"].toString())
        }
    }

    @Test
    fun `an MCP transport failure answers fatal`() = testApplication {
        runApplicationWithService { service ->
            service.handCallback.register("run-1", TransportFailingProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertTrue(response["fatal"].toString().contains("transport"))
        }
    }

    @Test
    fun `a result attachment the model cannot see answers fatal`() = testApplication {
        runApplicationWithService { service ->
            // gpt-oss-120b is text-only: an image result must fail the run
            // (today's per-round capability check equivalent)
            service.handCallback.register("run-1", ImageProvider(), textModel())
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            val fatal = response["fatal"].toString()
            assertTrue(fatal.contains("does not support image"), "fatal must name the mismatch: $fatal")
        }
    }

    @Test
    fun `a result attachment passes for a vision model`() = testApplication {
        runApplicationWithService { service ->
            val visionModel = ModelCatalog(
                mapOf("bifrost" to ModelProvider(id = "bifrost", baseUrl = "http://127.0.0.1:9/v1", apiKey = "test"))
            ).findModel("bifrost/cerebras/gemma-4-31b")!!
            assertTrue(visionModel.supports(LLMCapability.Input.Vision.Image))
            service.handCallback.register("run-1", ImageProvider(), visionModel)
            val (status, body) = client.callback(body = callbackRequest())
            assertEquals(HttpStatusCode.OK, status)
            val response = json().parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
            assertNull(response["fatal"])
        }
    }

    // ------------------------------------------------------------------
    // providers
    // ------------------------------------------------------------------

    private class EmptyProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("Error: no tools")),
                isError = true,
            )
    }

    private class EchoProvider : ToolProvider {
        val executed = mutableListOf<ToolCallRequest>()

        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
            executed += request
            return ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("hi")),
            )
        }
    }

    private class FailingProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(ChatMessagePart.Text("the tool failed")),
                isError = true,
            )
    }

    private class TransportFailingProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            throw McpTransportException("MCP transport failure", RuntimeException("boom"))
    }

    private class ImageProvider : ToolProvider {
        override suspend fun specifications(): List<ToolSpec> = emptyList()

        override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult =
            ChatMessagePart.ToolResult(
                id = request.id,
                tool = request.name,
                parts = listOf(
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64("AAAA"),
                        mimeType = "image/png",
                    )
                ),
            )
    }
}
