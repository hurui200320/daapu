package info.skyblond.daapu.agent.tool.bash

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.pipeline.investigate.InvestigatorService
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.config.AgentConfig
import info.skyblond.daapu.config.BashToolConfig
import info.skyblond.daapu.config.InvestigatorConfig
import info.skyblond.daapu.config.ToolConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.testutil.testKoinApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the bash tool provider against the real `/bin/bash`: the one-shot
 * semantics (no state between calls), the merged stdout+stderr capture,
 * the exit-code reporting, the timeout kill (partial output kept, the
 * SIGTERM → SIGKILL escalation) and the output capture cap.
 */
class BashToolProviderTest {

    private lateinit var tempRoot: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDirectory("daapu-bash-test")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.toFile().deleteRecursively()
    }

    // ---------- the tool surface ----------

    @Test
    fun `advertises a single bash__run tool under the bash namespace`() = runBlocking {
        val provider = provider()
        assertEquals(setOf("bash"), provider.namespaces())
        val specs = provider.specifications()
        assertEquals(listOf("bash__run"), specs.map { it.name })
        assertTrue(specs.all { it.description.isNotBlank() && it.schema.isNotEmpty() })
        assertContains(specs.single().description, "/bin/bash")
    }

    @Test
    fun `the execution budget is the configured timeout plus the backstop margin`() {
        assertEquals(7 + 30, provider(timeoutSeconds = 7).executionTimeoutSeconds("bash__run"))
        assertEquals(120 + 30, provider().executionTimeoutSeconds("bash__run"))
    }

    @Test
    fun `non-advertised names carry no execution budget`() {
        val p = provider()
        assertEquals(0, p.executionTimeoutSeconds("run"))
        assertEquals(0, p.executionTimeoutSeconds("bash__nope"))
        assertEquals(0, p.executionTimeoutSeconds("bash__run__extra"))
    }

    @Test
    fun `rejects names it does not advertise`() = runBlocking {
        for (name in listOf("run", "exa__web_search_exa", "bash__nope")) {
            val result = provider().execute(request(name))
            assertTrue(result.isError, "expected error for $name")
            assertContains(result.text(), "not advertised")
        }
    }

    // ---------- run: output and exit codes ----------

    @Test
    fun `run returns the command output`() = runBlocking {
        val result = provider().execute(request("bash__run", "command" to "echo hello world"))
        assertFalse(result.isError)
        assertEquals("hello world", result.text())
    }

    @Test
    fun `run merges stderr into the output`() = runBlocking {
        val result = provider().execute(request("bash__run", "command" to "echo to-err >&2"))
        assertFalse(result.isError)
        assertEquals("to-err", result.text())
    }

    @Test
    fun `run reports a non-zero exit as a normal result with an exit line`() = runBlocking {
        val result = provider().execute(request("bash__run", "command" to "echo partial; exit 3"))
        assertFalse(result.isError)
        val text = result.text()
        assertContains(text, "partial")
        assertTrue(text.endsWith("Command exited with code 3."), text)
    }

    @Test
    fun `run adds no exit line on success`() = runBlocking {
        val result = provider().execute(request("bash__run", "command" to "echo ok"))
        assertFalse(result.isError)
        assertEquals("ok", result.text())
    }

    @Test
    fun `run answers no output for a silent command`() = runBlocking {
        val result = provider().execute(request("bash__run", "command" to "true"))
        assertFalse(result.isError)
        assertEquals("(no output)", result.text())
    }

    @Test
    fun `run captures a missing command's shell error and exit code`() = runBlocking {
        val result = provider().execute(
            request("bash__run", "command" to "definitely-not-a-command-xyz")
        )
        // the shell's message is locale-dependent, so only the echoed
        // command name and the exit code are asserted
        assertFalse(result.isError)
        val text = result.text()
        assertContains(text, "definitely-not-a-command-xyz")
        assertTrue(text.endsWith("Command exited with code 127."), text)
    }

    // ---------- run: one-shot semantics and workdir ----------

    @Test
    fun `run state does not persist between calls`() = runBlocking {
        val shell = provider()
        shell.execute(request("bash__run", "command" to "cd /tmp"))
        // `pwd -P` prints the PHYSICAL directory, matching canonicalPath —
        // a logical `pwd` would keep a stat-equal $PWD and flake when the
        // process cwd is reached through a symlink
        val second = shell.execute(request("bash__run", "command" to "pwd -P"))
        val expected = File(System.getProperty("user.dir")).canonicalPath
        assertEquals(expected, second.text().trim())
    }

    @Test
    fun `run defaults to the configured workdir`() = runBlocking {
        val result = provider(workdir = tempRoot.toString())
            .execute(request("bash__run", "command" to "pwd"))
        assertEquals(tempRoot.toRealPath().toString(), result.text().trim())
    }

    @Test
    fun `run expands the home shorthand in the configured workdir`() = runBlocking {
        val result = provider(workdir = "~").execute(request("bash__run", "command" to "pwd"))
        val home = File(System.getProperty("user.home")).canonicalPath
        assertEquals(home, result.text().trim())
    }

    @Test
    fun `run expands the home shorthand in a per-call workdir`() = runBlocking {
        val result = provider().execute(
            request(
                "bash__run",
                buildJsonObject {
                    put("command", "pwd")
                    put("workdir", "~")
                }
            )
        )
        assertFalse(result.isError)
        val home = File(System.getProperty("user.home")).canonicalPath
        assertEquals(home, result.text().trim())
    }

    @Test
    fun `run honors a per-call workdir over the configured one`() = runBlocking {
        val other = createTempDirectory("daapu-bash-other")
        try {
            val result = provider(workdir = tempRoot.toString()).execute(
                request(
                    "bash__run",
                    buildJsonObject {
                        put("command", "pwd")
                        put("workdir", other.toString())
                    }
                )
            )
            assertEquals(other.toRealPath().toString(), result.text().trim())
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    @Test
    fun `run rejects a nonexistent per-call workdir`() = runBlocking {
        val result = provider().execute(
            request(
                "bash__run",
                buildJsonObject {
                    put("command", "pwd")
                    put("workdir", "/definitely/not/here")
                }
            )
        )
        assertTrue(result.isError)
        assertContains(result.text(), "workdir")
    }

    @Test
    fun `run treats a blank per-call workdir as omitted`() = runBlocking {
        // the strict textArg blank-rejects: blank = absent = the configured
        // default workdir (the tool spec's `workdir` description promises this)
        val result = provider(workdir = tempRoot.toString()).execute(
            request(
                "bash__run",
                buildJsonObject {
                    put("command", "pwd")
                    put("workdir", "   ")
                }
            )
        )
        assertFalse(result.isError)
        assertEquals(tempRoot.toRealPath().toString(), result.text().trim())
    }

    // ---------- run: argument validation ----------

    @Test
    fun `run requires a non-blank command`() = runBlocking {
        val missing = provider().execute(request("bash__run"))
        assertTrue(missing.isError)
        assertContains(missing.text(), "command is required")
        val blank = provider().execute(request("bash__run", "command" to "   "))
        assertTrue(blank.isError)
        assertContains(blank.text(), "command is required")
    }

    @Test
    fun `run rejects wrong-typed arguments (strict)`() = runBlocking {
        val result = provider().execute(
            request("bash__run", buildJsonObject { put("command", 123) })
        )
        assertTrue(result.isError)
        assertContains(result.text(), "command must be a string")
        val workdir = provider().execute(
            request(
                "bash__run",
                buildJsonObject {
                    put("command", "echo hi")
                    put("workdir", 123)
                }
            )
        )
        assertTrue(workdir.isError)
        assertContains(workdir.text(), "workdir must be a string")
    }

    // ---------- run: timeout kill ----------

    @Test
    fun `run kills a command at the timeout and keeps the partial output`() = runBlocking {
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 1).execute(
            request("bash__run", "command" to "echo started; sleep 30")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        val text = result.text()
        assertContains(text, "timed out after 1s")
        assertContains(text, "started")
        // the kill must end the call promptly, not let the sleep run out
        assertTrue(elapsedMillis < 20_000, "the call took ${elapsedMillis}ms")
    }

    @Test
    fun `run escalates to SIGKILL when the command ignores SIGTERM`() = runBlocking {
        // `trap '' TERM` makes bash (and, by inheritance, the sleep child)
        // ignore SIGTERM, forcing the grace-period SIGKILL escalation
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 1).execute(
            request("bash__run", "command" to "trap '' TERM; echo trapped; sleep 30")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        assertContains(result.text(), "timed out after 1s")
        // finishing before the grace period would mean the SIGTERM worked
        assertTrue(elapsedMillis >= 4_000, "the call took only ${elapsedMillis}ms")
        assertTrue(elapsedMillis < 20_000, "the call took ${elapsedMillis}ms")
    }

    @Test
    fun `run escalates to SIGKILL for a descendant that ignores SIGTERM after the shell exits`() = runBlocking {
        // the inner bash ignores SIGTERM while the outer shell dies from it:
        // the escalation must still reach the descendant (via the tree
        // snapshot taken before the parent exits), or the pipe the survivor
        // holds would stall the drain join and leak the drain thread
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 1).execute(
            request("bash__run", "command" to "bash -c 'trap \"\" TERM; sleep 30' & wait")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        assertContains(result.text(), "timed out after 1s")
        // a stall at the full drain join (the unfixed behavior) lands at
        // ~11s; the fixed path answers right after the grace period
        assertTrue(elapsedMillis < 8_000, "the call took ${elapsedMillis}ms")
    }

    @Test
    fun `run kills the whole process tree at the timeout`() = runBlocking {
        // the outer shell waits on a background child; the tree kill must
        // reap the child too (the command would otherwise hold the pipe
        // open and stall the drain until the sleep ran out)
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 1).execute(
            request("bash__run", "command" to "sleep 30 & wait")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        assertContains(result.text(), "timed out after 1s")
        assertTrue(elapsedMillis < 20_000, "the call took ${elapsedMillis}ms")
    }

    @Test
    fun `run answers promptly when a lingering child holds the pipe past a completed shell`() = runBlocking {
        // the shell exits immediately but the inherited-pipe child keeps
        // running for seconds: EOF never comes, so the completed path must
        // answer on its SHORT drain join (the timeout path's generous one
        // would stall the call for the full DRAIN_JOIN_MILLIS)
        val start = System.nanoTime()
        val result = provider().execute(
            request("bash__run", "command" to "sleep 5 & echo done")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertFalse(result.isError)
        assertEquals("done", result.text())
        assertTrue(elapsedMillis < 3_000, "the call took ${elapsedMillis}ms")
    }

    @Test
    fun `run caps the partial output in the timeout answer at its own char budget`() = runBlocking {
        // error results bypass the LengthSafeToolProvider cap, so the
        // echoed head must be bounded by the timeout answer itself
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 1).execute(
            request("bash__run", "command" to "head -c 8000 /dev/zero | tr '\\0' 'x'; sleep 30")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        val text = result.text()
        assertContains(text, "timed out after 1s")
        assertContains(text, "[... partial output truncated at 4000 chars ...]")
        assertTrue(elapsedMillis < 20_000, "the call took ${elapsedMillis}ms")
        assertTrue(text.length < 5_000, "the timeout message carried ${text.length} chars")
    }

    @Test
    fun `the timeout answer notes a capture-cap truncation`() = runBlocking {
        // the command floods past the capture cap AND overruns the budget:
        // the answer must carry both the char-capped head and the byte-cap
        // note (error results bypass LengthSafeToolProvider, so this note
        // would otherwise be lost)
        val result = provider(timeoutSeconds = 1, maxCaptureBytes = 10_000).execute(
            request("bash__run", "command" to "while true; do echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; done")
        )
        assertTrue(result.isError)
        val text = result.text()
        assertContains(text, "timed out after 1s")
        assertContains(text, "output truncated")
        assertContains(text, "10000 bytes")
    }

    // ---------- run: output capture cap ----------

    @Test
    fun `run caps the captured output and notes the truncation`() = runBlocking {
        val result = provider(maxCaptureBytes = 10_000).execute(
            request("bash__run", "command" to "head -c 20000 /dev/zero | tr '\\0' 'x'")
        )
        assertFalse(result.isError)
        val text = result.text()
        assertContains(text, "output truncated")
        assertTrue(text.startsWith("xxx"), "the kept head must be the command's output")
        assertTrue(text.length < 15_000, "the result must be capped, got ${text.length} chars")
    }

    @Test
    fun `run keeps draining past the cap so a firehose cannot deadlock the child`() = runBlocking {
        // an endless producer writes far past the cap: the drain must keep
        // consuming (discarding) so the child never blocks on a full pipe,
        // and only the timeout kill ends the command
        val start = System.nanoTime()
        val result = provider(timeoutSeconds = 2, maxCaptureBytes = 10_000).execute(
            request("bash__run", "command" to "while true; do echo aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa; done")
        )
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue(result.isError)
        assertContains(result.text(), "timed out after 2s")
        assertTrue(elapsedMillis < 15_000, "the call took ${elapsedMillis}ms")
    }

    // ---------- run: spawn failure ----------

    @Test
    fun `run answers an error result when the shell cannot spawn`() = runBlocking {
        // the class KDoc's error contract: a spawn failure is an `isError`
        // result the model can react to, never a thrown failure that would
        // end the hand run as `fatal`
        val result = provider(shellPath = "/definitely/not/a/shell").execute(
            request("bash__run", "command" to "echo hi")
        )
        assertTrue(result.isError)
        assertContains(result.text(), "bash tool 'bash__run' failed")
    }

    // ---------- construction fail-fast ----------

    @Test
    fun `boot fails fast on a missing default workdir`() {
        val e = assertFailsWith<IllegalArgumentException> {
            provider(workdir = "/definitely/not/here")
        }
        assertContains(e.message!!, "workdir")
    }

    @Test
    fun `boot fails fast on invalid budgets`() {
        assertFailsWith<IllegalArgumentException> { provider(shellPath = "  ") }
        assertFailsWith<IllegalArgumentException> { provider(timeoutSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { provider(maxCaptureBytes = 9_999) }
        assertFailsWith<IllegalArgumentException> { provider(maxCaptureBytes = Int.MAX_VALUE.toLong() + 1) }
    }

    // ---------- helpers ----------

    private fun provider(
        shellPath: String = "/bin/bash",
        workdir: String? = null,
        timeoutSeconds: Long = 120,
        maxCaptureBytes: Long = 1_000_000,
    ): BashToolProvider = BashToolProvider(shellPath, workdir, timeoutSeconds, maxCaptureBytes)

    private fun request(name: String, vararg args: Pair<String, String>): ToolCallRequest =
        request(name, buildJsonObject { args.forEach { (key, value) -> put(key, value) } })

    private fun request(name: String, args: JsonObject): ToolCallRequest =
        ToolCallRequest(id = "t1", name = name, args = args)

    private fun ChatMessagePart.ToolResult.text(): String =
        parts.filterIsInstance<ChatMessagePart.Text>().joinToString("") { it.text }
}

/**
 * The DI wiring: the bash provider joins the chat loop's combined tool set
 * only when `tool.bash.enabled`, the same set feeds the investigate
 * sub-agent (where the namespace stays masked unless whitelisted), and a
 * duplicate `bash` namespace (an MCP server under the same namespace) fails
 * fast.
 */
class BashToolProviderDiTest {

    @Test
    fun `the bash namespace joins the loop's tool set when enabled`() {
        val config = testAppConfig().copy(
            tool = ToolConfig(bash = BashToolConfig(enabled = true)),
        )
        val app = testKoinApp(config)
        try {
            assertTrue("bash" in app.koin.get<CombinedToolProvider>().namespaces())
        } finally {
            app.close()
        }
    }

    @Test
    fun `the bash namespace is whitelistable for the investigator when enabled`() {
        // whitelisting `bash` for the sub-agent fails fast unless the
        // investigator's OWN combined set serves it (the second
        // registration site in AppModule), so resolving the graph pins that
        // site the loop-set test above cannot reach
        val config = testAppConfig().copy(
            tool = ToolConfig(bash = BashToolConfig(enabled = true)),
            agent = AgentConfig(
                investigator = InvestigatorConfig(
                    model = "bifrost/cerebras/gemma-4-31b",
                    allowedNamespaces = listOf("eltm", "bash"),
                )
            ),
        )
        val app = testKoinApp(config)
        try {
            val investigator = app.koin.get<InvestigatorService>()
            assertNotNull(investigator)
        } finally {
            app.close()
        }
    }

    @Test
    fun `the bash namespace is absent when disabled`() {
        val app = testKoinApp()
        try {
            assertFalse("bash" in app.koin.get<CombinedToolProvider>().namespaces())
        } finally {
            app.close()
        }
    }

    @Test
    fun `a duplicate bash namespace fails fast in the combined provider`() {
        val bashProvider = BashToolProvider("/bin/bash", null, 120, 1_000_000)
        val fakeMcpBash = object : ToolProvider {
            override suspend fun specifications() = emptyList<ToolSpec>()
            override suspend fun execute(request: ToolCallRequest) = error("unused")
            override fun namespaces() = setOf("bash")
        }
        assertFails { CombinedToolProvider(listOf(bashProvider, fakeMcpBash)) }
    }
}
