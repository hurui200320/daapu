package info.skyblond.daapu.config

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the config loading from `config.jsonc` ([decodeAppConfig]): the JSONC
 * surface (comments, trailing commas, `$schema`), the decode defaults, and
 * the [AppConfig]/[McpServerConfig] validation (issue #8 config surface).
 */
class ConfigTest {

    private fun server(
        type: McpTransportType = McpTransportType.Http,
        url: String? = "https://mcp.exa.ai/mcp",
        command: List<String> = emptyList(),
        initializationTimeoutSeconds: Long? = null,
        toolExecutionTimeoutSeconds: Long = 0,
        reconnectAttempts: Int = 3,
        reconnectDelayMs: Long = 1000L,
    ) = McpServerConfig(
        type = type, url = url, command = command,
        initializationTimeoutSeconds = initializationTimeoutSeconds,
        toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds,
        reconnectAttempts = reconnectAttempts, reconnectDelayMs = reconnectDelayMs,
    )

    @Test
    fun `a full jsonc config decodes with comments, trailing commas and schema key`() {
        val config = decodeAppConfig(
            """
            {
                // editor hint: VSCode/IntelliJ validate against config.schema.json
                "${'$'}schema": "./config.schema.json",

                "database": { // the pgvector database
                    "url": "jdbc:postgresql://localhost:5432/postgres",
                    "user": "postgres",
                    "password": "postgres",
                },
                /* block comments
                   work too */
                "providers": {
                    "bifrost": {
                        "apiKey": "sk-test",
                        "baseUrl": "http://localhost:8000",
                    },
                },
                "server": {
                    "port": 9090,
                },
                "mcp": {
                    "exa": {
                        "type": "http",
                        "url": "https://mcp.exa.ai/mcp",
                        "headers": { "Authorization": "Bearer sk-exa" },
                        "toolExecutionTimeoutSeconds": 120,
                    },
                    "customs": {
                        "fs": {
                            "type": "stdio",
                            "command": ["npx", "-y", "some-server"],
                            "environment": { "FOO": "bar" },
                            "toolExecutionTimeoutSeconds": 0,
                        },
                    },
                    "proxy": {
                        "host": "127.0.0.1",
                        "port": 7890,
                    },
                },
                "memory": {
                    "compactModel": "bifrost/x",
                    "eltm": {
                        "extractionModel": "bifrost/x",
                        "embeddingModel": "bifrost/embed",
                        "writerModel": "bifrost/w",
                        "rewriteModel": "bifrost/rw",
                        "rewriteRounds": 5,
                        "relatedEntitiesLimit": 5,
                        "relatedNotesLimit": 5
                    }
                },
                "agent": { "investigator": { "model": "bifrost/i", "allowedNamespaces": ["eltm"] } },
                "title": {
                    "model": "bifrost/t",
                },
            }
            """.trimIndent()
        )
        assertEquals("jdbc:postgresql://localhost:5432/postgres", config.database.url)
        assertEquals("postgres", config.database.user)
        assertEquals("postgres", config.database.password)
        assertEquals("sk-test", config.providers["bifrost"]?.apiKey)
        assertEquals("http://localhost:8000", config.providers["bifrost"]?.baseUrl)
        assertEquals(9090, config.server.port)
        assertEquals("./config.schema.json", config.schema)
        assertEquals("bifrost/t", config.title.model)

        // allServers merges the dedicated exa server under its hardcoded
        // namespace (getValue throws if the merge was lost)
        val exa = config.mcp.allServers().getValue(EXA_NAMESPACE)
        assertEquals(McpTransportType.Http, exa.type)
        assertEquals("https://mcp.exa.ai/mcp", exa.url)
        assertEquals(mapOf("Authorization" to "Bearer sk-exa"), exa.headers)
        assertEquals(120L, exa.toolExecutionTimeoutSeconds)
        assertEquals(3, exa.reconnectAttempts)
        assertEquals(1000L, exa.reconnectDelayMs)

        val fs = config.mcp.customs.getValue("fs")
        assertEquals(McpTransportType.Stdio, fs.type)
        assertEquals(listOf("npx", "-y", "some-server"), fs.command)
        assertEquals(mapOf("FOO" to "bar"), fs.environment)
        assertEquals(0L, fs.toolExecutionTimeoutSeconds)

        // the optional proxy section decodes into McpProxyConfig
        assertEquals(McpProxyConfig("127.0.0.1", 7890), config.mcp.proxy)
        config.mcp.validate()
    }

    @Test
    fun `comment-like text inside string values is preserved`() {
        // `//` and `/*` are part of the url/api key, not comments
        val config = decodeAppConfig(
            """
            {
                "database": {
                    "url": "jdbc:postgresql:////my//host//db",
                    "user": "postgres",
                    "password": "postgres",
                },
                "providers": {
                    "bifrost": {
                        "apiKey": "abc/*def*/ghi",
                        "baseUrl": "http://host/x//y",
                    },
                },
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "t" },
            }
            """.trimIndent()
        )
        assertEquals("jdbc:postgresql:////my//host//db", config.database.url)
        assertEquals("abc/*def*/ghi", config.providers["bifrost"]?.apiKey)
        assertEquals("http://host/x//y", config.providers["bifrost"]?.baseUrl)
    }

    @Test
    fun `optional sections fall back to defaults`() {
        val config = decodeAppConfig(
            """
            {
                "database": { "url": "u", "user": "p", "password": "p" },
                "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "t" },
            }
            """.trimIndent()
        )
        assertEquals(8080, config.server.port)
        assertTrue(config.mcp.customs.isEmpty())
        assertEquals(setOf(EXA_NAMESPACE), config.mcp.allServers().keys, "only the dedicated exa server by default")
        assertEquals(null, config.mcp.proxy, "no proxy by default")
    }

    @Test
    fun `multiple providers are keyed by their id`() {
        val config = decodeAppConfig(
            """
            {
                "database": { "url": "u", "user": "p", "password": "p" },
                "providers": {
                    "bifrost": { "apiKey": "k1", "baseUrl": "http://h1" },
                    "other": { "apiKey": "k2", "baseUrl": "http://h2" },
                },
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "t" },
            }
            """.trimIndent()
        )
        assertEquals(setOf("bifrost", "other"), config.providers.keys)
        assertEquals("k1", config.providers["bifrost"]?.apiKey)
        assertEquals("k2", config.providers["other"]?.apiKey)
        assertEquals("http://h2", config.providers["other"]?.baseUrl)
    }

    @Test
    fun `an invalid provider key fails validation`() {
        // the key is prefixed onto every model id ({provider.id}/{modelId}),
        // so only [0-9a-z_-] is allowed, mirroring config.schema.json's
        // propertyNames pattern
        val e = assertFailsWith<IllegalArgumentException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "My Provider": { "apiKey": "k", "baseUrl": "http://h" } },
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" },
                }
                """.trimIndent()
            )
        }
        assertTrue(e.message!!.contains("providers key"), e.message)
    }

    @Test
    fun `an unknown key fails decode instead of being dropped`() {
        // parsing is strict (mirroring the schema's additionalProperties:
        // false): a key this binary does not know is a config bug, fail fast
        // rather than silently ignoring it
        assertFailsWith<SerializationException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "unknownFutureKey": true,
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a config without the providers key fails decode`() {
        assertFailsWith<SerializationException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `a config with no providers decodes`() {
        // no provider configs is valid (only the "bifrost" provider is
        // wired at runtime, see ChatRunService — a config missing it fails
        // there with a clear message)
        val config = decodeAppConfig(
            """
            {
                "database": { "url": "u", "user": "p", "password": "p" },
                "providers": {},
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "t" },
            }
            """.trimIndent()
        )
        assertTrue(config.providers.isEmpty())
    }

    @Test
    fun `a missing config file fails with a clear message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            loadConfig(java.io.File("/nonexistent/config.jsonc"))
        }
        assertTrue(e.message!!.contains("config.example.jsonc"), e.message)
    }

    @Test
    fun `blank required values fail validation`() {
        val e = assertFailsWith<IllegalArgumentException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "", "baseUrl": "http://h" } },
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" },
                }
                """.trimIndent()
            )
        }
        assertTrue(e.message!!.contains("providers['bifrost'].apiKey"), e.message)
    }

    @Test
    fun `an invalid transport type fails decode`() {
        assertFailsWith<SerializationException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "mcp": {
                        "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 },
                        "customs": { "calc": { "type": "grpc" } },
                    },
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `the transport type serial names are lowercase`() {
        // the schema (config.schema.json) says type is "http"/"stdio"; the
        // Kotlin enum names must not leak into the config file
        assertFailsWith<SerializationException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "mcp": {
                        "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 },
                        "customs": { "calc": { "type": "Http" } },
                    },
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `the port must be in range`() {
        val e = assertFailsWith<IllegalArgumentException> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "server": { "port": 0 },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" },
                }
                """.trimIndent()
            )
        }
        assertTrue(e.message!!.contains("server.port"), e.message)
    }

    @Test
    fun `the checked-in example config stays parseable`() {
        // guards that config.example.jsonc (checked in) matches the models
        val example = decodeAppConfig(java.io.File("./config.example.jsonc").readText())
        assertEquals(8080, example.server.port)
        assertTrue(example.providers.containsKey("bifrost"))
        // the example must configure the dedicated exa server (getValue
        // throws if the merge was lost)
        example.mcp.allServers().getValue(EXA_NAMESPACE)
        assertTrue(example.mcp.customs.isEmpty())
        assertTrue(example.title.model.isNotBlank())
    }

    @Test
    fun `the dedicated exa server is required at decode`() {
        // exa is REQUIRED (fail fast): a config without the mcp.exa section
        // must fail at decode, not fall back to no exa server
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "mcp": {},
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("exa"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `the mcp section itself is required at decode`() {
        // mcp has no default: a config without the section at all must fail
        // at decode too (config.schema.json requires "mcp" the same way)
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("mcp"),
            "the error should name the missing section: ${e.message}"
        )
    }

    @Test
    fun `the dedicated exa server validates like any MCP server`() {
        // the exa entry is a plain McpServerConfig with its namespace
        // hardcoded to EXA_NAMESPACE: its own validation must run (a config
        // error in the exa section is a config error, fail fast)
        McpConfig(
            customs = emptyMap(),
            exa = server(url = "https://mcp.exa.ai/mcp", toolExecutionTimeoutSeconds = 120),
        ).validate()
        assertFailsWith<IllegalArgumentException> {
            McpConfig(customs = emptyMap(), exa = server(url = null)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            McpConfig(customs = emptyMap(), exa = server(toolExecutionTimeoutSeconds = -1)).validate()
        }
    }

    @Test
    fun `a customs entry colliding with the exa namespace fails fast`() {
        // the dedicated exa server owns the namespace "exa": a user
        // configured server claiming it would collide in the merged tool set
        val e = assertFailsWith<IllegalArgumentException> {
            McpConfig(
                customs = mapOf(EXA_NAMESPACE to server()),
                exa = server(url = "https://mcp.exa.ai/mcp", toolExecutionTimeoutSeconds = 120),
            ).validate()
        }
        assertTrue(e.message!!.contains("exa"), e.message)
        assertTrue(e.message!!.contains("mcp.customs"), e.message)
    }

    @Test
    fun `customs map keys are validated as namespaces`() {
        // the map key IS the namespace: McpConfig.validate must run the
        // namespace checks (syntax, no '__', not reserved) on every key
        val exa = server(url = "https://mcp.exa.ai/mcp", toolExecutionTimeoutSeconds = 120)
        assertFailsWith<IllegalArgumentException> {
            McpConfig(customs = mapOf("my server!" to server()), exa = exa).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            McpConfig(customs = mapOf("my__server" to server()), exa = exa).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            McpConfig(customs = mapOf("gsg" to server()), exa = exa).validate()
        }
        // a valid key passes
        McpConfig(customs = mapOf("fs" to server(type = McpTransportType.Stdio, url = null, command = listOf("x"))), exa = exa).validate()
    }

    @Test
    fun `allServers merges the exa server under its hardcoded namespace`() {
        val exaConfig = server(url = "https://mcp.exa.ai/mcp", toolExecutionTimeoutSeconds = 120)
        val fs = server(type = McpTransportType.Stdio, url = null, command = listOf("npx", "-y", "fs"))
        val config = McpConfig(customs = mapOf("fs" to fs), exa = exaConfig)
        assertEquals(mapOf("fs" to fs, EXA_NAMESPACE to exaConfig), config.allServers())
    }

    @Test
    fun `a valid http server passes validation`() {
        server().validate(EXA_NAMESPACE)
    }

    @Test
    fun `a valid stdio server passes validation`() {
        server(
            type = McpTransportType.Stdio,
            url = null,
            command = listOf("npx", "-y", "some-server")
        ).validate("calc")
    }

    @Test
    fun `http entry without a url fails fast`() {
        val e = assertFailsWith<IllegalArgumentException> { server(url = null).validate(EXA_NAMESPACE) }
        assertEquals("MCP server 'exa': type 'http' requires a url", e.message)
    }

    @Test
    fun `stdio entry without a command fails fast`() {
        val e = assertFailsWith<IllegalArgumentException> {
            server(type = McpTransportType.Stdio, url = null).validate(EXA_NAMESPACE)
        }
        assertEquals("MCP server 'exa': type 'stdio' requires a command", e.message)
    }

    @Test
    fun `invalid url scheme fails fast`() {
        assertFailsWith<IllegalArgumentException> { server(url = "ftp://host/mcp").validate(EXA_NAMESPACE) }
    }

    @Test
    fun `blank or non-conforming namespace fails fast`() {
        // the namespace (the mcp.customs map key) becomes part of the
        // advertised tool name, so only [0-9a-z_-] is acceptable
        assertFailsWith<IllegalArgumentException> { server().validate("  ") }
        assertFailsWith<IllegalArgumentException> { server().validate("my server!") }
        // uppercase is rejected too: the reserved-namespace check stays an
        // exact match on the lowercase reserved names
        assertFailsWith<IllegalArgumentException> { server().validate("Exa") }
    }

    @Test
    fun `a namespace containing the advertised-name separator fails fast`() {
        // `__` separates the parts of advertised tool names, so it cannot
        // appear inside a namespace
        assertFailsWith<IllegalArgumentException> { server().validate("my__server") }
    }

    @Test
    fun `reserved namespaces are rejected`() {
        // namespaces the harness reserves for internal/harness tools: an
        // MCP server using one would collide with those tools' names
        for (reserved in TOOL_RESERVED_NAMESPACES) {
            val e =
                assertFailsWith<IllegalArgumentException> { server().validate(reserved) }
            assertTrue(
                e.message!!.contains("reserved"),
                "reserved namespace '$reserved': ${e.message}"
            )
        }
    }

    @Test
    fun `reconnect parameters are validated`() {
        // reconnectAttempts is the total number of connect attempts, the
        // first one included: 0 would mean "never connect at all"
        val e = assertFailsWith<IllegalArgumentException> {
            server(reconnectAttempts = 0).validate(EXA_NAMESPACE)
        }
        assertTrue(e.message!!.contains("reconnectAttempts"))
        assertFailsWith<IllegalArgumentException> { server(reconnectDelayMs = -1).validate(EXA_NAMESPACE) }
    }

    @Test
    fun `timeouts are validated`() {
        // mirroring config.schema.json's minimum: 1 — 0/negative would reach
        // the SDK transport builders as a nonsense Duration
        val e = assertFailsWith<IllegalArgumentException> {
            server(initializationTimeoutSeconds = 0).validate(EXA_NAMESPACE)
        }
        assertTrue(e.message!!.contains("initializationTimeoutSeconds"))
        assertFailsWith<IllegalArgumentException> {
            server(initializationTimeoutSeconds = -1).validate(EXA_NAMESPACE)
        }
        // toolExecutionTimeoutSeconds is REQUIRED and 0 disables it
        assertFailsWith<IllegalArgumentException> {
            server(toolExecutionTimeoutSeconds = -5).validate(EXA_NAMESPACE)
        }
        // null means "use the SDK default": valid
        server(initializationTimeoutSeconds = null).validate(EXA_NAMESPACE)
        server(initializationTimeoutSeconds = 30, toolExecutionTimeoutSeconds = 60).validate(EXA_NAMESPACE)
        server(toolExecutionTimeoutSeconds = 0).validate(EXA_NAMESPACE)
    }

    @Test
    fun `an MCP server without toolExecutionTimeoutSeconds fails to decode`() {
        // every advertised tool must carry an explicit execution budget:
        // a server config missing the field fails at decode, not at runtime
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": { "url": "u", "user": "p", "password": "p" },
                    "providers": { "bifrost": { "apiKey": "k", "baseUrl": "http://h" } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                    "title": { "model": "t" },
                    "mcp": {
                        "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 },
                        "customs": {
                            "calc": {
                                "type": "http",
                                "url": "https://mcp.exa.ai/mcp"
                            }
                        }
                    }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("toolExecutionTimeoutSeconds"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `a valid mcp proxy passes validation`() {
        McpProxyConfig("127.0.0.1", 7890).validate()
        McpProxyConfig("proxy.example.com", 8080).validate()
    }

    @Test
    fun `an mcp proxy without a host fails validation`() {
        val e = assertFailsWith<IllegalArgumentException> { McpProxyConfig("  ", 7890).validate() }
        assertTrue(e.message!!.contains("mcp.proxy"), e.message)
        assertTrue(e.message!!.contains("host"), e.message)
    }

    @Test
    fun `an mcp proxy with an out-of-range port fails validation`() {
        // mirroring config.schema.json's minimum/maximum: 1..65535
        for (port in listOf(0, -1, 65536, 70000)) {
            val e = assertFailsWith<IllegalArgumentException> { McpProxyConfig("127.0.0.1", port).validate() }
            assertTrue(e.message!!.contains("port"), e.message)
        }
    }

    @Test
    fun `the mcp proxy is validated on the mcp config`() {
        // validate() walks the whole tree: a bad proxy fails even though the
        // servers themselves are fine
        val config = McpConfig(
            exa = server(),
            customs = emptyMap(),
            proxy = McpProxyConfig("127.0.0.1", 0),
        )
        val e = assertFailsWith<IllegalArgumentException> { config.validate() }
        assertTrue(e.message!!.contains("mcp.proxy"), e.message)
    }

    @Test
    fun `memory config decodes`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": {
                    "compactModel": "bifrost/x",
                    "eltm": {
                        "extractionModel": "bifrost/x",
                        "embeddingModel": "bifrost/embed",
                        "writerModel": "bifrost/w",
                        "rewriteModel": "bifrost/rw",
                        "rewriteRounds": 7,
                        "relatedEntitiesLimit": 3,
                        "relatedNotesLimit": 0,
                        "entityMatchThreshold": 0.3,
                        "noteSearchThreshold": 0.2,
                        "maxWriterRounds": 10
                    }
                },
                "agent": { "investigator": { "model": "bifrost/i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "bifrost/t" }
            }
            """.trimIndent()
        ).memory
        assertEquals("bifrost/x", decoded.compactModel)
        assertEquals("bifrost/x", decoded.eltm.extractionModel)
        assertEquals("bifrost/embed", decoded.eltm.embeddingModel)
        assertEquals("bifrost/w", decoded.eltm.writerModel)
        assertEquals("bifrost/rw", decoded.eltm.rewriteModel)
        assertEquals(7, decoded.eltm.rewriteRounds)
        assertEquals(3, decoded.eltm.relatedEntitiesLimit)
        assertEquals(0, decoded.eltm.relatedNotesLimit)
        assertEquals(0.3, decoded.eltm.entityMatchThreshold)
        assertEquals(0.2, decoded.eltm.noteSearchThreshold)
        assertEquals(10, decoded.eltm.maxWriterRounds)
    }

    @Test
    fun `memory config requires the memory section and its model ids`() {
        // the memory models are required: a config missing the memory section
        // or any model id must fail at decode, not fall back to a default
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } }
                }
                """.trimIndent()
            )
        }
assertTrue(
            e.message!!.contains("memory"),
            "the error should name the missing field: ${e.message}"
        )
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": {
                        "compactModel": "bifrost/x",
                        "eltm": { "extractionModel": "x", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 }
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `memory config requires the eltm round and limit knobs`() {
        // rewriteRounds/relatedEntitiesLimit/relatedNotesLimit are REQUIRED
        // (they bound the rewrite tail and the injected ELTM size against
        // the model context): a config missing any of them must fail at
        // decode, not fall back to a default
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": {
                        "compactModel": "bifrost/x",
                        "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 3, "relatedEntitiesLimit": 5 }
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("relatedNotesLimit"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `memory config requires the eltm extraction model`() {
        // the extraction model is REQUIRED (it drives the memory extraction
        // pipeline): a config missing it must fail at decode
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": {
                        "compactModel": "bifrost/x",
                        "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 }
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("extractionModel"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `memory config requires the eltm section`() {
        // the ELTM is REQUIRED for every deployment (the extraction pipeline
        // and the investigate agent's ELTM access depend on it): a
        // config missing it must fail at decode
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": {
                        "compactModel": "bifrost/x"
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("eltm"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `memory config validation`() {
        val validEltm = EltmConfig(
            extractionModel = "bifrost/x",
            embeddingModel = "bifrost/embed",
            writerModel = "w",
            rewriteModel = "rw",
            rewriteRounds = 5,
            relatedEntitiesLimit = 5,
            relatedNotesLimit = 5,
        )
        val blank = assertFailsWith<IllegalArgumentException> {
            MemoryConfig(
                compactModel = "bifrost/x",
                eltm = validEltm.copy(extractionModel = "  "),
            ).validate()
        }
        assertTrue(blank.message!!.contains("extractionModel"))

        val blankCompact = assertFailsWith<IllegalArgumentException> {
            MemoryConfig(
                compactModel = "  ",
                eltm = validEltm,
            ).validate()
        }
        assertTrue(blankCompact.message!!.contains("compactModel"))
    }

    @Test
    fun `eltm config validation`() {
        val valid = EltmConfig(
            extractionModel = "bifrost/x",
            embeddingModel = "bifrost/embed",
            writerModel = "w",
            rewriteModel = "rw",
            rewriteRounds = 5,
            relatedEntitiesLimit = 5,
            relatedNotesLimit = 5,
        )
        valid.validate()

        val blankId = assertFailsWith<IllegalArgumentException> {
            valid.copy(writerModel = "  ").validate()
        }
        assertTrue(blankId.message!!.contains("writerModel"))

        val blankRewrite = assertFailsWith<IllegalArgumentException> {
            valid.copy(rewriteModel = "  ").validate()
        }
        assertTrue(blankRewrite.message!!.contains("rewriteModel"))

        // the rewrite rounds are a tail-size knob: at least 1 trailing
        // user round must feed the rewrite (0/negative is a config error)
        assertFailsWith<IllegalArgumentException> { valid.copy(rewriteRounds = 0).validate() }
        assertFailsWith<IllegalArgumentException> { valid.copy(rewriteRounds = -1).validate() }

        // the related-limit knobs are REQUIRED and bound the injection size
        // against the main model's context: negative is a config error,
        // 0 skips the individual search (and is a valid value)
        assertFailsWith<IllegalArgumentException> {
            valid.copy(relatedEntitiesLimit = -1).validate()
        }
        assertFailsWith<IllegalArgumentException> { valid.copy(relatedNotesLimit = -1).validate() }
        valid.copy(relatedEntitiesLimit = 0, relatedNotesLimit = 0).validate()

        // thresholds must stay in [0, 1]
        assertFailsWith<IllegalArgumentException> { valid.copy(entityMatchThreshold = 1.1).validate() }
        assertFailsWith<IllegalArgumentException> { valid.copy(entityMatchThreshold = -0.1).validate() }
        assertFailsWith<IllegalArgumentException> { valid.copy(noteSearchThreshold = 2.0).validate() }
        valid.copy(entityMatchThreshold = 0.0, noteSearchThreshold = 1.0).validate()

        assertFailsWith<IllegalArgumentException> { valid.copy(maxWriterRounds = -1).validate() }
        valid.copy(maxWriterRounds = 0).validate()
    }

    @Test
    fun `agent config decodes and defaults the round cap`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "bifrost/i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "bifrost/t" }
            }
            """.trimIndent()
        ).agent
        assertEquals("bifrost/i", decoded.investigator.model)
        assertEquals(150, decoded.investigator.maxRounds, "maxRounds defaults to 150")
    }

    @Test
    fun `agent config requires the investigator model and whitelist`() {
        // the investigator section, its model, and the non-empty
        // allowedNamespaces whitelist are REQUIRED (resolved once at
        // startup): a config without them must fail at decode, not fall
        // back to a default
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": {},
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        // the investigator without the whitelist or the model id must fail
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "bifrost/i" } },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        // an empty whitelist is a config error, not "no tools"
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "bifrost/i", "allowedNamespaces": [] } },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `agent config validation`() {
        val valid = AgentConfig(
            investigator = InvestigatorConfig(model = "bifrost/i", allowedNamespaces = listOf("eltm"))
        )
        valid.validate()

        val blank = assertFailsWith<IllegalArgumentException> {
            AgentConfig(
                investigator = InvestigatorConfig(model = "  ", allowedNamespaces = listOf("eltm"))
            ).validate()
        }
        assertTrue(blank.message!!.contains("agent.investigator.model"))

        // the round cap is a run budget: 0 = none
        assertFailsWith<IllegalArgumentException> {
            valid.copy(investigator = valid.investigator.copy(maxRounds = -1)).validate()
        }
        valid.copy(investigator = valid.investigator.copy(maxRounds = 0)).validate()

        // the whitelist is REQUIRED non-empty and validated like any
        // tool namespace
        assertFailsWith<IllegalArgumentException> {
            AgentConfig(
                investigator = InvestigatorConfig(model = "bifrost/i", allowedNamespaces = emptyList())
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            AgentConfig(
                investigator = InvestigatorConfig(model = "bifrost/i", allowedNamespaces = listOf("  "))
            ).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            AgentConfig(
                investigator = InvestigatorConfig(model = "bifrost/i", allowedNamespaces = listOf("bad__ns"))
            ).validate()
        }
        valid.copy(investigator = valid.investigator.copy(allowedNamespaces = listOf("eltm", "exa"))).validate()
    }

    @Test
    fun `title config decodes`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "bifrost/t" }
            }
            """.trimIndent()
        ).title
        assertEquals("bifrost/t", decoded.model)
        assertEquals(0, decoded.lastNRound, "lastNRound defaults to 0 (the whole history)")
    }

    @Test
    fun `title config decodes lastNRound`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } },
                "title": { "model": "bifrost/t", "lastNRound": 3 }
            }
            """.trimIndent()
        ).title
        assertEquals(3, decoded.lastNRound)
    }

    @Test
    fun `title config requires the model id`() {
        // the model is required: a config without the title section or the
        // model id must fail at decode, not fall back to a default
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "agent": { "investigator": { "model": "i", "allowedNamespaces": ["eltm"] } }
                }
                """.trimIndent()
            )
        }
        assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "mcp": { "exa": { "type": "http", "url": "https://mcp.exa.ai/mcp", "toolExecutionTimeoutSeconds": 120 } },
                    "memory": { "compactModel": "x", "eltm": { "extractionModel": "x", "embeddingModel": "bifrost/embed", "writerModel": "w", "rewriteModel": "rw", "rewriteRounds": 5, "relatedEntitiesLimit": 5, "relatedNotesLimit": 5 } },
                    "title": {}
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `title config validation`() {
        val blank = assertFailsWith<IllegalArgumentException> {
            TitleConfig(model = "  ").validate()
        }
        assertTrue(blank.message!!.contains("title.model"))

        val negative = assertFailsWith<IllegalArgumentException> {
            TitleConfig(model = "t", lastNRound = -1).validate()
        }
        assertTrue(negative.message!!.contains("title.lastNRound"))
    }
}
