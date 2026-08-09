package info.skyblond.daapu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the [McpServerConfig] validation (issue #8 config surface: the MCP
 * server definitions are hardcoded in `Main.kt`, only their API keys come
 * from the environment/`.env`).
 */
class ConfigTest {

    private fun server(
        name: String = "exa",
        type: McpTransportType = McpTransportType.Http,
        url: String? = "https://mcp.exa.ai/mcp",
        command: List<String> = emptyList(),
    ) = McpServerConfig(name = name, type = type, url = url, command = command)

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
    fun `blank or non-conforming server name fails fast`() {
        assertFailsWith<IllegalArgumentException> { server(name = "  ").validate() }
        // the name becomes part of the advertised tool name, so only
        // [a-zA-Z0-9_-] is acceptable
        assertFailsWith<IllegalArgumentException> { server(name = "my server!").validate() }
    }
}
