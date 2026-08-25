package info.skyblond.daapu.config

import kotlinx.serialization.Serializable

/**
 * The tool provider settings, the harness-owned tools that are not MCP
 * servers: today only the read-only filesystem provider (`tool.fs`), the
 * replacement for the vanilla filesystem MCP server's read-only half (that
 * server has no read-only mode; ours is hardcoded to it).
 */
@Serializable
data class ToolConfig(
    /** The read-only filesystem tool provider, see [FsToolConfig]. */
    val fs: FsToolConfig = FsToolConfig(),
) {
    fun validate() = fs.validate()
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
