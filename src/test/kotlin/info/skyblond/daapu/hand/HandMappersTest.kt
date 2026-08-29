package info.skyblond.daapu.hand

import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import info.skyblond.daapu.testutil.testLlm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [handRunRequest] — the ONE construction site of the run
 * envelope: the model/policy → wire mapping every caller must share (the
 * hand holds no defaults, so a drifted field would silently change a
 * run's budget or retry behavior).
 */
class HandMappersTest {

    private val model = testLlm("bifrost/cerebras/gemma-4-31b")

    @Test
    fun `handRunRequest maps the model and the policy onto the envelope`() {
        val messages = listOf(
            ChatMessage(ChatMessageRole.User, listOf(ChatMessagePart.Text("q"))),
        )
        val request = handRunRequest(
            model = model,
            messages = messages,
            systemPrompt = "sys",
            policy = HandRunPolicy(maxRetries = 3, streamIdleTimeoutMs = 4321),
            maxRounds = 7,
        )
        assertEquals(model.toHandModelSpec(), request.model)
        assertEquals(messages, request.messages)
        assertEquals("sys", request.systemPrompt)
        // the model's own output budget travels on the request
        assertEquals(model.maxOutputTokens, request.maxTokens)
        assertEquals(7, request.maxRounds)
        // the policy travels verbatim
        assertEquals(3, request.maxRetries)
        assertEquals(4321L, request.streamIdleTimeoutMs)
        // runId and the tool URLs are HandService's business (attached per
        // run; the tool-less shape omits them) — never set here
        assertNull(request.runId)
        assertNull(request.toolListUrl)
        assertNull(request.toolCallbackUrl)
    }
}
