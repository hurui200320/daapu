package info.skyblond.daapu.agent.tool.filesystem

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.tool.CombinedToolProvider
import info.skyblond.daapu.agent.tool.ToolCallRequest
import info.skyblond.daapu.agent.tool.ToolProvider
import info.skyblond.daapu.agent.tool.ToolSpec
import info.skyblond.daapu.config.FsToolConfig
import info.skyblond.daapu.config.ToolConfig
import info.skyblond.daapu.config.testAppConfig
import info.skyblond.daapu.testutil.testKoinApp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the read-only filesystem tool provider: the tool surface (names,
 * schemas, output formats mirroring the vanilla filesystem MCP server), the
 * allowed-directory containment (symlinks, `..`, relative paths) and the
 * blacklist access control — refused as the TARGET of a call, returned
 * as-is inside listing/search results.
 */
class FsToolProviderTest {

    private lateinit var root: Path
    private lateinit var provider: FsToolProvider

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("daapu-fs-test")
        Files.createDirectories(root.resolve("sub/deep"))
        Files.writeString(root.resolve("hello.txt"), "line1\nline2\nline3\n")
        Files.writeString(root.resolve(".env"), "SECRET=1\n")
        Files.writeString(root.resolve("sub/notes.md"), "note content\n")
        Files.writeString(root.resolve("sub/deep/target.txt"), "deep content\n")
        Files.createDirectories(root.resolve("secrets"))
        Files.writeString(root.resolve("secrets/key.pem"), "PRIVATE\n")
        Files.write(root.resolve("img.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        provider = FsToolProvider(
            allowedDirs = listOf(root.toString()),
            blacklists = listOf("**/.env", "**/*.env", "secrets/**"),
        )
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    // ---------- the tool surface ----------

    @Test
    fun `advertises the nine read-only tools under the fs namespace`() = runBlocking {
        assertEquals(setOf("fs"), provider.namespaces())
        val names = provider.specifications().map { it.name }
        assertEquals(
            listOf(
                "fs__read_text_file",
                "fs__read_media_file",
                "fs__read_multiple_files",
                "fs__list_directory",
                "fs__list_directory_with_sizes",
                "fs__directory_tree",
                "fs__search_files",
                "fs__get_file_info",
                "fs__list_allowed_directories",
            ),
            names,
        )
        assertTrue(provider.specifications().all { it.description.isNotBlank() && it.schema.isNotEmpty() })
    }

    @Test
    fun `declares no execution budget`() {
        assertEquals(0, provider.executionTimeoutSeconds("fs__read_text_file"))
        assertEquals(0, provider.executionTimeoutSeconds("fs__nope"))
    }

    @Test
    fun `rejects names it does not advertise`() = runBlocking {
        for (name in listOf("read_text_file", "exa__web_search_exa", "fs__nope")) {
            val result = provider.execute(request(name))
            assertTrue(result.isError, "expected error for $name")
            assertContains(result.text(), "not advertised")
        }
    }

    // ---------- read_text_file ----------

    @Test
    fun `read_text_file reads the whole file as text`() = runBlocking {
        val result = provider.execute(request("fs__read_text_file", path("hello.txt")))
        assertFalse(result.isError)
        assertEquals("line1\nline2\nline3\n", result.text())
    }

    @Test
    fun `read_text_file supports head and tail`() = runBlocking {
        val head = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", 2) })
        )
        assertEquals("line1\nline2", head.text())
        // tail counts the trailing empty line, like the server's tailFile:
        // tail=1 of a newline-terminated file answers ""
        val tail1 = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("tail", 1) })
        )
        assertEquals("", tail1.text())
        val tail2 = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("tail", 2) })
        )
        assertEquals("line3\n", tail2.text())
        val tail3 = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("tail", 3) })
        )
        assertEquals("line2\nline3\n", tail3.text())
        // a file NOT ending in a newline has no trailing empty line
        val noNewline = root.resolve("no-newline.txt")
        try {
            Files.writeString(noNewline, "a\nb")
            val tail = provider.execute(
                request("fs__read_text_file", buildJsonObject { put("path", "no-newline.txt"); put("tail", 1) })
            )
            assertEquals("b", tail.text())
        } finally {
            Files.deleteIfExists(noNewline)
        }
        // head beyond the file's lines clamps naturally (the trailing
        // newline is dropped, like the server's headFile)
        val short = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", 99) })
        )
        assertEquals("line1\nline2\nline3", short.text())
    }

    @Test
    fun `read_text_file rejects invalid head and tail arguments`() = runBlocking {
        val both = provider.execute(
            request(
                "fs__read_text_file",
                buildJsonObject { put("path", "hello.txt"); put("head", 1); put("tail", 1) },
            )
        )
        assertTrue(both.isError)
        assertContains(both.text(), "head and tail")
        // the server accepts 0/negative head/tail (zod `z.number()`; its
        // headFile/tailFile loops answer an empty read); we are deliberately
        // stricter: head/tail must be >= 1
        for (bad in listOf(0, -1)) {
            val head = provider.execute(
                request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", bad) })
            )
            assertTrue(head.isError, "expected error for head=$bad")
            val tail = provider.execute(
                request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("tail", bad) })
            )
            assertTrue(tail.isError, "expected error for tail=$bad")
        }
        // non-numbers are rejected like the server's zod number schema
        for (bad in listOf("1", "abc")) {
            val head = provider.execute(
                request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", bad) })
            )
            assertTrue(head.isError, "expected error for head=$bad")
        }
        // ...but a float IS a valid zod `z.number()` — the server would read
        // with it (truncating through its comparison loops); only whole
        // numbers are accepted here
        val float = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", 1.5) })
        )
        assertTrue(float.isError)
        val bool = provider.execute(
            request("fs__read_text_file", buildJsonObject { put("path", "hello.txt"); put("head", true) })
        )
        assertTrue(bool.isError)
    }

    @Test
    fun `read_text_file answers clean errors for missing paths and directories`() = runBlocking {
        val missing = provider.execute(request("fs__read_text_file", path("nope.txt")))
        assertTrue(missing.isError)
        assertContains(missing.text(), "does not exist")
        val dir = provider.execute(request("fs__read_text_file", path("sub")))
        assertTrue(dir.isError)
        assertContains(dir.text(), "not a file")
    }

    @Test
    fun `read_text_file accepts relative and absolute paths inside the allowed dir`() = runBlocking {
        val relative = provider.execute(request("fs__read_text_file", path("sub/notes.md")))
        assertEquals("note content\n", relative.text())
        val absolute = provider.execute(
            request("fs__read_text_file", path(root.resolve("sub/notes.md").toString()))
        )
        assertEquals("note content\n", absolute.text())
    }

    // ---------- read_media_file ----------

    @Test
    fun `read_media_file returns an image attachment with base64 content`() = runBlocking {
        val result = provider.execute(request("fs__read_media_file", path("img.png")))
        assertFalse(result.isError)
        val attachment = result.parts.single() as ChatMessagePart.Attachment
        assertEquals(AttachmentKind.Image, attachment.kind)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(
            Base64.getEncoder().encodeToString(Files.readAllBytes(root.resolve("img.png"))),
            (attachment.content as AttachmentContent.Base64).base64,
        )
    }

    @Test
    fun `read_media_file returns non-media files as file attachments`() = runBlocking {
        val result = provider.execute(request("fs__read_media_file", path("hello.txt")))
        assertFalse(result.isError)
        val attachment = result.parts.single() as ChatMessagePart.Attachment
        assertEquals(AttachmentKind.File, attachment.kind)
        assertEquals("application/octet-stream", attachment.mimeType)
    }

    // ---------- read_multiple_files ----------

    @Test
    fun `read_multiple_files reads every file and isolates per-file failures`() = runBlocking {
        val result = provider.execute(
            request(
                "fs__read_multiple_files",
                buildJsonObject {
                    putJsonArray("paths") {
                        add("hello.txt")
                        add("missing.txt")
                        add(".env")
                    }
                },
            )
        )
        assertFalse(result.isError)
        assertContains(result.text(), "hello.txt:\nline1\nline2\nline3")
        assertContains(result.text(), "missing.txt: Error -")
        assertContains(result.text(), ".env: Error - Access denied")
        assertContains(result.text(), "\n---\n")
    }

    @Test
    fun `read_multiple_files rejects an empty paths array`() = runBlocking {
        val result = provider.execute(
            request("fs__read_multiple_files", buildJsonObject { put("paths", buildJsonArray { }) })
        )
        assertTrue(result.isError)
    }

    // ---------- list_directory ----------

    @Test
    fun `list_directory lists entries sorted with FILE and DIR prefixes, blacklists included as-is`() = runBlocking {
        val result = provider.execute(request("fs__list_directory", path(".")))
        assertFalse(result.isError)
        assertEquals(
            """
            [FILE] .env
            [FILE] hello.txt
            [FILE] img.png
            [DIR] secrets
            [DIR] sub
            """.trimIndent(),
            result.text(),
        )
    }

    @Test
    fun `list_directory lists a directory that merely contains blacklisted files`() = runBlocking {
        // `secrets/**` does not match the `secrets` dir itself (minimatch
        // parity), so the dir is a legal target and its entries — the
        // blacklisted key.pem included — are returned as-is
        val result = provider.execute(request("fs__list_directory", path("secrets")))
        assertFalse(result.isError)
        assertEquals("[FILE] key.pem", result.text())
    }

    // ---------- list_directory_with_sizes ----------

    @Test
    fun `list_directory_with_sizes formats sizes and the summary, sorted by name by default`() = runBlocking {
        val result = provider.execute(request("fs__list_directory_with_sizes", path(".")))
        assertFalse(result.isError)
        assertContains(result.text(), "[FILE] .env")
        assertContains(result.text(), "[FILE] hello.txt")
        assertContains(result.text(), "[FILE] img.png")
        assertContains(result.text(), "[DIR] secrets")
        assertContains(result.text(), "[DIR] sub")
        // the size column is right-aligned to 10 chars after the padded name
        assertContains(result.text(), "${"9 B".padStart(10)}")
        assertContains(result.text(), "${"18 B".padStart(10)}")
        assertContains(result.text(), "${"8 B".padStart(10)}")
        assertContains(result.text(), "Total: 3 files, 2 directories")
        assertContains(result.text(), "Combined size: 35 B")
    }

    @Test
    fun `list_directory_with_sizes sorts by size descending`() = runBlocking {
        val result = provider.execute(
            request("fs__list_directory_with_sizes", path("."), "sortBy" to "size")
        )
        assertFalse(result.isError)
        val names = result.text().lines().take(5).map {
            it.removePrefix("[FILE] ").removePrefix("[DIR] ").trim().substringBefore(" ")
        }
        // the files sort by size descending; the directories (whose sizes
        // are filesystem-dependent and usually above the small files) sort
        // above them, so only the file order is pinned
        assertEquals(listOf("hello.txt", ".env", "img.png"), names.filterNot { it == "secrets" || it == "sub" })
        assertEquals(2, names.count { it == "secrets" || it == "sub" })
    }

    @Test
    fun `list_directory_with_sizes rejects an invalid sortBy`() = runBlocking {
        val result = provider.execute(
            request("fs__list_directory_with_sizes", path("."), "sortBy" to "mtime")
        )
        assertTrue(result.isError)
        assertContains(result.text(), "sortBy")
    }

    // ---------- directory_tree ----------

    @Test
    fun `directory_tree builds a recursive JSON tree including blacklisted entries`() = runBlocking {
        val result = provider.execute(request("fs__directory_tree", path(".")))
        assertFalse(result.isError)
        // pretty-printed with 2-space indent, children present for dirs only
        assertContains(result.text(), "    \"name\": \"sub\",\n    \"type\": \"directory\",\n    \"children\": [")
        assertContains(result.text(), "        \"name\": \"notes.md\",\n        \"type\": \"file\"")
        // blacklisted entries are NOT filtered from listings
        assertContains(result.text(), "    \"name\": \".env\"")
        assertContains(result.text(), "        \"name\": \"key.pem\"")
    }

    @Test
    fun `directory_tree honors excludePatterns with the three-variant rule`() = runBlocking {
        val result = provider.execute(
            request(
                "fs__directory_tree",
                buildJsonObject {
                    put("path", ".")
                    putJsonArray("excludePatterns") { add("sub") }
                },
            )
        )
        assertFalse(result.isError)
        assertFalse(result.text().contains("\"name\": \"sub\""))
        assertContains(result.text(), "\"name\": \"hello.txt\"")
    }

    @Test
    fun `directory_tree ignores invalid excludePatterns`() = runBlocking {
        // minimatch treats a malformed pattern as a literal string, so an
        // invalid pattern must exclude nothing, not fail the tool
        val result = provider.execute(
            request(
                "fs__directory_tree",
                buildJsonObject {
                    put("path", ".")
                    putJsonArray("excludePatterns") { add("[") }
                },
            )
        )
        assertFalse(result.isError)
        assertContains(result.text(), "\"name\": \"sub\"")
        assertContains(result.text(), "\"name\": \".env\"")
    }

    @Test
    fun `directory_tree keeps a dangling symlink as a file entry`() = runBlocking {
        // the server's validatePath falls back to the parent's real path for
        // a dangling symlink, and the dirent type classifies it a file
        val link = root.resolve("dangling-link")
        try {
            Files.createSymbolicLink(link, root.resolve("nowhere.txt"))
            val result = provider.execute(request("fs__directory_tree", path(".")))
            assertFalse(result.isError)
            assertContains(result.text(), "    \"name\": \"dangling-link\",\n    \"type\": \"file\"")
            // the exclusion relative path uses the RAW entry path like the
            // server: the link is excluded by its LINK name, not the target
            val excluded = provider.execute(
                request(
                    "fs__directory_tree",
                    buildJsonObject {
                        put("path", ".")
                        putJsonArray("excludePatterns") { add("dangling-link") }
                    },
                )
            )
            assertFalse(excluded.isError)
            assertFalse(excluded.text().contains("dangling-link"))
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `search_files matches a dangling symlink by its link name and returns the link path`() = runBlocking {
        // the server's relative path is computed over the RAW names: a
        // dangling link matches "**/dangling-link" and is returned as
        // ".../dangling-link" — never under the nonexistent target's name
        val link = root.resolve("dangling-link")
        try {
            Files.createSymbolicLink(link, root.resolve("nowhere.txt"))
            val result = provider.execute(
                request("fs__search_files", path("."), "pattern" to "**/dangling-link")
            )
            assertFalse(result.isError)
            assertEquals(link.toString(), result.text())
            val noTarget = provider.execute(
                request("fs__search_files", path("."), "pattern" to "**/nowhere.txt")
            )
            assertFalse(noTarget.isError)
            assertEquals("No matches found", noTarget.text())
        } finally {
            Files.deleteIfExists(link)
        }
    }

    // ---------- search_files ----------

    @Test
    fun `search_files skips an unreadable subtree and continues with the siblings`() = runBlocking {
        // chmod-based test: root bypasses permissions, and non-POSIX
        // filesystems reject the chmod itself — skip in both cases
        if (System.getProperty("user.name") == "root") return@runBlocking
        val locked = root.resolve("sub/locked")
        Files.createDirectories(locked)
        Files.writeString(locked.resolve("hidden.txt"), "hidden\n")
        val chmod = runCatching { Files.setPosixFilePermissions(locked, emptySet()) }
        if (chmod.isFailure) return@runBlocking
        try {
            // the server's per-entry catch skips the unreadable subtree,
            // the search continues with the siblings
            val result = provider.execute(
                request("fs__search_files", path("."), "pattern" to "**/*.txt")
            )
            assertFalse(result.isError)
            assertContains(result.text(), root.resolve("hello.txt").toString())
            assertContains(result.text(), root.resolve("sub/deep/target.txt").toString())
            assertFalse(result.text().contains("hidden.txt"))
        } finally {
            Files.setPosixFilePermissions(
                locked,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    @Test
    fun `search_files finds recursive glob matches and returns full paths`() = runBlocking {
        val result = provider.execute(
            request("fs__search_files", path("."), "pattern" to "**/*.txt")
        )
        assertFalse(result.isError)
        assertEquals(
            listOf(
                root.resolve("hello.txt").toString(),
                root.resolve("sub/deep/target.txt").toString(),
            ),
            result.text().lines().sorted(),
        )
    }

    @Test
    fun `search_files matches within the search root only`() = runBlocking {
        val result = provider.execute(
            request("fs__search_files", path("sub"), "pattern" to "*.md")
        )
        assertFalse(result.isError)
        assertEquals(root.resolve("sub/notes.md").toString(), result.text())
    }

    @Test
    fun `search_files returns blacklisted matches as-is`() = runBlocking {
        val result = provider.execute(
            request("fs__search_files", path("."), "pattern" to "**/*.env")
        )
        assertFalse(result.isError)
        assertContains(result.text(), root.resolve(".env").toString())
    }

    @Test
    fun `search_files honors excludePatterns and the no-matches sentinel`() = runBlocking {
        val excluded = provider.execute(
            request(
                "fs__search_files",
                buildJsonObject {
                    put("path", ".")
                    put("pattern", "**/*.txt")
                    putJsonArray("excludePatterns") { add("sub/**") }
                },
            )
        )
        assertFalse(excluded.isError)
        assertEquals(root.resolve("hello.txt").toString(), excluded.text())
        val none = provider.execute(
            request("fs__search_files", path("."), "pattern" to "*.xyz")
        )
        assertFalse(none.isError)
        assertEquals("No matches found", none.text())
    }

    // ---------- get_file_info ----------

    @Test
    fun `get_file_info reports metadata for files and directories`() = runBlocking {
        val file = provider.execute(request("fs__get_file_info", path("hello.txt")))
        assertFalse(file.isError)
        assertContains(file.text(), "size: 18")
        assertContains(file.text(), "isDirectory: false")
        assertContains(file.text(), "isFile: true")
        assertContains(file.text(), "created: ")
        assertContains(file.text(), "modified: ")
        assertContains(file.text(), "accessed: ")
        assertTrue(Regex("permissions: \\d{3}").containsMatchIn(file.text()))
        val dir = provider.execute(request("fs__get_file_info", path("sub")))
        assertFalse(dir.isError)
        assertContains(dir.text(), "isDirectory: true")
    }

    // ---------- list_allowed_directories ----------

    @Test
    fun `list_allowed_directories lists the canonical allowed roots`() = runBlocking {
        val result = provider.execute(request("fs__list_allowed_directories"))
        assertFalse(result.isError)
        assertEquals("Allowed directories:\n${root.toFile().canonicalFile}", result.text())
    }

    // ---------- access control ----------

    @Test
    fun `refuses targets outside the allowed directories`() = runBlocking {
        val outside = createTempDirectory("daapu-fs-outside")
        try {
            val file = outside.resolve("secret.txt")
            Files.writeString(file, "outside\n")
            val absolute = provider.execute(request("fs__read_text_file", path(file.toString())))
            assertTrue(absolute.isError)
            assertContains(absolute.text(), "outside the allowed directories")
            // `..` escape relative to the root
            val up = provider.execute(
                request("fs__read_text_file", path("../${outside.fileName}/secret.txt"))
            )
            assertTrue(up.isError)
            assertContains(up.text(), "outside the allowed directories")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `refuses targets matching a blacklist pattern`() = runBlocking {
        val dotEnv = provider.execute(request("fs__read_text_file", path(".env")))
        assertTrue(dotEnv.isError)
        assertContains(dotEnv.text(), "blacklist")
        val nested = provider.execute(request("fs__read_text_file", path("secrets/key.pem")))
        assertTrue(nested.isError)
        assertContains(nested.text(), "blacklist")
        val info = provider.execute(request("fs__get_file_info", path(".env")))
        assertTrue(info.isError)
        assertContains(info.text(), "blacklist")
        val tree = provider.execute(request("fs__directory_tree", path(".env")))
        assertTrue(tree.isError)
    }

    @Test
    fun `a blacklisted directory is refused as a listing target only when the pattern matches it`() = runBlocking {
        val blocked = FsToolProvider(
            allowedDirs = listOf(root.toString()),
            blacklists = listOf("**/.env", "**/secrets"),
        )
        val result = blocked.execute(request("fs__list_directory", path("secrets")))
        assertTrue(result.isError)
        assertContains(result.text(), "blacklist")
    }

    @Test
    fun `symlinks pointing outside the allowed dirs are refused and hidden from listings`() = runBlocking {
        val outside = createTempDirectory("daapu-fs-outside")
        try {
            Files.writeString(outside.resolve("evil.txt"), "outside\n")
            Files.createSymbolicLink(root.resolve("link"), outside)
            val read = provider.execute(request("fs__read_text_file", path("link/evil.txt")))
            assertTrue(read.isError)
            assertContains(read.text(), "outside the allowed directories")
            val listing = provider.execute(request("fs__list_directory", path(".")))
            assertFalse(listing.isError)
            assertFalse(listing.text().contains("link"))
            // directory_tree fails the WHOLE tree on an escaping entry (the
            // server's validatePath throws); the other tools skip it
            val tree = provider.execute(request("fs__directory_tree", path(".")))
            assertTrue(tree.isError)
            assertContains(tree.text(), "Access denied")
            val search = provider.execute(request("fs__search_files", path("."), "pattern" to "**"))
            assertFalse(search.isError)
            assertFalse(search.text().contains("evil.txt"))
        } finally {
            Files.deleteIfExists(root.resolve("link"))
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a symlink pointing inside the allowed dirs is readable through the link`() = runBlocking {
        val link = root.resolve("link-in.txt")
        try {
            Files.createSymbolicLink(link, root.resolve("hello.txt"))
            val result = provider.execute(request("fs__read_text_file", path("link-in.txt")))
            assertFalse(result.isError)
            assertEquals("line1\nline2\nline3\n", result.text())
        } finally {
            Files.deleteIfExists(link)
        }
    }

    @Test
    fun `rejects malformed arguments`() = runBlocking {
        val missing = provider.execute(request("fs__list_directory"))
        assertTrue(missing.isError)
        assertContains(missing.text(), "path is required")
        val blank = provider.execute(request("fs__list_directory", path("  ")))
        assertTrue(blank.isError)
        assertContains(blank.text(), "path is required")
        // an invalid glob matches nothing, like minimatch's literal
        // handling of a malformed pattern (the server never errors)
        val invalidGlob = provider.execute(
            request("fs__search_files", path("."), "pattern" to "[")
        )
        assertFalse(invalidGlob.isError)
        assertEquals("No matches found", invalidGlob.text())
    }

    @Test
    fun `rejects non-string arguments like the server's zod schemas`() = runBlocking {
        // zod's z.string()/z.array(z.string()) reject these at the tool-call
        // boundary; they are never coerced into paths or patterns
        val numPath = provider.execute(request("fs__read_text_file", buildJsonObject { put("path", 1) }))
        assertTrue(numPath.isError)
        assertContains(numPath.text(), "path must be a string")
        val boolPath = provider.execute(request("fs__list_directory", buildJsonObject { put("path", true) }))
        assertTrue(boolPath.isError)
        val nullPath = provider.execute(request("fs__get_file_info", buildJsonObject { put("path", null) }))
        assertTrue(nullPath.isError)
        val numSort = provider.execute(
            request("fs__list_directory_with_sizes", buildJsonObject { put("path", "."); put("sortBy", 5) })
        )
        assertTrue(numSort.isError)
        assertContains(numSort.text(), "sortBy must be a string")
        val numPattern = provider.execute(
            request("fs__search_files", buildJsonObject { put("path", "."); put("pattern", 3) })
        )
        assertTrue(numPattern.isError)
        val numInPaths = provider.execute(
            request(
                "fs__read_multiple_files",
                buildJsonObject { putJsonArray("paths") { add("hello.txt"); add(42) } },
            )
        )
        assertTrue(numInPaths.isError)
        assertContains(numInPaths.text(), "paths must be an array of strings")
        val numInExcludes = provider.execute(
            request(
                "fs__directory_tree",
                buildJsonObject { put("path", "."); putJsonArray("excludePatterns") { add(1) } },
            )
        )
        assertTrue(numInExcludes.isError)
        assertContains(numInExcludes.text(), "excludePatterns must be an array of strings")
    }

    // ---------- construction fail-fast ----------

    @Test
    fun `construction fails fast on an unusable config`() {
        assertFailsWith<IllegalArgumentException> { FsToolProvider(emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            FsToolProvider(listOf(root.resolve("nope").toString()), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            FsToolProvider(listOf(root.resolve("hello.txt").toString()), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            FsToolProvider(listOf(root.toString()), listOf("  "))
        }
        assertFailsWith<IllegalArgumentException> {
            FsToolProvider(listOf(root.toString()), listOf("["))
        }
    }

    // ---------- helpers ----------

    private fun path(value: String) = "path" to value

    private fun request(name: String, vararg args: Pair<String, String>): ToolCallRequest =
        request(name, buildJsonObject { args.forEach { (key, value) -> put(key, value) } })

    private fun request(name: String, args: JsonObject): ToolCallRequest =
        ToolCallRequest(id = "t1", name = name, args = args)

    private fun ChatMessagePart.ToolResult.text(): String =
        parts.filterIsInstance<ChatMessagePart.Text>().joinToString("") { it.text }
}

/**
 * The DI wiring: the fs provider joins the chat loop's combined tool set
 * only when `tool.fs.enabled`, and a duplicate `fs` namespace (an MCP
 * server under the same namespace) fails fast.
 */
class FsToolProviderDiTest {

    @Test
    fun `the fs namespace joins the loop's tool set when enabled`() {
        val root = createTempDirectory("daapu-fs-di")
        try {
            val config = testAppConfig().copy(
                tool = ToolConfig(
                    fs = FsToolConfig(
                        enabled = true,
                        allowedDirs = listOf(root.toString()),
                        blacklists = listOf("**/.env"),
                    )
                ),
            )
            val app = testKoinApp(config)
            try {
                assertTrue("fs" in app.koin.get<CombinedToolProvider>().namespaces())
            } finally {
                app.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the fs namespace is absent when disabled`() {
        val app = testKoinApp()
        try {
            assertFalse("fs" in app.koin.get<CombinedToolProvider>().namespaces())
        } finally {
            app.close()
        }
    }

    @Test
    fun `a duplicate fs namespace fails fast in the combined provider`() {
        val root = createTempDirectory("daapu-fs-di")
        try {
            val fsProvider = FsToolProvider(listOf(root.toString()), emptyList())
            val fakeMcpFs = object : ToolProvider {
                override suspend fun specifications() = emptyList<ToolSpec>()
                override suspend fun execute(request: ToolCallRequest) = error("unused")
                override fun namespaces() = setOf("fs")
            }
            assertFails { CombinedToolProvider(listOf(fsProvider, fakeMcpFs)) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
