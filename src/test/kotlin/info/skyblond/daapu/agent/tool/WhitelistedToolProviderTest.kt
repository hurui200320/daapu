package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

/**
 * A minimal namespaced child, the same shape as the one in
 * `CombinedToolProviderTest`: advertises one tool per entry, answers with
 * the reply text, and counts its executions so tests can assert routing.
 */
private class FakeChildToolProvider(
    namespaces: Set<String>,
    tools: Map<String, String>,
    private val timeoutSeconds: Long = 0,
) : ToolProvider {
    val namespaces: Set<String> = namespaces
    val advertised: List<ToolSpec> = tools.map { (name, _) ->
        ToolSpec(name, "fake tool", buildJsonObject {})
    }
    val executed = mutableListOf<ToolCallRequest>()

    override fun namespaces(): Set<String> = namespaces

    override suspend fun specifications(): List<ToolSpec> = advertised

    override fun executionTimeoutSeconds(toolName: String): Long =
        if (advertised.any { it.name == toolName }) timeoutSeconds else 0

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        executed += request
        val reply = advertised.firstOrNull { it.name == request.name }
        return if (reply == null) {
            ChatMessagePart.ToolResult(
                id = request.id, tool = request.name,
                parts = listOf(ChatMessagePart.Text("Error: unknown tool")), isError = true,
            )
        } else {
            ChatMessagePart.ToolResult(
                id = request.id, tool = request.name,
                parts = listOf(ChatMessagePart.Text("reply to ${request.name}")),
            )
        }
    }
}

class WhitelistedToolProviderTest {

    private fun toolCall(id: String, name: String, arguments: JsonObject = buildJsonObject {}) =
        ToolCallRequest(id = id, name = name, args = arguments)

    private fun textOf(result: ChatMessagePart.ToolResult) =
        (result.parts.single() as ChatMessagePart.Text).text

    @Test
    fun `advertises only the whitelisted namespaces' tools`() = runBlocking {
        val delegate = FakeChildToolProvider(
            setOf("eltm", "exa", "notes"),
            mapOf(
                "eltm__search" to "search",
                "exa__web" to "web",
                "notes__list" to "list",
            ),
        )
        val whitelisted = WhitelistedToolProvider(delegate, setOf("eltm", "exa"))

        assertEquals(setOf("eltm", "exa"), whitelisted.namespaces())
        assertEquals(
            listOf("eltm__search", "exa__web"),
            whitelisted.specifications().map { it.name },
        )
    }

    @Test
    fun `routes whitelisted execution to the delegate`() = runBlocking {
        val delegate = FakeChildToolProvider(
            setOf("eltm", "exa"),
            mapOf("eltm__search" to "search", "exa__web" to "web"),
        )
        val whitelisted = WhitelistedToolProvider(delegate, setOf("eltm"))

        val result = whitelisted.execute(toolCall("c1", "eltm__search"))
        assertFalse(result.isError, textOf(result))
        assertEquals(listOf("eltm__search"), delegate.executed.map { it.name })
    }

    @Test
    fun `a non-whitelisted name answers an error without touching the delegate`() = runBlocking {
        val delegate = FakeChildToolProvider(
            setOf("eltm", "exa"),
            mapOf("eltm__search" to "search", "exa__web" to "web"),
        )
        val whitelisted = WhitelistedToolProvider(delegate, setOf("eltm"))

        // an advertised-but-not-whitelisted namespace
        val foreign = whitelisted.execute(toolCall("c1", "exa__web"))
        assertTrue(foreign.isError)
        assertTrue(textOf(foreign).contains("not allowed"), textOf(foreign))

        // an unknown namespace prefix
        val unknown = whitelisted.execute(toolCall("c2", "notes__list"))
        assertTrue(unknown.isError)
        assertTrue(textOf(unknown).contains("not allowed"), textOf(unknown))

        // a bare (unprefixed) name
        val bare = whitelisted.execute(toolCall("c3", "search"))
        assertTrue(bare.isError)
        assertTrue(textOf(bare).contains("not allowed"), textOf(bare))

        assertTrue(delegate.executed.isEmpty(), "a non-whitelisted name never reaches the delegate")
    }

    @Test
    fun `executionTimeoutSeconds delegates whitelisted names and answers 0 for others`() {
        val delegate = FakeChildToolProvider(
            setOf("eltm", "exa"),
            mapOf("eltm__search" to "search", "exa__web" to "web"),
            timeoutSeconds = 120,
        )
        val whitelisted = WhitelistedToolProvider(delegate, setOf("eltm"))

        assertEquals(120, whitelisted.executionTimeoutSeconds("eltm__search"))
        assertEquals(0, whitelisted.executionTimeoutSeconds("exa__web"))
        assertEquals(0, whitelisted.executionTimeoutSeconds("unknown__tool"))
        assertEquals(0, whitelisted.executionTimeoutSeconds("search"))
    }

    @Test
    fun `an empty whitelist fails at construction`() {
        val delegate = FakeChildToolProvider(setOf("eltm"), mapOf("eltm__search" to "search"))
        assertFailsWith<IllegalArgumentException> {
            WhitelistedToolProvider(delegate, emptySet())
        }
    }

    @Test
    fun `an invalid whitelist namespace fails at construction`() {
        val delegate = FakeChildToolProvider(setOf("eltm"), mapOf("eltm__search" to "search"))
        assertFailsWith<IllegalArgumentException> {
            WhitelistedToolProvider(delegate, setOf("Eltm"))
        }
        assertFailsWith<IllegalArgumentException> {
            WhitelistedToolProvider(delegate, setOf("a__b"))
        }
        assertFailsWith<IllegalArgumentException> {
            WhitelistedToolProvider(delegate, setOf(""))
        }
    }

    @Test
    fun `a whitelist namespace the delegate does not serve fails at construction`() {
        val delegate = FakeChildToolProvider(setOf("eltm"), mapOf("eltm__search" to "search"))
        assertFailsWith<IllegalArgumentException> {
            WhitelistedToolProvider(delegate, setOf("eltm", "exa"))
        }
    }

    @Test
    fun `a delegate's unprefixed advertisements are dropped`() = runBlocking {
        val delegate = FakeChildToolProvider(
            setOf("eltm"),
            mapOf("eltm__search" to "search", "add" to "add"),
        )
        val whitelisted = WhitelistedToolProvider(delegate, setOf("eltm"))

        assertEquals(listOf("eltm__search"), whitelisted.specifications().map { it.name })
        val bare = whitelisted.execute(toolCall("c1", "add"))
        assertTrue(bare.isError)
        assertTrue(delegate.executed.isEmpty())
    }
}
