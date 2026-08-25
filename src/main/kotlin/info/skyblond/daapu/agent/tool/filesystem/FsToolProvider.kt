package info.skyblond.daapu.agent.tool.filesystem

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.config.validateToolNamespaceSyntax
import info.skyblond.daapu.mcp.errorResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.util.Base64
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * The hardcoded namespace of the read-only filesystem tool provider
 * ([FsToolProvider]): advertised tool names become `fs__read_text_file`, ...
 * The namespace is NOT configurable — the provider only exists because the
 * vanilla filesystem MCP server has no read-only mode, and a user who wants
 * read-write access uses that MCP server instead (under the same `fs`
 * namespace, which fails fast at boot on the duplicate namespace if this
 * provider is enabled at the same time — see `config.example.jsonc`).
 */
const val FS_NAMESPACE: String = "fs"

/**
 * The read-only filesystem tool provider: a local mock of the read-only
 * tools of the vanilla filesystem MCP server
 * (`@modelcontextprotocol/server-filesystem`), so the LLM can inspect local
 * files WITHOUT the write tools that server ships. Configured under
 * `tool.fs` (`FsToolConfig`): the LLM may access files under [allowedDirs],
 * except paths matching the [blacklists] glob patterns (blob/gitignore
 * syntax — [GlobMatcher]).
 *
 * Access control, applied in [resolveTarget] on the TOOL'S TARGET path:
 *
 * - the target must be inside an allowed directory after full symlink and
 *   `..` resolution (canonical path containment — a symlink pointing
 *   outside the allowed dirs is refused, as is a path escaping via `..`),
 * - the target must not match a blacklist pattern (matched against the
 *   path relative to the allowed directory).
 *
 * Listings and searches are NOT filtered: results that merely contain
 * blacklisted entries are returned as-is — only the requested target is
 * checked. Matches and results use the RAW entry path (a symlink matches
 * by its link name and is returned as the link path, like the server's
 * `path.relative` over the raw names); only the containment checks use
 * the canonical path. Traversal skips entries whose canonical path leaves
 * the allowed roots (symlink-escape protection: `search_files` skips them
 * like the server's per-entry catch, and the listings hide them — the
 * server's plain `list_directory` would show the raw link entry). An
 * unreadable subtree is skipped by `search_files` but fails
 * `directory_tree`, like the server. `directory_tree` is deliberately
 * STRICTER than the server on escaping entries: one fails the whole tree —
 * the server never descends a symlink (dirent type), so it would just list
 * it as a plain file entry and its per-directory `validatePath` cannot
 * throw for it.
 *
 * Error contract: an invalid argument or a refused path answers an
 * `isError` result (the model can react); [CancellationException] is
 * rethrown; everything else (I/O failures, races) is warn-logged and
 * answered as an `isError` — never a thrown transport failure (this
 * provider has no transport). All I/O runs on the hand callback route's
 * `Dispatchers.IO` ([HandCallbackService] wraps `execute`); this provider
 * declares no execution budget ([ToolProvider.executionTimeoutSeconds]
 * stays 0).
 */
class FsToolProvider(
    allowedDirs: List<String>,
    blacklists: List<String>,
) : ToolProvider {
    private val roots: List<Path>
    private val blacklistMatchers: List<GlobMatcher>
    private val specs: List<ToolSpec>

    init {
        validateToolNamespaceSyntax(FS_NAMESPACE, "filesystem tool")
        require(allowedDirs.isNotEmpty()) {
            "tool.fs.allowedDirs must not be empty: the LLM would have no directory to access"
        }
        require(blacklists.none { it.isBlank() }) {
            "tool.fs.blacklists must not contain blank entries (a blank pattern matches everything)"
        }
        // canonicalize the allowed dirs at construction: `~` expansion, `..`
        // resolution and symlinks all resolve here, so the containment check
        // compares canonical paths only (a symlinked config path like
        // /tmp -> /private/tmp on macOS can never be escaped by a request
        // that resolves to the same file the "other" way)
        roots = allowedDirs.map(::expandHome).map { dir ->
            val canonical = File(dir).canonicalFile
            require(canonical.isDirectory) {
                "tool.fs.allowedDirs entry '$dir' does not exist or is not a directory"
            }
            canonical.toPath()
        }.distinct()
        blacklistMatchers = blacklists.map(::GlobMatcher)
        specs = toolSpecs.map { it.copy(name = "${FS_NAMESPACE}__${it.name}") }
    }

    override fun namespaces(): Set<String> = setOf(FS_NAMESPACE)

    override suspend fun specifications(): List<ToolSpec> = specs

    override suspend fun execute(request: ToolCallRequest): ChatMessagePart.ToolResult {
        // in namespaced mode only `{namespace}__{tool}` names are accepted:
        // anything else is not advertised by this provider
        val prefix = "${FS_NAMESPACE}__"
        val name = request.name.takeIf { it.startsWith(prefix) }?.substring(prefix.length)
        if (name == null) {
            return errorResult(
                request, "tool '${request.name}' is not advertised by this filesystem provider"
            )
        }
        logger.info { "Executing tool ${request.name}" }
        return try {
            when (name) {
                "read_text_file" -> readTextFile(request)
                "read_media_file" -> readMediaFile(request)
                "read_multiple_files" -> readMultipleFiles(request)
                "list_directory" -> listDirectory(request)
                "list_directory_with_sizes" -> listDirectoryWithSizes(request)
                "directory_tree" -> directoryTree(request)
                "search_files" -> searchFiles(request)
                "get_file_info" -> getFileInfo(request)
                "list_allowed_directories" -> textResult(
                    request, "Allowed directories:\n${roots.joinToString("\n")}"
                )
                else -> errorResult(
                    request, "tool '${request.name}' is not advertised by this filesystem provider"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            errorResult(request, e.message ?: "illegal argument")
        } catch (e: Exception) {
            logger.warn(e) { "Unexpected filesystem tool failure on ${request.name}" }
            errorResult(request, "filesystem tool '${request.name}' failed: ${e.message}")
        }
    }

    // ---------- the tools ----------

    private fun readTextFile(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val head = args.optionalIntArg("head", "head")
        val tail = args.optionalIntArg("tail", "tail")
        if (head != null && tail != null) {
            return errorResult(request, "cannot specify both head and tail simultaneously")
        }
        if (head != null && head < 1) return errorResult(request, "head must be >= 1")
        if (tail != null && tail < 1) return errorResult(request, "tail must be >= 1")
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isRegularFile(target)) return errorResult(request, "'$path' is not a file")
        // decode with replacement (like the server's UTF-8 read): a binary
        // file yields replacement chars instead of failing the whole read
        val content = Files.readAllBytes(target).toString(Charsets.UTF_8)
        // lines() handles all line separators; a final newline yields a
        // trailing empty line. The server's headFile counts complete lines
        // only — head must NOT count it — but its tailFile DOES (tail of a
        // newline-terminated file answers the trailing empty line, so
        // tail=1 of "a\nb\n" is ""), so tail keeps it.
        val lines = content.lines()
        val headLines = if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
        val text = when {
            head != null -> headLines.take(head).joinToString("\n")
            tail != null -> lines.takeLast(tail).joinToString("\n")
            else -> content
        }
        return textResult(request, text)
    }

    private fun readMediaFile(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isRegularFile(target)) return errorResult(request, "'$path' is not a file")
        val bytes = Files.readAllBytes(target)
        val extension = "." + target.fileName.toString().substringAfterLast('.', "").lowercase()
        val mimeType = MIME_TYPES[extension] ?: "application/octet-stream"
        val kind = when {
            mimeType.startsWith("image/") -> AttachmentKind.Image
            mimeType.startsWith("audio/") -> AttachmentKind.Audio
            else -> AttachmentKind.File
        }
        return ChatMessagePart.ToolResult(
            id = request.id,
            tool = request.name,
            parts = listOf(
                ChatMessagePart.Attachment(
                    kind = kind,
                    content = AttachmentContent.Base64(Base64.getEncoder().encodeToString(bytes)),
                    mimeType = mimeType,
                )
            ),
        )
    }

    private fun readMultipleFiles(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val paths = args.requiredStringArray("paths") ?: return errorResult(
            request, "paths is required and must be a non-empty array of non-blank paths"
        )
        // failed reads for individual files never stop the batch (server
        // semantics); the errors are reported inline per file
        val results = paths.map { filePath ->
            try {
                val target = resolveTarget(request, filePath)
                if (!Files.exists(target)) throw IllegalArgumentException("path '$filePath' does not exist")
                if (!Files.isRegularFile(target)) throw IllegalArgumentException("'$filePath' is not a file")
                val content = Files.readAllBytes(target).toString(Charsets.UTF_8)
                "$filePath:\n$content\n"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$filePath: Error - ${e.message}"
            }
        }
        return textResult(request, results.joinToString("\n---\n"))
    }

    private fun listDirectory(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isDirectory(target)) return errorResult(request, "'$path' is not a directory")
        val entries = listEntries(target).sortedBy { it.second.fileName.toString() }
        val text = entries.joinToString("\n") { (_, entry) ->
            "${if (isDirectoryEntry(entry)) "[DIR]" else "[FILE]"} ${entry.fileName}"
        }
        return textResult(request, text)
    }

    private fun listDirectoryWithSizes(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val sortBy = args.optionalText("sortBy") ?: "name"
        if (sortBy != "name" && sortBy != "size") {
            return errorResult(request, "sortBy must be either 'name' or 'size', got '$sortBy'")
        }
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isDirectory(target)) return errorResult(request, "'$path' is not a directory")
        val detailed = listEntries(target).map { (canonical, entry) ->
            DetailedEntry(
                displayName = entry.fileName.toString(),
                isDirectory = isDirectoryEntry(entry),
                // size follows symlinks (like the server's fs.stat); a broken
                // symlink answers 0 (the server catches the stat error too)
                size = runCatching { Files.size(canonical) }.getOrDefault(0L),
            )
        }
        val sorted = when (sortBy) {
            "size" -> detailed.sortedWith(
                compareByDescending<DetailedEntry> { it.size }.thenBy { it.displayName }
            )
            else -> detailed.sortedBy { it.displayName }
        }
        val lines = sorted.map { entry ->
            val prefix = if (entry.isDirectory) "[DIR]" else "[FILE]"
            val size = if (entry.isDirectory) "" else formatSize(entry.size).padStart(10)
            "$prefix ${entry.displayName.padEnd(30)} $size"
        }
        val totalFiles = detailed.count { !it.isDirectory }
        val totalDirs = detailed.count { it.isDirectory }
        val totalSize = detailed.filter { !it.isDirectory }.sumOf { it.size }
        val text = (
            lines + listOf(
                "",
                "Total: $totalFiles files, $totalDirs directories",
                "Combined size: ${formatSize(totalSize)}",
            )
            ).joinToString("\n")
        return textResult(request, text)
    }

    private fun directoryTree(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val excludePatterns = args.optionalStringArray("excludePatterns").orEmpty()
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isDirectory(target)) return errorResult(request, "'$path' is not a directory")
        // a pattern without '*' also matches as a path prefix and as any
        // ancestor/descendant (the server's three-variant rule); lenient —
        // an invalid pattern excludes nothing (minimatch treats it literally)
        val compiledExcludes = excludePatterns.map { pattern ->
            if (pattern.contains('*')) listOfNotNull(GlobMatcher.lenient(pattern))
            else listOfNotNull(
                GlobMatcher.lenient(pattern),
                GlobMatcher.lenient("**/$pattern"),
                GlobMatcher.lenient("**/$pattern/**"),
            )
        }
        val tree = buildTree(target, target, compiledExcludes)
        val text = PRETTY_JSON.encodeToString(JsonElement.serializer(), tree)
        return textResult(request, text)
    }

    private fun searchFiles(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val pattern = args.requiredText("pattern") ?: return errorResult(
            request, "pattern is required and must not be blank"
        )
        val excludePatterns = args.optionalStringArray("excludePatterns").orEmpty()
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        if (!Files.isDirectory(target)) return errorResult(request, "'$path' is not a directory")
        // lenient: an invalid pattern matches nothing (minimatch treats a
        // malformed pattern as a literal string, so it never errors)
        val patternMatcher = GlobMatcher.lenient(pattern)
        val excludeMatchers = excludePatterns.mapNotNull(GlobMatcher::lenient)
        val results = mutableListOf<String>()
        fun walk(current: Path) {
            for ((canonical, entry) in listEntries(current)) {
                // the relative path and the result use the RAW entry path
                // (the server's `path.relative` over the raw names): a
                // symlink — dangling or not — matches by its LINK name and
                // is returned as the link path; the canonical path only
                // decided containment in listEntries
                val relative = target.relativize(entry).map(Path::toString).joinToString("/")
                if (excludeMatchers.any { it.matches(relative) }) continue
                if (patternMatcher?.matches(relative) == true) results += entry.toString()
                // symlinked directories are NOT descended (dirent type), like
                // the server's search
                if (isDirectoryEntry(entry)) {
                    // an unreadable subtree is SKIPPED like the server's
                    // per-entry catch: the search continues with the
                    // siblings, only the root's own readdir failure fails
                    // the whole tool
                    try {
                        walk(canonical)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.debug(e) { "Skipping unreadable directory $canonical in search_files" }
                    }
                }
            }
        }
        walk(target)
        val text = results.ifEmpty { listOf("No matches found") }.joinToString("\n")
        return textResult(request, text)
    }

    private fun getFileInfo(request: ToolCallRequest): ChatMessagePart.ToolResult {
        val args = request.args
        val path = args.requiredText("path") ?: return errorResult(
            request, "path is required and must not be blank"
        )
        val target = resolveTarget(request, path)
        if (!Files.exists(target)) return errorResult(request, "path '$path' does not exist")
        val attrs = Files.readAttributes(target, BasicFileAttributes::class.java)
        // unix:mode is POSIX-only; on other filesystems (Windows) fall back
        // to the DOS read-only bit — mirroring Node's Windows stat.mode
        // (files 0o666, read-only 0o444, directories 0o777)
        val permissions = runCatching {
            val mode = Files.getAttribute(target, "unix:mode") as? Long
            if (mode != null) (mode and 0x1FF).toString(8).padStart(3, '0') else null
        }.getOrNull() ?: dosPermissions(target)
        val lines = listOf(
            "size: ${attrs.size()}",
            // ISO instants, deliberately NOT the server's JS Date.toString()
            // format — the instants carry the same information
            "created: ${attrs.creationTime().toInstant()}",
            "modified: ${attrs.lastModifiedTime().toInstant()}",
            "accessed: ${attrs.lastAccessTime().toInstant()}",
            "isDirectory: ${attrs.isDirectory}",
            "isFile: ${attrs.isRegularFile}",
            "permissions: $permissions",
        )
        return textResult(request, lines.joinToString("\n"))
    }

    // ---------- access control ----------

    /**
     * Resolve a requested path to its canonical absolute form, refusing
     * anything that is not inside an allowed root or that matches a
     * blacklist pattern. Throws [IllegalArgumentException] with the
     * model-visible reason; the callers catch it as an `isError` result.
     */
    private fun resolveTarget(request: ToolCallRequest, requested: String): Path {
        val expanded = expandHome(requested)
        val absolute = Path.of(expanded).let { path ->
            if (path.isAbsolute) path else roots.first().resolve(path)
        }
        // canonicalize the longest existing prefix: `..` and symlinks (in the
        // path AND in its ancestors) resolve here, so an escaping request can
        // never pass the containment check below
        val canonical = absolute.toFile().canonicalFile.toPath()
        val root = roots.firstOrNull { canonical.startsWith(it) }
            ?: throw IllegalArgumentException(
                "Access denied: path '$requested' is outside the allowed directories (${roots.joinToString(", ")})"
            )
        val relative = root.relativize(canonical).map(Path::toString).joinToString("/")
        if (blacklistMatchers.any { it.matches(relative) }) {
            throw IllegalArgumentException("Access denied: path '$requested' matches a blacklist pattern")
        }
        return canonical
    }

    /**
     * The entries of [dir] whose canonical path is still inside an allowed
     * root: `(canonical, rawEntry)` pairs. A symlink pointing outside the
     * allowed dirs (or a broken entry that cannot be canonicalized) is
     * skipped — listings and searches never leak outside the roots.
     */
    private fun listEntries(dir: Path): List<Pair<Path, Path>> =
        Files.list(dir).use { stream ->
            stream.toList().mapNotNull { entry ->
                val canonical = runCatching { File(entry.toString()).canonicalFile.toPath() }
                    .getOrNull() ?: return@mapNotNull null
                if (!roots.any { canonical.startsWith(it) }) return@mapNotNull null
                canonical to entry
            }
        }

    /**
     * Like [listEntries] but STRICT — the directory_tree's view, and
     * deliberately stricter than the server: an entry whose canonical path
     * leaves the allowed roots THROWS, failing the whole tree (the model
     * learns the tree contains a forbidden target instead of getting a
     * silently partial tree; the server never descends a symlink — dirent
     * type — and would merely list it as a plain file entry). An entry
     * that cannot be canonicalized (a dangling symlink) is kept as-is —
     * its parent is canonical and inside the roots, and the dirent type
     * classifies it a file, exactly like the server's parent-realpath
     * fallback.
     */
    private fun listEntriesStrict(dir: Path): List<Pair<Path, Path>> =
        Files.list(dir).use { stream ->
            stream.toList().map { entry ->
                val canonical = runCatching { File(entry.toString()).canonicalFile.toPath() }
                    .getOrNull()
                if (canonical == null) {
                    // dangling symlink (or unreadable ancestor — impossible
                    // here, the parents are canonical): keep the raw entry
                    return@map entry to entry
                }
                if (!roots.any { canonical.startsWith(it) }) {
                    throw IllegalArgumentException(
                        "Access denied: '${entry.fileName}' resolves outside the allowed directories"
                    )
                }
                canonical to entry
            }
        }

    /** The dirent type (no symlink following), like the server's `withFileTypes`. */
    private fun isDirectoryEntry(entry: Path): Boolean =
        runCatching {
            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .isDirectory
        }.getOrDefault(false)

    /**
     * The permissions fallback for filesystems without `unix:mode`
     * (Windows): "777" for directories, "444" for a DOS read-only file and
     * "666" otherwise — mirroring Node's Windows `stat.mode`. "000" only
     * when even the DOS attributes are unavailable.
     */
    private fun dosPermissions(target: Path): String =
        runCatching { Files.readAttributes(target, DosFileAttributes::class.java) }
            .getOrNull()
            ?.let { dos ->
                when {
                    dos.isDirectory -> "777"
                    dos.isReadOnly -> "444"
                    else -> "666"
                }
            } ?: "000"

    private fun buildTree(
        current: Path,
        root: Path,
        compiledExcludes: List<List<GlobMatcher>>,
    ): JsonElement = buildJsonArray {
        for ((canonical, entry) in listEntriesStrict(current).sortedBy { it.second.fileName.toString() }) {
            // like search_files, the relative path uses the RAW entry path
            // (a symlink excludes by its link name, like the server's
            // `path.relative` over the raw names); canonical is containment
            val relative = root.relativize(entry).map(Path::toString).joinToString("/")
            val excluded = compiledExcludes.any { matchers -> matchers.any { it.matches(relative) } }
            if (excluded) continue
            val isDir = isDirectoryEntry(entry)
            add(
                buildJsonObject {
                    put("name", entry.fileName.toString())
                    put("type", if (isDir) "directory" else "file")
                    if (isDir) put("children", buildTree(canonical, root, compiledExcludes))
                }
            )
        }
    }

    private data class DetailedEntry(
        val displayName: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    companion object {
        private val logger = KotlinLogging.logger {}

        private val PRETTY_JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

        /** The server's extension -> MIME map, mirrored verbatim. */
        private val MIME_TYPES = mapOf(
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".gif" to "image/gif",
            ".webp" to "image/webp",
            ".bmp" to "image/bmp",
            ".svg" to "image/svg+xml",
            ".mp3" to "audio/mpeg",
            ".wav" to "audio/wav",
            ".ogg" to "audio/ogg",
            ".flac" to "audio/flac",
        )

        private fun formatSize(bytes: Long): String {
            val units = listOf("B", "KB", "MB", "GB", "TB")
            if (bytes == 0L) return "0 B"
            val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
            if (i <= 0) return "$bytes B"
            val unitIndex = minOf(i, units.size - 1)
            val value = bytes / 1024.0.pow(unitIndex)
            return String.format(Locale.ROOT, "%.2f %s", value, units[unitIndex])
        }

        private fun expandHome(path: String): String {
            val home = System.getProperty("user.home") ?: return path
            return when {
                path == "~" -> home
                path.startsWith("~/") -> home + path.substring(1)
                else -> path
            }
        }

        private val toolSpecs = listOf(
            ToolSpec(
                name = "read_text_file",
                description = "Read the complete contents of a file from the file system as text. Handles various text encodings and provides detailed error messages if the file cannot be read. Use this tool when you need to examine the contents of a single file. Use the 'head' parameter to read only the first N lines of a file, or the 'tail' parameter to read only the last N lines of a file. Operates on the file as text regardless of extension. Only works within allowed directories; paths matching the blacklist patterns are refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The file path to read."),
                    "head" to integerSchema("If provided, returns only the first N lines of the file"),
                    "tail" to integerSchema("If provided, returns only the last N lines of the file"),
                ),
            ),
            ToolSpec(
                name = "read_media_file",
                description = "Read a file and return it as a base64-encoded attachment with its MIME type. Image and audio files are returned as image/audio attachments; any other file type is returned as a file attachment. Only works within allowed directories; paths matching the blacklist patterns are refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The file path to read."),
                ),
            ),
            ToolSpec(
                name = "read_multiple_files",
                description = "Read the contents of multiple files simultaneously. This is more efficient than reading files one by one when you need to analyze or compare multiple files. Each file's content is returned with its path as a reference. Failed reads for individual files won't stop the entire operation. Only works within allowed directories; paths matching the blacklist patterns are refused per file.",
                schema = objectSchema(
                    required = listOf("paths"),
                    "paths" to stringArraySchema("Array of file paths to read. Each path must point to a valid file within allowed directories."),
                ),
            ),
            ToolSpec(
                name = "list_directory",
                description = "Get a detailed listing of all files and directories in a specified path. Results clearly distinguish between files and directories with [FILE] and [DIR] prefixes, sorted by name. This tool is essential for understanding directory structure and finding specific files within a directory. Only works within allowed directories; listing a directory that itself matches a blacklist pattern is refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The directory path to list."),
                ),
            ),
            ToolSpec(
                name = "list_directory_with_sizes",
                description = "Get a detailed listing of all files and directories in a specified path, including sizes. Results clearly distinguish between files and directories with [FILE] and [DIR] prefixes, sorted by name or size (descending), and include summary statistics. Only works within allowed directories; listing a directory that itself matches a blacklist pattern is refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The directory path to list."),
                    "sortBy" to enumStringSchema(
                        "Sort entries by \"name\" or \"size\" (default \"name\")", "name", "size"
                    ),
                ),
            ),
            ToolSpec(
                name = "directory_tree",
                description = "Get a recursive tree view of files and directories as a JSON structure. Each entry includes 'name', 'type' (file/directory), and 'children' for directories. Files have no children array, while directories always have a children array (which may be empty). The output is formatted with 2-space indentation. Only works within allowed directories; a tree root that itself matches a blacklist pattern is refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The starting directory path."),
                    "excludePatterns" to stringArraySchema("Exclude any patterns. Glob formats are supported."),
                ),
            ),
            ToolSpec(
                name = "search_files",
                description = "Recursively search for files and directories matching a pattern. The patterns should be glob-style patterns that match paths relative to the search directory. Use pattern like '*.ext' to match files in the current directory, and '**/*.ext' to match files in all subdirectories. Returns full paths to all matching items. Great for finding files when you don't know their exact location. Only searches within allowed directories; a search root that itself matches a blacklist pattern is refused.",
                schema = objectSchema(
                    required = listOf("path", "pattern"),
                    "path" to stringSchema("The starting directory path."),
                    "pattern" to stringSchema("The glob search pattern, matched against paths relative to the starting directory."),
                    "excludePatterns" to stringArraySchema("Exclude any patterns. Glob formats are supported."),
                ),
            ),
            ToolSpec(
                name = "get_file_info",
                description = "Retrieve detailed metadata about a file or directory. Returns comprehensive information including size, creation time, last modified time, permissions, and type. This tool is perfect for understanding file characteristics without reading the actual content. Only works within allowed directories; a path matching a blacklist pattern is refused.",
                schema = objectSchema(
                    required = listOf("path"),
                    "path" to stringSchema("The file or directory path."),
                ),
            ),
            ToolSpec(
                name = "list_allowed_directories",
                description = "Returns the list of directories that this provider is allowed to access. Subdirectories within these allowed directories are also accessible. Use this to understand which directories and their nested paths are available before trying to access files.",
                schema = objectSchema(required = emptyList()),
            ),
        )

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun enumStringSchema(description: String, vararg values: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
            put("enum", buildJsonArray { values.forEach { add(it) } })
        }

        private fun integerSchema(description: String) = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }

        private fun stringArraySchema(description: String) = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", description)
        }

        private fun objectSchema(
            required: List<String>,
            vararg properties: Pair<String, JsonObject>,
        ) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(it) } })
            }
        }
    }
}

/**
 * The text value of [key] or `null` when absent/blank. A PRESENT value that
 * is not a JSON string throws [IllegalArgumentException] — like the
 * server's zod `z.string()` schema, which rejects numbers/booleans/null at
 * the tool-call boundary instead of coercing them.
 */
private fun JsonObject.requiredText(key: String): String? {
    val element = this[key] ?: return null
    val primitive = runCatching { element.jsonPrimitive }.getOrNull()
        ?: throw IllegalArgumentException("$key must be a string")
    if (!primitive.isString) throw IllegalArgumentException("$key must be a string")
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

private fun JsonObject.optionalText(key: String): String? =
    requiredText(key)

/**
 * `null` when [key] is ABSENT; throws [IllegalArgumentException] when it is
 * present but not an integer number. Deliberately STRICTER than the server:
 * zod's `z.number()` accepts any JSON number, so the server reads with a
 * float (truncating through its `lines.length < numLines` loops) and with
 * `0`/negative values (answering an empty read); here head/tail must be
 * whole numbers, and a string, float, bool or null argument is an invalid
 * argument, not a silently truncated one. The `>= 1` range check lives in
 * [readTextFile].
 */
private fun JsonObject.optionalIntArg(key: String, display: String): Int? {
    val element = this[key] ?: return null
    val primitive = runCatching { element.jsonPrimitive }.getOrNull()
        ?: throw IllegalArgumentException("$display must be a number")
    if (primitive.isString) throw IllegalArgumentException("$display must be a number")
    val value = primitive.contentOrNull?.toIntOrNull()
        ?: throw IllegalArgumentException("$display must be an integer, got '$primitive'")
    return value
}

/**
 * Non-blank string entries only; absent, empty or all-blank arrays answer
 * `null`. A present value that is not a string array — or an array with a
 * non-string element (number, boolean, null, object) — throws
 * [IllegalArgumentException], like the server's zod `z.array(z.string())`
 * schema.
 */
private fun JsonObject.optionalStringArray(key: String): List<String>? {
    val array = this[key]?.jsonArray ?: return null
    return array.map { element ->
        val primitive = runCatching { element.jsonPrimitive }.getOrNull()
            ?: throw IllegalArgumentException("$key must be an array of strings")
        if (!primitive.isString) throw IllegalArgumentException("$key must be an array of strings")
        primitive.contentOrNull?.trim().orEmpty()
    }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
}

private fun JsonObject.requiredStringArray(key: String): List<String>? = optionalStringArray(key)

private fun textResult(request: ToolCallRequest, text: String): ChatMessagePart.ToolResult =
    ChatMessagePart.ToolResult(
        id = request.id,
        tool = request.name,
        parts = listOf(ChatMessagePart.Text(text)),
    )

private fun errorResult(request: ToolCallRequest, error: String): ChatMessagePart.ToolResult =
    errorResult(request.id, request.name, error)
