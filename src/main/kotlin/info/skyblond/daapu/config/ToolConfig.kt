package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The tool provider settings, the harness-owned tools that are not MCP
 * servers: the read-only filesystem provider (`tool.fs`, the replacement
 * for the vanilla filesystem MCP server's read-only half — that server has
 * no read-only mode) and the bash tool (`tool.bash`).
 */
@Serializable
data class ToolConfig(
    /** The read-only filesystem tool provider, see [FsToolConfig]. */
    val fs: FsToolConfig = FsToolConfig(),
    /** The bash tool provider, see [BashToolConfig]. */
    val bash: BashToolConfig = BashToolConfig(),
) {
    fun validate() {
        fs.validate()
        bash.validate()
    }
}

/**
 * The read-only filesystem tool provider (`agent/tool/filesystem/
 * FsToolProvider.kt`): a local mock of the read-only tools of the vanilla
 * filesystem MCP server, restricted to [allowedDirs] with [blacklists] glob
 * patterns on top (e.g. any `.env` file at any depth, anything under
 * `secrets` — blob/gitignore-style patterns). Its namespace is hardcoded to
 * `fs` (advertised names
 * `fs__read_text_file`, ...), so it cannot coexist with an MCP server under
 * the same namespace: a user who wants read-write access uses the vanilla
 * filesystem MCP server instead and keeps this provider disabled — enabling
 * both fails fast at boot on the duplicate namespace.
 *
 * All fields are ignored while [enabled] is false.
 */
@Serializable
data class FsToolConfig(
    /** Whether the provider is built into the chat loop's (and the investigate sub-agent's) tool set. */
    val enabled: Boolean = false,
    /**
     * The directories the LLM may access, REQUIRED non-empty when enabled.
     * `~` is expanded; each directory must exist and be a directory at boot
     * (the provider canonicalizes them and fails fast otherwise — a typo'd
     * path must abort startup, not silently expose nothing).
     */
    val allowedDirs: List<String> = emptyList(),
    /**
     * Glob patterns (blob/gitignore syntax, e.g. any `.env` file at any
     * depth or anything under `secrets`) matched against the paths relative
     * to the allowed directories. A blacklisted path is refused as the
     * TARGET of a tool call (reading a blocked file, listing a blocked
     * folder, ...); listing and search results that merely contain
     * blacklisted entries are returned as-is. Blank patterns are rejected
     * (a blank pattern matches everything).
     */
    val blacklists: List<String> = emptyList(),
) {
    fun validate() {
        if (!enabled) return
        require(allowedDirs.isNotEmpty()) {
            "tool.fs.allowedDirs must not be empty when tool.fs.enabled is true: the LLM would have no directory to access"
        }
        require(allowedDirs.none { it.isBlank() }) {
            "tool.fs.allowedDirs must not contain blank entries"
        }
        require(blacklists.none { it.isBlank() }) {
            "tool.fs.blacklists must not contain blank entries (a blank pattern matches everything)"
        }
    }
}

/**
 * The bash tool provider (`agent/tool/bash/BashToolProvider.kt`): the LLM
 * may run arbitrary shell commands as `<shellPath> -c <command>` with the
 * brain process's own privileges and user.
 *
 * SECURITY: disabled by default, and for good reason — a shell tool is a
 * full system-access tool. There is NO sandboxing, NO allowlist and NO
 * permission prompt at this layer (the namespace can be masked per persona
 * via the tool-namespace whitelist, but any persona that serves `bash`
 * serves arbitrary commands): the container IS the sandbox. Enable it ONLY
 * when the brain runs inside an isolated environment whose blast radius
 * you accept (a container or VM — the shipped Dockerfile runs the brain as
 * root on a toolbox base exactly for this), and NEVER when the brain runs
 * on bare metal or shares an environment with anything you care about.
 *
 * All fields are ignored while [enabled] is false.
 */
@Serializable
data class BashToolConfig(
    /**
     * Whether the provider is built into the chat loop's tool set — and
     * into the investigate sub-agent's combined set, where the namespace
     * stays masked unless `agent.investigator.allowedNamespaces` lists
     * `bash` (exactly like the fs provider).
     */
    val enabled: Boolean = false,
    /** The shell executable, spawned as `<shellPath> -c <command>`. */
    val shellPath: String = "/bin/bash",
    /**
     * The default working directory for commands; null = the brain
     * process's own working directory. Must exist and be a directory at
     * boot (the provider canonicalizes it and fails fast otherwise). A
     * per-call `workdir` argument overrides it.
     */
    val workdir: String? = null,
    /**
     * The per-command execution budget in seconds: when a command
     * overruns, its whole process tree is killed (SIGTERM, then SIGKILL —
     * the escalation's grace semantics: `BashToolProvider.killTree`) and
     * the tool answers a timeout error result with the output captured so
     * far. Must be >= 1.
     */
    val timeoutSeconds: Long = 120,
    /**
     * The in-memory cap in bytes on the command output the tool captures
     * (stdout+stderr merged): output past the cap is drained and
     * discarded, and the result notes the truncation. Must be >= 10_000
     * (a smaller cap makes nearly every result useless) and fit an Int
     * (the capture buffer is a byte array).
     */
    val maxCaptureBytes: Long = 1_000_000,
) {
    fun validate() {
        if (!enabled) return
        require(shellPath.isNotBlank()) {
            "tool.bash.shellPath must not be blank when tool.bash.enabled is true"
        }
        require(timeoutSeconds >= 1) {
            "tool.bash.timeoutSeconds must be >= 1, got $timeoutSeconds"
        }
        require(maxCaptureBytes >= 10_000) {
            "tool.bash.maxCaptureBytes must be >= 10_000, got $maxCaptureBytes"
        }
        require(maxCaptureBytes <= Int.MAX_VALUE) {
            "tool.bash.maxCaptureBytes must fit an Int (<= ${Int.MAX_VALUE}), got $maxCaptureBytes"
        }
    }
}
