package info.skyblond.daapu.agent.tool

import info.skyblond.daapu.agent.chat.ChatMessagePart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

/**
 * A minimal namespaced child: advertises one tool per entry, answers with
 * the reply text, and counts its executions so tests can assert routing.
 */
private class FakeToolProvider(
    namespaces: Set<String>,
    tools: Map<String, String>,
    private val timeoutSeconds: Long = 0,
    private val onClose: () -> Unit = {},
) : ToolProvider, AutoCloseable {
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

    override fun close() = onClose()
}

class CombinedToolProviderTest {

    private fun toolCall(id: String, name: String, arguments: JsonObject = buildJsonObject {}) =
        ToolCallRequest(id = id, name = name, args = arguments)

    private fun textOf(result: ChatMessagePart.ToolResult) =
        (result.parts.single() as ChatMessagePart.Text).text

    @Test
    fun `advertises the children's tools in child order`() = runBlocking {
        val calc = FakeToolProvider(setOf("calc"), mapOf("calc__add" to "add"))
        val search = FakeToolProvider(setOf("search"), mapOf("search__web" to "web"))
        val combined = CombinedToolProvider(listOf(calc, search))

        assertEquals(setOf("calc", "search"), combined.namespaces())
        assertEquals(
            listOf("calc__add", "search__web"),
            combined.specifications().map { it.name },
        )
    }

    @Test
    fun `routes execution to the owning child by namespace prefix`() = runBlocking {
        val calc = FakeToolProvider(setOf("calc"), mapOf("calc__add" to "add"))
        val search = FakeToolProvider(setOf("search"), mapOf("search__web" to "web"))
        val combined = CombinedToolProvider(listOf(calc, search))

        val addResult = combined.execute(toolCall("c1", "calc__add"))
        assertFalse(addResult.isError, textOf(addResult))
        assertEquals(listOf("calc__add"), calc.executed.map { it.name })
        assertTrue(search.executed.isEmpty(), "the other child is never called")

        val webResult = combined.execute(toolCall("c2", "search__web"))
        assertFalse(webResult.isError, textOf(webResult))
        assertEquals(listOf("search__web"), search.executed.map { it.name })
    }

    @Test
    fun `a child serving several namespaces routes all of them`() = runBlocking {
        val multi = FakeToolProvider(setOf("a", "b"), mapOf("a__one" to "1", "b__two" to "2"))
        val combined = CombinedToolProvider(listOf(multi))

        assertEquals(setOf("a", "b"), combined.namespaces())
        assertFalse(combined.execute(toolCall("c1", "a__one")).isError)
        assertFalse(combined.execute(toolCall("c2", "b__two")).isError)
        assertEquals(listOf("a__one", "b__two"), multi.executed.map { it.name })
    }

    @Test
    fun `an unroutable name answers an error result without touching any child`() = runBlocking {
        val calc = FakeToolProvider(setOf("calc"), mapOf("calc__add" to "add"))
        val combined = CombinedToolProvider(listOf(calc))

        // a bare (unprefixed) name
        val bare = combined.execute(toolCall("c1", "add"))
        assertTrue(bare.isError)
        assertTrue(textOf(bare).contains("not advertised"), textOf(bare))

        // an unknown namespace prefix
        val foreign = combined.execute(toolCall("c2", "search__web"))
        assertTrue(foreign.isError)
        assertTrue(textOf(foreign).contains("not advertised"), textOf(foreign))

        assertTrue(calc.executed.isEmpty(), "an unroutable name never reaches a child")
    }

    @Test
    fun `executionTimeoutSeconds delegates to the owning child`() {
        val calc = FakeToolProvider(setOf("calc"), mapOf("calc__add" to "add"), timeoutSeconds = 30)
        val search = FakeToolProvider(setOf("search"), mapOf("search__web" to "web"))
        val combined = CombinedToolProvider(listOf(calc, search))

        assertEquals(30, combined.executionTimeoutSeconds("calc__add"))
        assertEquals(0, combined.executionTimeoutSeconds("search__web"))
        assertEquals(0, combined.executionTimeoutSeconds("unknown__tool"))
        assertEquals(0, combined.executionTimeoutSeconds("add"))
    }

    @Test
    fun `close closes every AutoCloseable child`() {
        var closedCalc = false
        var closedSearch = false
        val calc = FakeToolProvider(setOf("calc"), mapOf("calc__add" to "add")) { closedCalc = true }
        val search = FakeToolProvider(setOf("search"), mapOf("search__web" to "web")) { closedSearch = true }
        CombinedToolProvider(listOf(calc, search)).close()

        assertTrue(closedCalc)
        assertTrue(closedSearch)
    }

    @Test
    fun `a child without a namespace fails at construction`() {
        val bare = FakeToolProvider(emptySet(), mapOf("add" to "add"))
        assertFailsWith<IllegalArgumentException> {
            CombinedToolProvider(listOf(bare))
        }
    }

    @Test
    fun `duplicate namespaces across children fail at construction`() {
        val a1 = FakeToolProvider(setOf("a"), mapOf("a__one" to "1"))
        val a2 = FakeToolProvider(setOf("a"), mapOf("a__two" to "2"))
        assertFailsWith<IllegalArgumentException> {
            CombinedToolProvider(listOf(a1, a2))
        }
    }

    @Test
    fun `an invalid child namespace fails at construction`() {
        val invalid = FakeToolProvider(setOf("a__b"), mapOf("a__b__x" to "x"))
        assertFailsWith<IllegalArgumentException> {
            CombinedToolProvider(listOf(invalid))
        }
        val uppercase = FakeToolProvider(setOf("Calc"), mapOf("Calc__add" to "add"))
        assertFailsWith<IllegalArgumentException> {
            CombinedToolProvider(listOf(uppercase))
        }
    }
}
