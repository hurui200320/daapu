package info.skyblond.daapu.mcp

import info.skyblond.daapu.config.McpServerConfig
import info.skyblond.daapu.config.McpTransportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the stdio subprocess lifecycle of [ClientEntry]: a subprocess is
 * owned from spawn on — a silent server under an initialization budget
 * fails fast with the ACTUAL process killed (PID-verified via
 * [ProcessHandle], not just object-level cleanup), every retried attempt
 * destroys its own subprocess, a cancelled stuck connect unwinds promptly
 * (its handshake await is cancellable and the catch kills the process)
 * instead of hanging until the process's own death, a drop destroys
 * the subprocess before closing the client (the SDK close would otherwise
 * block on the pipe read until EOF), and the closed gate ends the
 * lifecycle: closing during a retrying connect (or connecting after the
 * close) stops at once instead of waiting out the retry budget — or, with
 * no initialization timeout, hanging forever.
 *
 * The silent mock self-limits (default ~2 minutes, seconds via the
 * transport's environment): every assertion here must finish far below
 * that — without the ownership fix these tests degrade into slow
 * failures, not hangs. One test composes the mock's SIGTERM-ignoring
 * mode with the silent mode to pin the force-kill escalation: the grace
 * period must actually elapse, and the kill must stay bounded.
 */
class StdioClientLifecycleTest {

    @Test
    fun `an initialization timeout with a silent server fails fast and kills the subprocess`() {
        val pidFile = tempPidFile()
        val entry = silentEntry(pidFile, initializationTimeoutSeconds = 2, reconnectAttempts = 1)
        try {
            val start = System.currentTimeMillis()
            val failure = assertFailsWith<McpTransportException> {
                runBlocking { entry.getConnectedClient() }
            }
            val elapsed = System.currentTimeMillis() - start
            // the await times out at the 2s budget and the catch destroys
            // the silent subprocess; the abandoned handshake job's close
            // (blocked on the pipe read until then) follows on its own: the
            // whole failure must land far below the process's own
            // ~2-minute lifetime
            assertTrue(
                failure.message.orEmpty().contains("local"),
                "the failure must name the server: ${failure.message}"
            )
            assertTrue(elapsed < 10_000, "initialization timeout must fail fast, took ${elapsed}ms")
            assertPidsDead(pidFile, expectedCount = 1)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `retried connect attempts destroy every subprocess they spawned`() {
        val pidFile = tempPidFile()
        // a timeout must flow through the reconnect policy as a transport
        // failure (not as bare cancellation, which skips the retries): two
        // attempts spawn — and destroy — two subprocesses
        val entry = silentEntry(pidFile, initializationTimeoutSeconds = 2, reconnectAttempts = 2)
        try {
            val failure = assertFailsWith<McpTransportException> {
                runBlocking { entry.getConnectedClient() }
            }
            assertTrue(
                failure.message.orEmpty().contains("2 reconnect attempts"),
                "both attempts must be reported: ${failure.message}"
            )
            assertPidsDead(pidFile, expectedCount = 2)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `a SIGTERM-ignoring subprocess is reaped by the bounded force-kill escalation`() {
        val pidFile = tempPidFile()
        val entry = ignoreTermSilentEntry(pidFile)
        try {
            val start = System.currentTimeMillis()
            val failure = assertFailsWith<McpTransportException> {
                runBlocking { entry.getConnectedClient() }
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                failure.message.orEmpty().contains("local"),
                "the failure must name the server: ${failure.message}"
            )
            // the await times out at the 2s budget; destroyWithEscalation
            // then waits out the FULL 2s grace (the mock's shutdown hook
            // keeps the JVM alive through the SIGTERM) before the force
            // kill reaps it at once — an unbounded wait, or a kill without
            // escalation, shows up here as a timing miss
            assertTrue(elapsed >= 3_900, "the grace period must be awaited, took ${elapsed}ms")
            assertTrue(elapsed < 15_000, "the escalation must stay bounded, took ${elapsed}ms")
            assertPidsDead(pidFile, expectedCount = 1)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `dropConnection destroys the connected subprocess before closing`() {
        val pidFile = tempPidFile()
        val entry = healthyEntry(pidFile)
        try {
            runBlocking { withTimeout(30_000) { entry.getConnectedClient() } }
            val pid = pidFile.readLines().single().toLong()
            assertTrue(
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
                "the connected subprocess must be alive before the drop"
            )
            val start = System.currentTimeMillis()
            runBlocking { entry.dropConnection() }
            val elapsed = System.currentTimeMillis() - start
            // destroy-first: the SDK close that follows sees EOF at once —
            // the drop must not hang on the live (idle, pipe-silent) process
            assertTrue(elapsed < 10_000, "the drop must be prompt, took ${elapsed}ms")
            assertPidsDead(pidFile, expectedCount = 1)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `cancelling a stuck connect unwinds promptly with the subprocess dead`() {
        val pidFile = tempPidFile()
        val entry = silentEntry(pidFile, initializationTimeoutSeconds = 2, reconnectAttempts = 3)
        try {
            val start = System.currentTimeMillis()
            val failure = assertFailsWith<CancellationException> {
                // outer cancellation mid-handshake: the await returns at
                // once (it is cancellable) and its catch kills the process
                // immediately — the abandoned handshake job's close then
                // unblocks on the pipe EOF; nothing waits for the process's
                // own ~2-minute lifetime
                runBlocking { withTimeout(200) { entry.getConnectedClient() } }
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                failure.message.orEmpty().contains("200"),
                "the outer timeout must surface, got: ${failure.message}"
            )
            assertTrue(elapsed < 10_000, "cancellation must unwind promptly, took ${elapsed}ms")
            assertPidsDead(pidFile, expectedCount = 1)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `closing during a retrying silent connect returns promptly and stops the retries`() {
        val pidFile = tempPidFile()
        // NO initialization timeout (the documented "no timeout" state):
        // an attempt's await never ends on its own, so ONLY the closed gate
        // can end the loop — close() must not wait out the retry budget
        val entry = silentEntry(
            pidFile,
            initializationTimeoutSeconds = null,
            reconnectAttempts = 3,
            // outlives the healthy test (~seconds) by far; caps a gate
            // regression at ~seconds per retry instead of ~2 minutes
            silentSeconds = 30,
        )
        try {
            runBlocking {
                supervisorScope {
                    val connect = async(Dispatchers.IO) { entry.getConnectedClient() }
                    // let attempt 1 spawn and register before closing
                    awaitPidRegistrations(pidFile, expectedCount = 1)
                    val start = System.currentTimeMillis()
                    entry.close()
                    val elapsed = System.currentTimeMillis() - start
                    // the gate stops the loop at its next re-check: close
                    // waits only for the swept attempt to unwind — never
                    // for the remaining retry budget (or the process's own
                    // silent lifetime)
                    assertTrue(elapsed < 10_000, "close must return promptly, took ${elapsed}ms")
                    val failure = assertFailsWith<McpTransportException> { connect.await() }
                    assertTrue(
                        failure.message.orEmpty().contains("closed"),
                        "the aborted connect must report the closed entry: ${failure.message}"
                    )
                }
            }
            // exactly ONE subprocess: the gate stopped the retries before
            // attempt 2 could spawn one
            assertPidsDead(pidFile, expectedCount = 1)
        } finally {
            entry.close()
        }
    }

    @Test
    fun `a connect after close fails fast without spawning a subprocess`() {
        val pidFile = tempPidFile()
        // with NO initialization timeout a post-close connect could never
        // finish (the cancelled handshakeScope never completes a
        // handshake): the closed gate must fail it before any spawn — the
        // withTimeout keeps a gate regression a slow failure, not a hang
        val entry = silentEntry(
            pidFile,
            initializationTimeoutSeconds = null,
            reconnectAttempts = 3,
        )
        try {
            entry.close()
            val start = System.currentTimeMillis()
            val failure = assertFailsWith<McpTransportException> {
                runBlocking { withTimeout(30_000) { entry.getConnectedClient() } }
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                failure.message.orEmpty().contains("closed"),
                "the failure must report the closed entry: ${failure.message}"
            )
            assertTrue(elapsed < 1_000, "a closed entry must fail fast, took ${elapsed}ms")
            assertTrue(pidFile.readLines().isEmpty(), "a closed entry must not spawn a subprocess")
        } finally {
            entry.close()
        }
    }

    @Test
    fun `a healthy connect completes within its budget and keeps the subprocess alive`() {
        val pidFile = tempPidFile()
        val entry = healthyEntry(pidFile)
        try {
            runBlocking { withTimeout(30_000) { entry.getConnectedClient() } }
            val pid = pidFile.readLines().single().toLong()
            // a successful handshake owns the process without touching it:
            // it must stay alive until the drop — overeager failure cleanup
            // would kill it at the budget
            assertTrue(
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false),
                "the connected subprocess must stay alive (watchdog disarmed on success)"
            )
            assertEquals(1, pidFile.readLines().size, "exactly one spawned subprocess")
        } finally {
            entry.close()
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun tempPidFile(): File = File.createTempFile("mcp-stdio-pid", ".txt").apply {
        deleteOnExit()
    }

    private fun stdioMockCommand(): List<String> = listOf(
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
        "-cp",
        System.getProperty("java.class.path"),
        "info.skyblond.daapu.mcp.StdioMockMcpMainKt",
    )

    /** A silent-server entry: never handshakes, self-reports its PID. */
    private fun silentEntry(
        pidFile: File,
        initializationTimeoutSeconds: Long?,
        reconnectAttempts: Int,
        silentSeconds: Int = 120,
    ): ClientEntry = ClientEntry(
        namespace = "local",
        config = McpServerConfig(
            type = McpTransportType.Stdio,
            command = stdioMockCommand(),
            environment = mapOf(
                "MCP_STDIO_PID_FILE" to pidFile.absolutePath,
                "MCP_STDIO_SILENT" to "1",
                "MCP_STDIO_SILENT_SECONDS" to silentSeconds.toString(),
            ),
            initializationTimeoutSeconds = initializationTimeoutSeconds,
            reconnectAttempts = reconnectAttempts,
            reconnectDelayMs = 50,
            toolExecutionTimeoutSeconds = 0,
        ),
    )

    /**
     * A silent entry whose subprocess also ignores a graceful SIGTERM (a
     * shutdown hook blocks the JVM's exit): only the force-kill
     * escalation can reap it.
     */
    private fun ignoreTermSilentEntry(pidFile: File): ClientEntry = ClientEntry(
        namespace = "local",
        config = McpServerConfig(
            type = McpTransportType.Stdio,
            command = stdioMockCommand(),
            environment = mapOf(
                "MCP_STDIO_PID_FILE" to pidFile.absolutePath,
                "MCP_STDIO_SILENT" to "1",
                "MCP_STDIO_IGNORE_TERM" to "1",
            ),
            initializationTimeoutSeconds = 2,
            reconnectAttempts = 1,
            reconnectDelayMs = 50,
            toolExecutionTimeoutSeconds = 0,
        ),
    )

    /** A healthy mock-server entry that self-reports its PID. */
    private fun healthyEntry(pidFile: File): ClientEntry = ClientEntry(
        namespace = "local",
        config = McpServerConfig(
            type = McpTransportType.Stdio,
            command = stdioMockCommand(),
            environment = mapOf("MCP_STDIO_PID_FILE" to pidFile.absolutePath),
            initializationTimeoutSeconds = 30,
            reconnectAttempts = 1,
            toolExecutionTimeoutSeconds = 0,
        ),
    )

    /**
     * Waits for [expectedCount] PID registrations to land in [pidFile] (a
     * child JVM needs a moment to boot before its first line lands) and
     * returns them.
     */
    private fun awaitPidRegistrations(pidFile: File, expectedCount: Int): List<Long> {
        val registrationDeadline = System.currentTimeMillis() + 10_000
        var lines = pidFile.readLines()
        while (System.currentTimeMillis() < registrationDeadline && lines.size < expectedCount) {
            Thread.sleep(50)
            lines = pidFile.readLines()
        }
        assertEquals(expectedCount, lines.size, "every attempt must register its subprocess pid")

        val pids = lines.mapNotNull { it.toLongOrNull() }
        assertEquals(expectedCount, pids.size, "registered lines must be pids")
        return pids
    }

    /**
     * Asserts every subprocess registered in [pidFile] is terminated:
     * waits for the expected registrations, then polls [ProcessHandle]
     * until the OS reports each process dead.
     */
    private fun assertPidsDead(pidFile: File, expectedCount: Int) {
        val pids = awaitPidRegistrations(pidFile, expectedCount)
        val deathDeadline = System.currentTimeMillis() + 5_000
        for (pid in pids) {
            var alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            while (System.currentTimeMillis() < deathDeadline && alive) {
                Thread.sleep(50)
                alive = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }
            assertTrue(!alive, "spawned subprocess $pid must be terminated")
        }
    }
}
