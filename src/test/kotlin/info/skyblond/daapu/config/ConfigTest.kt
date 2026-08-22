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
        namespace: String = "exa",
        type: McpTransportType = McpTransportType.Http,
        url: String? = "https://mcp.exa.ai/mcp",
        command: List<String> = emptyList(),
        initializationTimeoutSeconds: Long? = null,
        toolExecutionTimeoutSeconds: Long = 0,
        reconnectAttempts: Int = 3,
        reconnectDelayMs: Long = 1000L,
    ) = McpServerConfig(
        namespace = namespace, type = type, url = url, command = command,
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
                    "servers": [
                        {
                            "namespace": "exa",
                            "type": "http",
                            "url": "https://mcp.exa.ai/mcp",
                            "headers": { "Authorization": "Bearer sk-exa" },
                            "toolExecutionTimeoutSeconds": 120,
                        },
                        {
                            "namespace": "fs",
                            "type": "stdio",
                            "command": ["npx", "-y", "some-server"],
                            "environment": { "FOO": "bar" },
                            "toolExecutionTimeoutSeconds": 0,
                        },
                    ],
                },
                "memory": {
                    "compactModel": "bifrost/x",
                    "sstm": {
                        "extractModel": "bifrost/y",
                        "mergeModel": "bifrost/z",
                        "maxCapacity": 100,
                        "purgeBatchSize": 10
                    },
                    "eltm": {
                        "embeddingModel": "bifrost/embed",
                        "writerModel": "bifrost/w",
                        "recallModel": "bifrost/r",
                        "rewriteModel": "bifrost/rw"
                    }
                },
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

        val exa = config.mcp.servers[0]
        assertEquals("exa", exa.namespace)
        assertEquals(McpTransportType.Http, exa.type)
        assertEquals("https://mcp.exa.ai/mcp", exa.url)
        assertEquals(mapOf("Authorization" to "Bearer sk-exa"), exa.headers)
        assertEquals(120L, exa.toolExecutionTimeoutSeconds)
        assertEquals(3, exa.reconnectAttempts)
        assertEquals(1000L, exa.reconnectDelayMs)

        val fs = config.mcp.servers[1]
        assertEquals(McpTransportType.Stdio, fs.type)
        assertEquals(listOf("npx", "-y", "some-server"), fs.command)
        assertEquals(mapOf("FOO" to "bar"), fs.environment)
        assertEquals(0L, fs.toolExecutionTimeoutSeconds)
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
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
                "title": { "model": "t" },
            }
            """.trimIndent()
        )
        assertEquals(8080, config.server.port)
        assertTrue(config.mcp.servers.isEmpty())
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
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                    "mcp": { "servers": [ { "namespace": "exa", "type": "grpc" } ] },
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
                    "mcp": { "servers": [ { "namespace": "exa", "type": "Http" } ] },
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
                    "server": { "port": 0 },
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
        assertTrue(example.mcp.servers.isNotEmpty())
        assertTrue(example.title.model.isNotBlank())
    }

    @Test
    fun `a valid http server passes validation`() {
        server().validate()
    }

    @Test
    fun `a valid stdio server passes validation`() {
        server(
            type = McpTransportType.Stdio,
            url = null,
            command = listOf("npx", "-y", "some-server")
        ).validate()
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
        for (reserved in TOOL_RESERVED_NAMESPACES) {
            val e =
                assertFailsWith<IllegalArgumentException> { server(namespace = reserved).validate() }
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
            server(reconnectAttempts = 0).validate()
        }
        assertTrue(e.message!!.contains("reconnectAttempts"))
        assertFailsWith<IllegalArgumentException> { server(reconnectDelayMs = -1).validate() }
    }

    @Test
    fun `timeouts are validated`() {
        // mirroring config.schema.json's minimum: 1 — 0/negative would reach
        // the SDK transport builders as a nonsense Duration
        val e = assertFailsWith<IllegalArgumentException> {
            server(initializationTimeoutSeconds = 0).validate()
        }
        assertTrue(e.message!!.contains("initializationTimeoutSeconds"))
        assertFailsWith<IllegalArgumentException> {
            server(initializationTimeoutSeconds = -1).validate()
        }
        // toolExecutionTimeoutSeconds is REQUIRED and 0 disables it
        assertFailsWith<IllegalArgumentException> {
            server(toolExecutionTimeoutSeconds = -5).validate()
        }
        // null means "use the SDK default": valid
        server(initializationTimeoutSeconds = null).validate()
        server(initializationTimeoutSeconds = 30, toolExecutionTimeoutSeconds = 60).validate()
        server(toolExecutionTimeoutSeconds = 0).validate()
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
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
                    "title": { "model": "t" },
                    "mcp": {
                        "servers": [
                            {
                                "namespace": "exa",
                                "type": "http",
                                "url": "https://mcp.exa.ai/mcp"
                            }
                        ]
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
    fun `memory config decodes`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "memory": {
                    "compactModel": "bifrost/x",
                    "sstm": {
                        "extractModel": "bifrost/y",
                        "mergeModel": "bifrost/z",
                        "maxCapacity": 200,
                        "purgeBatchSize": 20
                    },
                    "eltm": {
                        "embeddingModel": "bifrost/embed",
                        "writerModel": "bifrost/w",
                        "recallModel": "bifrost/r",
                        "rewriteModel": "bifrost/rw",
                        "entityMatchThreshold": 0.3,
                        "noteSearchThreshold": 0.2,
                        "recallTimeoutSeconds": 60,
                        "maxWriterRounds": 10
                    }
                },
                "title": { "model": "bifrost/t" }
            }
            """.trimIndent()
        ).memory
        assertEquals("bifrost/x", decoded.compactModel)
        assertEquals("bifrost/y", decoded.sstm.extractModel)
        assertEquals("bifrost/z", decoded.sstm.mergeModel)
        assertEquals(150, decoded.sstm.maxMergeRounds)
        assertEquals(200, decoded.sstm.maxCapacity)
        assertEquals(20, decoded.sstm.purgeBatchSize)
        assertEquals("bifrost/embed", decoded.eltm.embeddingModel)
        assertEquals("bifrost/w", decoded.eltm.writerModel)
        assertEquals("bifrost/r", decoded.eltm.recallModel)
        assertEquals("bifrost/rw", decoded.eltm.rewriteModel)
        assertEquals(5, decoded.eltm.rewriteRounds, "rewriteRounds defaults to 5")
        assertEquals(0.3, decoded.eltm.entityMatchThreshold)
        assertEquals(0.2, decoded.eltm.noteSearchThreshold)
        assertEquals(60L, decoded.eltm.recallTimeoutSeconds)
        assertEquals(10, decoded.eltm.maxWriterRounds)
    }

    @Test
    fun `memory config requires all three model ids`() {
        // all three models are required: a config missing the memory section
        // or any model id must fail at decode, not fall back to a default
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {}
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
                    "memory": {
                        "compactModel": "bifrost/x",
                        "extractModel": "bifrost/y",
                        "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" }
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `memory config requires the sstm section`() {
        // the SSTM is REQUIRED (the injection is an unconditional
        // system-prompt promise): a config missing it must fail at decode
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "memory": {
                        "compactModel": "bifrost/x",
                        "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" }
                    },
                    "title": { "model": "bifrost/t" }
                }
                """.trimIndent()
            )
        }
        assertTrue(
            e.message!!.contains("sstm"),
            "the error should name the missing field: ${e.message}"
        )
    }

    @Test
    fun `memory config requires the eltm section`() {
        // the ELTM is REQUIRED for every deployment (the SSTM purge and the
        // recall tool are unconditional system-prompt promises): a config
        // missing it must fail at decode
        val e = assertFailsWith<Exception> {
            decodeAppConfig(
                """
                {
                    "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                    "providers": {},
                    "memory": {
                        "compactModel": "bifrost/x",
                        "sstm": { "extractModel": "bifrost/y", "mergeModel": "bifrost/z", "maxCapacity": 100, "purgeBatchSize": 10 }
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
            embeddingModel = "bifrost/embed",
            writerModel = "w",
            recallModel = "r",
            rewriteModel = "rw",
        )
        val validSstm = SstmConfig(
            extractModel = "bifrost/x",
            mergeModel = "bifrost/z",
            maxCapacity = 100,
            purgeBatchSize = 10,
        )
        val blank = assertFailsWith<IllegalArgumentException> {
            MemoryConfig(
                compactModel = "bifrost/x",
                sstm = validSstm.copy(mergeModel = "  "),
                eltm = validEltm,
            ).validate()
        }
        assertTrue(blank.message!!.contains("mergeModel"))

        val blankCompact = assertFailsWith<IllegalArgumentException> {
            MemoryConfig(
                compactModel = "  ",
                sstm = validSstm,
                eltm = validEltm,
            ).validate()
        }
        assertTrue(blankCompact.message!!.contains("compactModel"))
    }

    @Test
    fun `sstm config validation`() {
        val valid = SstmConfig(
            extractModel = "bifrost/x",
            mergeModel = "bifrost/z",
            maxCapacity = 100,
            purgeBatchSize = 10,
        )
        valid.validate()

        val negativeRounds = assertFailsWith<IllegalArgumentException> {
            valid.copy(maxMergeRounds = -1).validate()
        }
        assertTrue(negativeRounds.message!!.contains("maxMergeRounds"))
        valid.copy(maxMergeRounds = 0).validate()

        val badCapacity = assertFailsWith<IllegalArgumentException> {
            valid.copy(maxCapacity = 0).validate()
        }
        assertTrue(badCapacity.message!!.contains("maxCapacity"))

        val badBatch = assertFailsWith<IllegalArgumentException> {
            valid.copy(purgeBatchSize = 0).validate()
        }
        assertTrue(badBatch.message!!.contains("purgeBatchSize"))
    }

    @Test
    fun `eltm config validation`() {
        val valid = EltmConfig(
            embeddingModel = "bifrost/embed",
            writerModel = "w",
            recallModel = "r",
            rewriteModel = "rw",
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

        // thresholds must stay in [0, 1]
        assertFailsWith<IllegalArgumentException> { valid.copy(entityMatchThreshold = 1.1).validate() }
        assertFailsWith<IllegalArgumentException> { valid.copy(entityMatchThreshold = -0.1).validate() }
        assertFailsWith<IllegalArgumentException> { valid.copy(noteSearchThreshold = 2.0).validate() }
        valid.copy(entityMatchThreshold = 0.0, noteSearchThreshold = 1.0).validate()

        // the recall timeout is an execution budget: 0 = none
        assertFailsWith<IllegalArgumentException> { valid.copy(recallTimeoutSeconds = -1).validate() }
        valid.copy(recallTimeoutSeconds = 0).validate()

        assertFailsWith<IllegalArgumentException> { valid.copy(maxWriterRounds = -1).validate() }
        valid.copy(maxWriterRounds = 0).validate()
    }

    @Test
    fun `title config decodes`() {
        val decoded = decodeAppConfig(
            """
            {
                "database": {"url": "jdbc:postgresql://localhost:5432/postgres", "user": "postgres", "password": "postgres"},
                "providers": {},
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } }
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
                    "memory": { "compactModel": "x", "sstm": { "extractModel": "y", "mergeModel": "z", "maxCapacity": 100, "purgeBatchSize": 10 }, "eltm": { "embeddingModel": "bifrost/embed", "writerModel": "w", "recallModel": "r", "rewriteModel": "rw" } },
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
