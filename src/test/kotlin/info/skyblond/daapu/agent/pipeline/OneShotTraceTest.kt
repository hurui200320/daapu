package info.skyblond.daapu.agent.pipeline

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.hand.HandModelSpec
import info.skyblond.daapu.hand.HandRunException
import info.skyblond.daapu.hand.HandRunRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Pins the one-shot trace rendering (agent/pipeline/OneShotTrace.kt): the
 * header (label/model/rounds/usage), the system prompt, the input and
 * transcript sections with reasoning, tool calls, tool results and usage,
 * attachment placeholders that never carry bytes, and the three terminal
 * outcomes.
 */
class OneShotTraceTest {

    private fun runRequest(
        systemPrompt: String? = "You are a test.",
        messages: List<ChatMessage> = listOf(user("hello")),
    ) = HandRunRequest(
        model = HandModelSpec(
            baseUrl = "http://gateway/v1",
            apiKey = "key",
            modelId = "test/model-1",
            contextWindow = 1000,
            maxOutputTokens = 100,
            reasoning = false,
            input = listOf("text"),
        ),
        messages = messages,
        systemPrompt = systemPrompt,
        maxTokens = 100,
        maxRounds = 0,
        maxRetries = 0,
        streamIdleTimeoutMs = 0,
    )

    private fun user(text: String) = ChatMessage(
        ChatMessageRole.User,
        listOf(ChatMessagePart.Text(text)),
    )

    private fun assistant(
        parts: List<ChatMessagePart>,
        inputTokens: Int,
        outputTokens: Int,
        finishReason: String,
    ) = ChatMessage(
        ChatMessageRole.Assistant,
        parts,
        meta = ChatMessageMeta(inputTokens, outputTokens, totalTokens = inputTokens + outputTokens),
        finishReason = finishReason,
    )

    private fun toolResult(
        id: String,
        tool: String,
        parts: List<ChatMessagePart.ContentPart>,
        isError: Boolean = false,
    ) = ChatMessage(
        ChatMessageRole.ToolResult,
        listOf(ChatMessagePart.ToolResult(id = id, tool = tool, parts = parts, isError = isError)),
    )

    /** A full tool round: reasoning + call, result, final answer. */
    private fun toolRound(): List<ChatMessage> = listOf(
        assistant(
            parts = listOf(
                ChatMessagePart.Reasoning("thinking about it\ncarefully"),
                ChatMessagePart.ToolCall(
                    id = "call_1",
                    tool = "eltm__search_entities",
                    args = buildJsonObject { put("q", "alice") },
                ),
            ),
            inputTokens = 10,
            outputTokens = 5,
            finishReason = "tool_calls",
        ),
        toolResult("call_1", "eltm__search_entities", listOf(ChatMessagePart.Text("entity hit"))),
        assistant(
            parts = listOf(ChatMessagePart.Text("the answer")),
            inputTokens = 20,
            outputTokens = 7,
            finishReason = "stop",
        ),
    )

    @Test
    fun `renders the header, system prompt, input and the full transcript`() {
        val trace = renderOneShotTrace(
            label = "ELTM write",
            request = runRequest(
                systemPrompt = "You are a writer.",
                messages = listOf(user("hello world")),
            ),
            messages = toolRound(),
            error = null,
        )

        assertContains(
            trace,
            "=== One-shot trace: ELTM write | model=test/model-1 | rounds=2 | tokens in=30 out=12 ===",
        )
        assertContains(trace, "--- system prompt ---")
        assertContains(trace, "You are a writer.")
        assertContains(trace, "--- input (1 messages) ---")
        assertContains(trace, "[user]")
        assertContains(trace, "  <text>\n    hello world\n  </text>")
        assertContains(trace, "--- transcript (3 messages) ---")
        assertContains(trace, "[assistant #1 | finish=tool_calls | in=10 out=5]")
        assertContains(trace, "  <reasoning>\n    thinking about it\n    carefully\n  </reasoning>")
        assertContains(trace, """  <tool_call#call_1> eltm__search_entities({"q":"alice"})""")
        assertContains(trace, "[tool_result#call_1 eltm__search_entities | ok]")
        assertContains(trace, "  <text>\n    entity hit\n  </text>")
        assertContains(trace, "[assistant #2 | finish=stop | in=20 out=7]")
        assertContains(trace, "  <text>\n    the answer\n  </text>")
        assertContains(trace, "--- end: done (stop) ---")
    }

    @Test
    fun `renders an error tool result as error`() {
        val messages = listOf(
            assistant(
                parts = listOf(
                    ChatMessagePart.ToolCall("call_1", "eltm__search_entities", JsonObject(emptyMap())),
                ),
                inputTokens = 10,
                outputTokens = 5,
                finishReason = "tool_calls",
            ),
            toolResult(
                "call_1", "eltm__search_entities",
                listOf(ChatMessagePart.Text("embedding too large")),
                isError = true,
            ),
        )
        val trace = renderOneShotTrace("ELTM write", runRequest(), messages, null)
        assertContains(trace, "[tool_result#call_1 eltm__search_entities | error]")
        assertContains(trace, "embedding too large")
    }

    @Test
    fun `renders attachments as placeholders and never the base64 bytes`() {
        val smallBase64 = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=" // 36 chars -> 27 decoded bytes
        val bigBase64 = "A".repeat(4096) // -> 3072 decoded bytes -> 3.0KB
        val input = listOf(
            ChatMessage(
                ChatMessageRole.User,
                listOf(
                    ChatMessagePart.Text("look at this"),
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64(smallBase64),
                        mimeType = "image/png",
                    ),
                ),
            ),
        )
        val messages = listOf(
            toolResult(
                "call_1", "exa__web_fetch_exa",
                listOf(
                    ChatMessagePart.Text("page content"),
                    ChatMessagePart.Attachment(
                        kind = AttachmentKind.Image,
                        content = AttachmentContent.Base64(bigBase64),
                        mimeType = "image/jpeg",
                    ),
                ),
            ),
        )
        val trace = renderOneShotTrace(null, runRequest(messages = input), messages, null)

        assertContains(trace, "[attachment image image/png, ~27B decoded, omitted]")
        assertContains(trace, "[attachment image image/jpeg, ~3.0KB decoded, omitted]")
        assertFalse(trace.contains(smallBase64), "the base64 payload must never be logged")
        assertFalse(trace.contains(bigBase64), "the base64 payload must never be logged")
    }

    @Test
    fun `renders the three terminal outcomes`() {
        val messages = toolRound()

        val done = renderOneShotTrace("Test", runRequest(), messages, error = null)
        assertContains(done, "--- end: done (stop) ---")

        val handError = renderOneShotTrace(
            "Test", runRequest(), messages,
            error = HandRunException("round_limit", "maxRounds (150) reached at round 150"),
        )
        assertContains(handError, "--- end: hand error round_limit: maxRounds (150) reached at round 150 ---")

        val transport = renderOneShotTrace(
            "Test", runRequest(), messages,
            error = RuntimeException("wire cut"),
        )
        assertContains(transport, "--- end: transport failure: RuntimeException: wire cut ---")
    }

    @Test
    fun `renders the unlabelled, no-prompt, empty-run degenerate case`() {
        val trace = renderOneShotTrace(
            label = null,
            request = runRequest(systemPrompt = null, messages = emptyList()),
            messages = emptyList(),
            error = null,
        )
        assertContains(trace, "=== One-shot trace: (unlabelled) |")
        assertContains(trace, "(none)")
        assertContains(trace, "--- input (0 messages) ---")
        assertContains(trace, "--- transcript (0 messages) ---")
        assertContains(trace, "--- end: done (?) ---")
    }
}
