package info.skyblond.daapu.script.digest.transform

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Pins the SillyTavern transformer: the happy path must produce a chat the
 * stored-chat validation accepts (what the webui import enforces), and the
 * malformed-export cases must fail fast with the script's own messages.
 * Skipped lines (not JSON, or no send_date/mes) must warn on stderr with
 * their full content, so dropped material stays visible. [main]'s outputs
 * (the raw neutral format and the webui import payload) and its argument
 * validation are pinned too.
 */
class SillyTavernTransformerTest {

    private lateinit var tempDir: Path
    private lateinit var jsonlFile: Path
    private lateinit var imagesDir: Path

    /**
     * A real 1x1 PNG. The probed mime type comes from the `.png` NAME, not
     * these bytes: Files.probeContentType is extension/platform-based and
     * never sniffs content.
     */
    private val pngBytes = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    )

    private val prologueLine =
        """{"is_user": false, "send_date": "2026-01-01T10:00:00+08:00", "mes": "Welcome, traveler."}"""

    private val userLine =
        """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "Look at this", """ +
            """"extra": {"media": [{"type": "image", "url": "/user/images/pic.png"}]}}"""

    private val assistantLine =
        """{"is_user": false, "send_date": "2026-01-01T10:06:00+08:00", "mes": "Nice picture."}"""

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("daapu-st-test")
        imagesDir = tempDir.resolve("images")
        Files.createDirectories(imagesDir)
        Files.write(imagesDir.resolve("pic.png"), pngBytes)
        jsonlFile = tempDir.resolve("export.jsonl")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun writeJsonl(vararg lines: String) {
        Files.writeString(jsonlFile, lines.joinToString("\n"))
    }

    private fun transform(): List<ChatMessage> =
        transformSillyTavernChat(jsonlFile.toFile(), imagesDir.toFile())

    private fun captureStderr(block: () -> Unit): String {
        val captured = ByteArrayOutputStream()
        val original = System.err
        try {
            System.setErr(PrintStream(captured, true, Charsets.UTF_8))
            block()
        } finally {
            System.setErr(original)
        }
        return captured.toString(Charsets.UTF_8)
    }

    @Test
    fun `transforms an export into an importable chat`() {
        writeJsonl(prologueLine, userLine, assistantLine)
        val chat = transform()

        assertEquals(3, chat.size)

        // the first assistant message becomes the wrapped prologue
        val prologue = chat[0]
        assertEquals(ChatMessageRole.Assistant, prologue.role)
        val prologueText = assertIs<ChatMessagePart.Text>(prologue.parts.single())
        assertEquals(
            "<sillytavern-opening>\nWelcome, traveler.\n</sillytavern-opening>",
            prologueText.text
        )

        // the user message carries its ST send_date (UTC) and the image;
        // text first, then the attachment — the send path's order
        val user = chat[1]
        assertEquals(ChatMessageRole.User, user.role)
        assertEquals(Instant.parse("2026-01-01T02:05:00Z"), user.createdAt)
        assertEquals(2, user.parts.size)
        val userText = assertIs<ChatMessagePart.Text>(user.parts[0])
        assertEquals("Look at this", userText.text)
        val attachment = assertIs<ChatMessagePart.Attachment>(user.parts[1])
        assertEquals(AttachmentKind.Image, attachment.kind)
        assertEquals("image/png", attachment.mimeType)
        val content = assertIs<AttachmentContent.Base64>(attachment.content)
        assertContentEquals(pngBytes, Base64.getDecoder().decode(content.base64))

        // the assistant reply gets the zeroed meta + stop the codec requires
        val assistant = chat[2]
        assertEquals(ChatMessageRole.Assistant, assistant.role)
        assertEquals("stop", assistant.finishReason)
        assertEquals(0, assistant.meta?.inputTokens)

        // round-trips through the stored-chat validation: the webui import
        // would accept it
        assertEquals(chat, ChatCodec.decodeChat("test", ChatCodec.encodeChat(chat)))
    }

    @Test
    fun `parses the UTC Z-form send_date recent ST versions write`() {
        // toISOString() output: UTC with millis; the media url carries ST's
        // character subfolder, resolved by basename against the flat imagesDir
        val zUserLine =
            """{"is_user": true, "send_date": "2026-01-01T02:05:00.000Z", "mes": "Look at this", """ +
                """"extra": {"media": [{"type": "image", "url": "/user/images/Aria/pic.png"}]}}"""
        writeJsonl(prologueLine, zUserLine, assistantLine)
        val chat = transform()

        assertEquals(Instant.parse("2026-01-01T02:05:00Z"), chat[1].createdAt)
        val attachment = assertIs<ChatMessagePart.Attachment>(chat[1].parts[1])
        assertEquals(AttachmentKind.Image, attachment.kind)
    }

    @Test
    fun `skips lines without a send_date or mes`() {
        writeJsonl(prologueLine, """{"is_user": true, "mes": "no date"}""", userLine, assistantLine)
        assertEquals(3, transform().size)
    }

    @Test
    fun `warns on stderr with the full content of skipped lines`() {
        val skippedLine = """{"is_user": true, "mes": "no date"}"""
        writeJsonl(prologueLine, skippedLine, userLine, assistantLine)
        val warned = captureStderr { assertEquals(3, transform().size) }
        assertContains(warned, "[warn] Skipped line 2 (no send_date)")
        assertContains(warned, skippedLine)
    }

    @Test
    fun `skips lines that are not JSON objects`() {
        writeJsonl(prologueLine, "garbage a plugin appended", userLine, assistantLine)
        val warned = captureStderr { assertEquals(3, transform().size) }
        assertContains(warned, "[warn] Skipped line 2 (not a JSON object)")
    }

    @Test
    fun `rejects a non-ISO send_date naming the line and value`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "January 1, 2026 10:05am", "mes": "old style date"}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "Line 2")
        assertContains(e.message!!, "January 1, 2026 10:05am")
    }

    @Test
    fun `rejects an empty export`() {
        writeJsonl()
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "No messages")
    }

    @Test
    fun `rejects an export whose first message is a user message`() {
        writeJsonl(userLine, assistantLine)
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "prologue")
    }

    @Test
    fun `rejects an export ending with a user message`() {
        writeJsonl(prologueLine, userLine)
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "assistant reply")
    }

    @Test
    fun `rejects a missing image file`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "look", """ +
                """"extra": {"media": [{"type": "image", "url": "/user/images/nope.png"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "nope.png")
    }

    @Test
    fun `rejects a non-image media type`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "look", """ +
                """"extra": {"media": [{"type": "video", "url": "/user/images/clip.mp4"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "Unsupported media type video")
    }

    @Test
    fun `rejects a media entry without a type`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "look", """ +
                """"extra": {"media": [{"url": "/user/images/pic.png"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "without a type")
    }

    @Test
    fun `rejects an unexpected media url`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "look", """ +
                """"extra": {"media": [{"type": "image", "url": "https://example.com/pic.png"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "Unexpected media url")
    }

    @Test
    fun `rejects messages carrying extra files attachments`() {
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "read this", """ +
                """"extra": {"files": [{"url": "/user/files/note.txt", "size": 3, "name": "note.txt"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "extra.files")
    }

    @Test
    fun `rejects an image file whose probed mime type is not an image`() {
        Files.write(imagesDir.resolve("not-an-image.txt"), "not really an image".toByteArray())
        writeJsonl(
            prologueLine,
            """{"is_user": true, "send_date": "2026-01-01T10:05:00+08:00", "mes": "look", """ +
                """"extra": {"media": [{"type": "image", "url": "/user/images/not-an-image.txt"}]}}""",
            assistantLine
        )
        val e = assertFailsWith<IllegalArgumentException> { transform() }
        assertContains(e.message!!, "not-an-image.txt")
    }

    @Test
    fun `main writes both outputs with the default title`() {
        writeJsonl(prologueLine, userLine, assistantLine)
        val outputBase = tempDir.resolve("out").toString()
        main(arrayOf(jsonlFile.toString(), imagesDir.toString(), outputBase))
        val expected = transform()

        // the raw neutral format: decode-stable through the stored-chat codec
        assertEquals(
            expected,
            ChatCodec.decodeChat("test", tempDir.resolve("out.messages.json").toFile().readText())
        )

        // the webui import payload {title, messages}: the title defaults
        // to the export file's name
        val export = Json.parseToJsonElement(
            tempDir.resolve("out.export.json").toFile().readText()
        ).jsonObject
        assertEquals("export", export.getValue("title").jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(ChatCodec.encodeChat(expected)),
            export.getValue("messages"),
        )
    }

    @Test
    fun `main carries the given title into the export`() {
        writeJsonl(prologueLine, userLine, assistantLine)
        main(
            arrayOf(
                jsonlFile.toString(),
                imagesDir.toString(),
                tempDir.resolve("out2").toString(),
                "My chat"
            )
        )
        val export = Json.parseToJsonElement(
            tempDir.resolve("out2.export.json").toFile().readText()
        ).jsonObject
        assertEquals("My chat", export.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `main rejects wrong arg counts and blank titles`() {
        val usageError = assertFailsWith<IllegalArgumentException> {
            main(arrayOf(jsonlFile.toString(), imagesDir.toString()))
        }
        assertContains(usageError.message!!, "Usage")

        writeJsonl(prologueLine, userLine, assistantLine)
        val blankTitle = assertFailsWith<IllegalArgumentException> {
            main(
                arrayOf(
                    jsonlFile.toString(),
                    imagesDir.toString(),
                    tempDir.resolve("out3").toString(),
                    "  "
                )
            )
        }
        assertContains(blankTitle.message!!, "Title must not be blank")
    }
}
