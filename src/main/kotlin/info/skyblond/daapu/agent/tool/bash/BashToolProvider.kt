package info.skyblond.daapu.agent.tool.bash

import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.agent.tool.errorResult
import info.skyblond.daapu.agent.tool.expandHomePath
import info.skyblond.daapu.agent.tool.nsToolName
import info.skyblond.daapu.agent.tool.objectSchema
import info.skyblond.daapu.agent.tool.splitNsToolName
import info.skyblond.daapu.agent.tool.stringSchema
import info.skyblond.daapu.agent.tool.textArg
import info.skyblond.daapu.agent.tool.textResult
import info.skyblond.daapu.agent.tool.validateToolNamespaceSyntax
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The hardcoded namespace of the bash tool provider ([BashToolProvider]):
 * the advertised tool name becomes `bash__run`. The namespace is NOT
 * configurable, and it cannot coexist with an MCP server under the same
 * `bash` namespace (enabling both fails fast at boot on the duplicate
 * namespace in `CombinedToolProvider` — see `config.example.jsonc`).
 */
const val BASH_NAMESPACE: String = "bash"

/**
 * The bash tool: runs an arbitrary command string as `<shellPath> -c
 * <command>` (a fresh, one-shot shell per call — no state carries over)
 * and answers with the merged stdout+stderr. Configured under `tool.bash`
 * (`BashToolConfig`); the SECURITY warning lives there and in
 * `config.example.jsonc`: the commands run with the brain process's own
 * privileges and there is NO sandboxing at this layer — the tool must only
 * be enabled where the brain itself is isolated (a container/VM; never on
 * bare metal).
 *
 * Semantics (mirroring opencode's bash tool where it matters):
 *
 * - stdin is `/dev/null`: a command waiting on interactive input sees EOF
 *   instead of hanging on the pipe,
 * - stdout and stderr are merged into one stream, like the child's terminal,
 * - a command that overruns `tool.bash.timeoutSeconds` has its whole
 *   process tree killed (SIGTERM, then an unconditional SIGKILL
 *   escalation — the grace semantics live on [killTree]) and the tool
 *   answers an `isError` timeout result with the output captured
 *   so far. The kill walks `ProcessHandle.descendants()` because the JDK
 *   cannot spawn into a process group (opencode's `detached` + group
 *   kill); a command that daemonizes by double-forking escapes it — the
 *   container isolation absorbs that,
 * - the captured output is capped at `tool.bash.maxCaptureBytes`: the
 *   excess is drained and DISCARDED (the head is kept, like opencode's
 *   in-memory capture limit), and the result notes the truncation. The
 *   drain runs for the command's whole lifetime regardless of the cap —
 *   a full pipe buffer would otherwise block the child forever,
 * - a shell that exits while a background child still holds the output
 *   pipe (a daemonized or disowned command) never lets the drain reach
 *   EOF: the drain join bounds the stall — short for a completed shell
 *   ([COMPLETED_DRAIN_JOIN_MILLIS]: its own output is already captured
 *   at exit), generous after a timeout kill ([DRAIN_JOIN_MILLIS]) — the
 *   abandoned drain thread lingers (daemon) until the child exits, and
 *   the child's output past the snapshot is lost — the one-shot shell
 *   trade-off,
 * - a non-zero exit is a NORMAL result (the output stays usable for the
 *   model), with a trailing `Command exited with code N.` line; a zero
 *   exit adds no line.
 *
 * The budget is enforced HERE, not by the route: the blocking process
 * wait holds no suspension point, so the route's `withTimeout`
 * ([HandCallbackService]) cannot fire while it blocks — a cancelled
 * coroutine would leave the child running, and the route's timeout
 * answer can only replace a block that RETURNS after the budget. The
 * internal waits are all bounded (the wait, the kill grace, the drain
 * join), so the internal enforcement always fires first and answers
 * with the partial output; [ToolProvider.executionTimeoutSeconds]
 * answers `timeoutSeconds + 30` as that late-return backstop — never a
 * rescue for a permanently wedged block.
 *
 * Error contract: an invalid argument or an unusable workdir answers an
 * `isError` result (the model can react); [CancellationException] is
 * rethrown; everything else (a spawn failure, I/O races) is warn-logged
 * and answered as an `isError` — never a thrown transport failure (this
 * provider has no transport). All I/O runs on the hand callback route's
 * `Dispatchers.IO` ([HandCallbackService] wraps `execute`).
 */
class BashToolProvider(
    private val shellPath: String,
    workdir: String?,
    private val timeoutSeconds: Long,
    private val maxCaptureBytes: Long,
) : ToolProvider {
    private val defaultWorkdir: File?
    private val specs: List<ToolSpec>

    init {
        validateToolNamespaceSyntax(BASH_NAMESPACE, "bash tool")
        require(shellPath.isNotBlank()) {
            "tool.bash.shellPath must not be blank: there is no shell to run commands with"
        }
        require(timeoutSeconds >= 1) {
            "tool.bash.timeoutSeconds must be >= 1, got $timeoutSeconds"
        }
        require(maxCaptureBytes >= MIN_CAPTURE_BYTES) {
            "tool.bash.maxCaptureBytes must be >= $MIN_CAPTURE_BYTES, got $maxCaptureBytes"
        }
        require(maxCaptureBytes <= Int.MAX_VALUE) {
            "tool.bash.maxCaptureBytes must fit an Int (<= ${Int.MAX_VALUE}), got $maxCaptureBytes"
        }
        // canonicalize + fail fast at boot: a typo'd default workdir must
        // abort startup, not surface as a per-command error later
        defaultWorkdir = workdir?.let { raw ->
            val canonical = File(expandHomePath(raw)).canonicalFile
            require(canonical.isDirectory) {
                "tool.bash.workdir '$raw' does not exist or is not a directory"
            }
            canonical
        }
        logger.warn {
            "bash tool enabled: commands run with this process's own privileges and NO " +
                    "sandboxing — the brain must run inside an isolated environment (container/VM)"
        }
        specs = listOf(
            ToolSpec(
                name = nsToolName(BASH_NAMESPACE, "run"),
                description = "Run a command in a shell (`$shellPath -c <command>`) and return its combined stdout and stderr. " +
                        "Each call spawns a FRESH, non-interactive shell: no state (current directory, environment variables, shell options) carries over between calls — use absolute paths or pass 'workdir' instead of relying on 'cd'. " +
                        "stdin is /dev/null, so a command waiting for interactive input sees EOF instead. " +
                        "A non-zero exit is reported as a trailing 'Command exited with code N.' line (a zero exit adds no line). " +
                        "The command is killed when it overruns the ${timeoutSeconds}s budget (the tool answers a timeout error result). " +
                        "Output is capped at the first $maxCaptureBytes bytes.",
                schema = objectSchema(
                    required = listOf("command"),
                    "command" to stringSchema("The shell command to run, e.g. 'ls -la /tmp'."),
                    "workdir" to stringSchema(
                        "Optional working directory for the command (an existing directory path). " +
                                "Use this instead of 'cd'; defaults to the configured default workdir or the process's working directory. " +
                                "A blank value is treated as if the argument was omitted."
                    ),
                ),
            ),
        )
    }

    override fun namespaces(): Set<String> = setOf(BASH_NAMESPACE)

    override suspend fun specifications(): List<ToolSpec> = specs

    // only the advertised `bash__run` name carries the backstop budget:
    // unknown names answer 0 per the ToolProvider contract
    override fun executionTimeoutSeconds(toolName: String): Long =
        when (splitNsToolName(toolName)?.takeIf { it.first == BASH_NAMESPACE }?.second) {
            "run" -> timeoutSeconds + BACKSTOP_MARGIN_SECONDS
            else -> 0
        }

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val name = splitNsToolName(request.name)
            ?.takeIf { it.first == BASH_NAMESPACE }?.second
        if (name == null) {
            return errorResult(
                request, "tool '${request.name}' is not advertised by this bash provider"
            )
        }
        logger.info { "Executing tool ${request.name} with args ${request.args}" }
        return try {
            when (name) {
                "run" -> run(request)
                else -> errorResult(
                    request, "tool '${request.name}' is not advertised by this bash provider"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            errorResult(request, e.message ?: "illegal argument")
        } catch (e: Exception) {
            logger.warn(e) { "Unexpected bash tool failure on ${request.name}" }
            errorResult(request, "bash tool '${request.name}' failed: ${e.message}")
        }
    }

    // ---------- the tool ----------

    private fun run(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val command = request.args.textArg("command", strict = true) ?: return errorResult(
            request, "command is required and must not be blank"
        )
        val workdir = resolveWorkdir(request.args.textArg("workdir", strict = true))
        return when (val outcome = runCommand(command, workdir)) {
            is CommandOutcome.Completed -> completedResult(request, outcome)
            is CommandOutcome.TimedOut -> timedOutResult(request, outcome)
        }
    }

    /**
     * The command's working directory: a per-call [requested] path wins
     * over the configured default (`tool.bash.workdir`, validated at
     * boot); null = inherit the brain process's working directory. A blank
     * per-call value never reaches here (the strict `textArg` reads it as
     * absent — the tool spec's `workdir` description promises the same).
     * Throws [IllegalArgumentException] with the model-visible reason;
     * the caller catches it as an `isError` result.
     */
    private fun resolveWorkdir(requested: String?): File? {
        val raw = requested ?: return defaultWorkdir
        val canonical = File(expandHomePath(raw)).canonicalFile
        if (!canonical.isDirectory) {
            throw IllegalArgumentException("workdir '$raw' does not exist or is not a directory")
        }
        return canonical
    }

    /**
     * The blocking core: spawn the one-shot shell, drain its merged
     * output (capped), wait the budget, kill the tree on overrun.
     * Blocking ON PURPOSE — it runs inside the hand callback route's
     * `Dispatchers.IO` and holds no suspension point, so the route's
     * backstop `withTimeout` can never abandon a half-dead process: the
     * internal budget always fires first and cleans up.
     */
    private fun runCommand(command: String, workdir: File?): CommandOutcome {
        val process = ProcessBuilder(shellPath, "-c", command).apply {
            workdir?.let { directory(it) }
            // stdin from /dev/null (a stdin-reading command sees EOF, never
            // an interactive prompt) and stderr merged into stdout
            redirectInput(ProcessBuilder.Redirect.from(DEV_NULL))
            redirectErrorStream(true)
        }.start()
        val capture = OutputCapture(maxCaptureBytes)
        val drain = thread(start = true, isDaemon = true, name = "bash-tool-drain") {
            capture.drain(process.inputStream)
        }
        val exited = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // the wait itself was interrupted (no current call site
            // interrupts a tool thread, but the cleanup must not rely on
            // that): restore the flag and treat it as an overrun — the
            // !exited branch kills the tree, and the timeout answer
            // stands in for a result
            Thread.currentThread().interrupt()
            false
        }
        if (!exited) killTree(process)
        // EOF lands right after the exit/kill; the join only bounds the
        // pathological stall where it does NOT come: a lingering background
        // child holds the pipe open past the shell's own exit (the escaped-
        // daemon caveat in the class KDoc). A completed shell's own output
        // is already buffered or drained at exit, so its join is short; the
        // generous one stays with the timeout kill, where only an escaped
        // daemon can hold the pipe. The snapshot lock makes a still-running
        // drain thread harmless either way.
        drain.join(if (exited) COMPLETED_DRAIN_JOIN_MILLIS else DRAIN_JOIN_MILLIS)
        val snapshot = capture.snapshot()
        return if (exited) {
            CommandOutcome.Completed(process.exitValue(), snapshot.text, snapshot.truncated)
        } else {
            CommandOutcome.TimedOut(snapshot.text, snapshot.truncated)
        }
    }

    /**
     * Kill the command's whole process tree: SIGTERM to the descendants
     * and the shell (the direct child) first, then SIGKILL to whoever
     * remains. The SIGTERM grace wait is the SHELL's alone: the `finally`
     * escalation fires as soon as the shell dies — usually instantly — so
     * the descendants get only that window to exit gracefully, never the
     * full grace period; a descendant that ignores SIGTERM is SIGKILLed by
     * the `finally` whether the shell honored the SIGTERM or needed the
     * escalation (the pre-kill snapshot below is what reaches it after the
     * shell is gone). Deliberate: the budget has already elapsed, so a
     * bounded prompt kill beats a graceful one. The escalation is
     * UNCONDITIONAL: a failed tree enumeration or an interrupt landing
     * inside the grace wait must never let a SIGTERM-ignoring process
     * outlive the budget. See the class KDoc for the escaped-daemon caveat.
     */
    private fun killTree(process: Process) {
        // snapshot the tree BEFORE the kill: once the parent exits, its
        // children are reparented to init and `ProcessHandle.descendants()`
        // can no longer enumerate them — the escalation below must reuse
        // this snapshot to reach a surviving descendant. Enumeration is
        // failure-tolerant: with an empty snapshot the process itself
        // still gets the full SIGTERM → SIGKILL treatment.
        val descendants = runCatching { process.descendants().toList() }
            .getOrDefault(emptyList())
        try {
            descendants.forEach { it.destroy() }
            process.destroy()
            if (!process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        } finally {
            // SIGKILL whoever survived the SIGTERM, on EVERY path
            // (destroyForcibly on an already-dead handle is a no-op); the
            // caller's drain join bounds the wait — SIGKILL cannot be
            // ignored
            descendants.forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    private fun completedResult(
        request: ToolCallRequest,
        outcome: CommandOutcome.Completed,
    ): ChatMessagePart.ToolResult {
        val segments = mutableListOf<String>()
        if (outcome.output.isNotBlank()) segments += outcome.output.removeSuffix("\n")
        if (outcome.truncated) segments += truncationNote()
        if (outcome.exitCode != 0) segments += "Command exited with code ${outcome.exitCode}."
        val text = segments.joinToString("\n")
        return textResult(request, text.ifEmpty { "(no output)" })
    }

    /** The capture-cap truncation note, shared by both result shapes. */
    private fun truncationNote(): String =
        "[output truncated: the capture cap of $maxCaptureBytes bytes was reached; the rest was discarded]"

    /**
     * The budget answer: an `isError` result (the model must react) with
     * the output captured before the kill. Error results bypass the
     * `LengthSafeToolProvider` cap, so the partial output is bounded here:
     * the echoed head is char-capped ([TIMEOUT_OUTPUT_CHARS]), and BOTH
     * truncations are noted — the char cut and the capture's byte cap
     * ([CommandOutcome.TimedOut.partialTruncated], see [OutputCapture]).
     */
    private fun timedOutResult(
        request: ToolCallRequest,
        outcome: CommandOutcome.TimedOut,
    ): ChatMessagePart.ToolResult {
        val partial = outcome.partialOutput.take(TIMEOUT_OUTPUT_CHARS).let {
            // the cut can land inside a surrogate pair: drop the dangling
            // high half like LengthSafeToolProvider's truncation does
            if (it.isNotEmpty() && it.last().isHighSurrogate() &&
                it.length < outcome.partialOutput.length &&
                outcome.partialOutput[it.length].isLowSurrogate()
            ) it.dropLast(1) else it
        }
        val message = buildString {
            append("command timed out after ${timeoutSeconds}s and was killed")
            if (partial.isBlank()) {
                append(" (no output was captured before the timeout)")
            } else {
                append("\n--- output before the timeout ---\n")
                append(partial)
                if (outcome.partialOutput.length > partial.length) {
                    append("\n[... partial output truncated at $TIMEOUT_OUTPUT_CHARS chars ...]")
                }
            }
            if (outcome.partialTruncated) append("\n${truncationNote()}")
        }
        return errorResult(request, message)
    }

    /**
     * The outcome of [runCommand]: the process exited on its own, or the
     * budget killed it.
     */
    private sealed interface CommandOutcome {
        /**
         * The command finished on its own; [exitCode] may be non-zero —
         * still a normal result (see [completedResult]).
         */
        data class Completed(val exitCode: Int, val output: String, val truncated: Boolean) : CommandOutcome

        /**
         * The budget elapsed and the tree is dead; [partialOutput] is
         * whatever the capture held by then, and [partialTruncated]
         * whether the byte cap was reached ([timedOutResult] notes it).
         */
        data class TimedOut(val partialOutput: String, val partialTruncated: Boolean) : CommandOutcome
    }

    /**
     * The bounded, thread-safe capture of the child's merged output: the
     * drain thread appends until the byte cap, then keeps draining but
     * discards (the child must never block on a full pipe, whatever the
     * cap), and the caller snapshots after the exit/kill. Bytes are kept
     * verbatim and decoded once at the snapshot, so a cut between a UTF-8
     * sequence's bytes only costs one replacement char.
     */
    private class OutputCapture(private val maxBytes: Long) {
        private val buffer = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY_BYTES).toInt())

        @Volatile
        private var truncated = false

        /** The drain loop; runs on the dedicated daemon thread. */
        fun drain(input: InputStream) {
            input.use { stream ->
                val chunk = ByteArray(DRAIN_CHUNK_BYTES)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) return
                    synchronized(buffer) {
                        if (buffer.size() >= maxBytes) {
                            truncated = true
                        } else {
                            val keep = minOf(read.toLong(), maxBytes - buffer.size()).toInt()
                            buffer.write(chunk, 0, keep)
                            if (keep < read) truncated = true
                        }
                    }
                }
            }
        }

        fun snapshot(): Snapshot = synchronized(buffer) {
            Snapshot(buffer.toString(Charsets.UTF_8), truncated)
        }

        class Snapshot(val text: String, val truncated: Boolean)
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The redirect source for stdin: reads answer EOF immediately. */
        private val DEV_NULL = File("/dev/null")

        private const val DRAIN_CHUNK_BYTES = 8 * 1024

        private const val INITIAL_CAPACITY_BYTES = 64 * 1024L

        /**
         * The shortest usable capture cap: below this the tool would
         * truncate nearly every result into uselessness (mirrored by
         * `BashToolConfig.validate`).
         */
        private const val MIN_CAPTURE_BYTES = 10_000L

        /**
         * How long the drain thread may keep running past the snapshot
         * after a timeout kill: the killed tree EOFs the pipe within
         * milliseconds (SIGKILL cannot be ignored), so the join only
         * bounds the stall of a daemon that escaped the tree kill and
         * still holds the pipe. A shell that exited on its own uses the
         * shorter [COMPLETED_DRAIN_JOIN_MILLIS].
         */
        private const val DRAIN_JOIN_MILLIS = 10_000L

        /**
         * The short drain join for a shell that exited on its own: its
         * own output is already buffered or drained at exit, so EOF
         * lands within milliseconds — the join only bounds the stall of
         * a lingering background child holding the pipe (see the class
         * KDoc), without taxing the common case with the full
         * [DRAIN_JOIN_MILLIS].
         */
        private const val COMPLETED_DRAIN_JOIN_MILLIS = 1_000L

        /** The SIGTERM grace period before the tree is SIGKILLed. */
        private const val KILL_GRACE_SECONDS = 5L

        /**
         * The head of the partial output a timeout answer carries, bounded
         * because error results bypass the `LengthSafeToolProvider` cap.
         */
        private const val TIMEOUT_OUTPUT_CHARS = 4_000

        /**
         * The margin between the internally enforced budget and the route
         * backstop ([executionTimeoutSeconds]): covers the kill grace
         * period and the drain join.
         */
        private const val BACKSTOP_MARGIN_SECONDS = 30L
    }
}
