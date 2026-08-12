package info.skyblond.daapu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the [McpServerConfig] validation (issue #8 config surface: the MCP
 * server definitions are hardcoded in `Main.kt`, only their API keys come
 * from the environment/`.env`).
 */
class ConfigTest {

    private fun server(
        namespace: String = "exa",
        type: McpTransportType = McpTransportType.Http,
        url: String? = "https://mcp.exa.ai/mcp",
        command: List<String> = emptyList(),
        reconnectAttempts: Int = 3,
        reconnectDelayMs: Long = 1000L,
    ) = McpServerConfig(
        namespace = namespace, type = type, url = url, command = command,
        reconnectAttempts = reconnectAttempts, reconnectDelayMs = reconnectDelayMs,
    )

    @Test
    fun `a valid http server passes validation`() {
        server().validate()
    }

    @Test
    fun `a valid stdio server passes validation`() {
        server(type = McpTransportType.Stdio, url = null, command = listOf("npx", "-y", "some-server")).validate()
    }

    @Test
    fun `http entry without a url fails fast`() {
        val e = assertFailsWith<IllegalArgumentException> { server(url = null).validate() }
        assertEquals("MCP server 'exa': type 'http' requires a url", e.message)
    }

    @Test
    fun `stdio entry without a command fails fast`() {
        val e = assertFailsWith<IllegalArgumentException> {
            server(type = McpTransportType.Stdio, url = null).validate()
        }
        assertEquals("MCP server 'exa': type 'stdio' requires a command", e.message)
    }

    @Test
    fun `invalid url scheme fails fast`() {
        assertFailsWith<IllegalArgumentException> { server(url = "ftp://host/mcp").validate() }
    }

    @Test
    fun `blank or non-conforming namespace fails fast`() {
        assertFailsWith<IllegalArgumentException> { server(namespace = "  ").validate() }
        // the namespace becomes part of the advertised tool name, so only
        // [0-9a-z_-] is acceptable
        assertFailsWith<IllegalArgumentException> { server(namespace = "my server!").validate() }
        // uppercase is rejected too: the reserved-namespace check stays an
        // exact match on the lowercase reserved names
        assertFailsWith<IllegalArgumentException> { server(namespace = "Exa").validate() }
    }

    @Test
    fun `a namespace containing the advertised-name separator fails fast`() {
        // `__` separates the parts of advertised tool names, so it cannot
        // appear inside a namespace
        assertFailsWith<IllegalArgumentException> { server(namespace = "my__server").validate() }
    }

    @Test
    fun `reserved namespaces are rejected`() {
        // namespaces the harness reserves for internal/harness tools: an
        // MCP server using one would collide with those tools' names
        for (reserved in MCP_RESERVED_NAMESPACES) {
            val e = assertFailsWith<IllegalArgumentException> { server(namespace = reserved).validate() }
            assertTrue(e.message!!.contains("reserved"), "reserved namespace '$reserved': ${e.message}")
        }
    }

    @Test
    fun `reconnect parameters are validated`() {
        // reconnectAttempts is the total number of connect attempts, the
        // first one included: 0 would mean "never connect at all"
        val e = assertFailsWith<IllegalArgumentException> {
            server(reconnectAttempts = 0).validate()
        }
        assertTrue(e.message!!.contains("reconnectAttempts"))
        assertFailsWith<IllegalArgumentException> { server(reconnectDelayMs = -1).validate() }
    }
}
