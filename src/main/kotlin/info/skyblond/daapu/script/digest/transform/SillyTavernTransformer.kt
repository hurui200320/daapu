package info.skyblond.daapu.script.digest.transform

import info.skyblond.daapu.agent.chat.AttachmentContent
import info.skyblond.daapu.agent.chat.AttachmentKind
import info.skyblond.daapu.agent.chat.ChatCodec
import info.skyblond.daapu.agent.chat.ChatMessage
import info.skyblond.daapu.agent.chat.ChatMessageMeta
import info.skyblond.daapu.agent.chat.ChatMessagePart
import info.skyblond.daapu.agent.chat.ChatMessageRole.Assistant
import info.skyblond.daapu.agent.chat.ChatMessageRole.User
import info.skyblond.daapu.agent.chat.imageMimeTypeRegex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor
import java.util.Base64

/**
 * A dev-time utility, not part of the server. Invocation (through the Gradle
 * runner) and the produced files are documented in `script/README.md`.
 */
private val json = Json {
    allowComments = true
    allowTrailingComma = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun File.contentToBase64(): String =
    Base64.getEncoder().encodeToString(this.readBytes())

/**
 * Announce a skipped JSONL line on stderr (lines that are not JSON objects,
 * or objects without `send_date`/`mes`: the ST header, truncated or
 * plugin-appended lines). The FULL line content goes along, so the user
 * can review what was dropped — the transform itself carries on without
 * it.
 */
private fun warnSkippedLine(lineNumber: Int, line: String, reason: String) {
    System.err.println("[warn] Skipped line $lineNumber ($reason): $line")
}

private data class SillyTavernMessage(
    val isUser: Boolean,
    val sendDate: TemporalAccessor,
    val message: String,
    val images: List<File>
) {
    private fun toParts(
        isProlog: Boolean,
    ): List<ChatMessagePart> {
        val result = mutableListOf<ChatMessagePart>()
        // text first, then attachments — the order the chat-send path
        // builds its user messages (see ChatService.prepareRun)
        result.add(
            ChatMessagePart.Text(
                if (isProlog) "<sillytavern-opening>\n$message\n</sillytavern-opening>"
                else message
            )
        )
        images.forEach {
            // the probe is extension/platform-based and can yield a non-image
            // type (e.g. application/octet-stream); gate it here with the
            // shared image mime shape, so a bad one fails in the script
            // instead of surfacing as an opaque provider error mid-run
            val mimeType = requireNotNull(Files.probeContentType(it.toPath())) {
                "Cannot determine the mime type of image $it"
            }
            require(imageMimeTypeRegex.matchEntire(mimeType) != null) {
                "Probed mime type of $it is not an image mime type: $mimeType"
            }
            result.add(
                ChatMessagePart.Attachment(
                    kind = AttachmentKind.Image,
                    mimeType = mimeType,
                    content = AttachmentContent.Base64(
                        base64 = it.contentToBase64()
                    )
                )
            )
        }
        return result
    }

    fun toChatMessage(
        isProlog: Boolean,
    ): ChatMessage = ChatMessage(
        role = if (isUser) User else Assistant,
        parts = toParts(isProlog),
        createdAt = if (isUser) Instant.from(sendDate) else null,
        meta = if (isUser) null else ChatMessageMeta(
            0, 0, 0, null
        ),
        finishReason = if (isUser) null else "stop",
    )
}

/**
 * Transform a SillyTavern chat export (JSONL) and its uploaded images into
 * the neutral chat format ([ChatMessage]) — a dev-time utility, not part of
 * the server; see `script/README.md` for invocation and outputs.
 *
 * CLI contract: `<st-export.jsonl> <imagesDir> <outputBase> [title]`, where
 * `title` defaults to the export file's name and must be non-blank. One run
 * writes BOTH outputs (see `script/README.md` for their semantics).
 */
fun main(args: Array<String>) {
    if (args.size !in 3..4) {
        throw IllegalArgumentException(
            "Usage: <st-export.jsonl> <imagesDir> <outputBase> [title] — see script/README.md"
        )
    }
    val chatJsonLineFile = File(args[0])
    val imageFolder = File(args[1])
    val outputBase = args[2]
    val title = args.getOrElse(3) { chatJsonLineFile.nameWithoutExtension }
    require(title.isNotBlank()) { "Title must not be blank" }

    val chat = transformSillyTavernChat(chatJsonLineFile, imageFolder)
    val encodedChat = ChatCodec.encodeChat(chat)

    // the raw neutral format, byte-identical to what GET /api/chats/{id}/chat
    // serves — for processing by code
    File("$outputBase.messages.json").writeText(encodedChat)

    // the `{title, messages}` payload the webui's import accepts, mirroring
    // GET /api/chats/{id}/export (see server/endpoint/ChatsRoute.kt)
    val exportJsonObj = buildJsonObject {
        put("title", title)
        put("messages", Json.parseToJsonElement(encodedChat))
    }
    File("$outputBase.export.json").writeText(json.encodeToString(JsonObject.serializer(), exportJsonObj))
}

/**
 * The pure transform behind [main]: parse [chatJsonLineFile] (one JSON
 * object per line), resolve each message's `extra.media` entries against
 * [imageFolder], and build the [ChatMessage] list.
 *
 * Input requirements: every message line's `send_date` must be an ISO 8601
 * date-time — recent ST versions write UTC `toISOString()` output (e.g.
 * `2026-01-01T02:05:00.000Z`); local-offset ISO forms (e.g.
 * `2026-01-01T10:05:00+08:00`) are accepted too. Older exports' humanized
 * or epoch-millis timestamps are NOT supported and fail fast, naming the
 * line and the offending value.
 *
 * Lines that are not JSON objects, or without `send_date`/`mes` — the ST
 * header line, truncated lines, or whatever ST plugins append — are
 * skipped, each announced by [warnSkippedLine] on stderr with the full
 * line content, so dropped material stays visible.
 *
 * The first message must be the assistant prologue (wrapped in
 * `<sillytavern-opening>` tags); every user message carries its ST
 * `send_date` as `createdAt`, and every assistant message gets zeroed
 * usage meta plus `finishReason = "stop"` (both required on stored
 * assistant messages, see `ChatCodec`). Fails fast on non-image media,
 * media entries without a type, unexpected media urls, missing image
 * files, image files whose probed mime type is not an image, messages
 * carrying `extra.files` attachments (documents are not supported), and
 * on exports that do not start with an assistant prologue or end with an
 * assistant reply — the last two are what `ChatCodec.validateChat` would
 * reject at import time, reported here with a clearer message.
 */
internal fun transformSillyTavernChat(
    chatJsonLineFile: File,
    imageFolder: File,
): List<ChatMessage> {
    val rawMessages: List<SillyTavernMessage> = chatJsonLineFile.useLines { lines ->
        lines.mapIndexedNotNull { index, line ->
            val lineNumber = index + 1
            val obj = try {
                json.decodeFromString(JsonObject.serializer(), line)
            } catch (_: SerializationException) {
                warnSkippedLine(lineNumber, line, "not a JSON object")
                return@mapIndexedNotNull null
            }
            val isUser = obj["is_user"]?.jsonPrimitive?.booleanOrNull ?: false
            val sendDateStr = obj["send_date"]?.jsonPrimitive?.contentOrNull
            if (sendDateStr == null) {
                warnSkippedLine(lineNumber, line, "no send_date")
                return@mapIndexedNotNull null
            }
            val sendDate = try {
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(sendDateStr)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException(
                    "Line $lineNumber: cannot parse send_date '$sendDateStr' as an ISO 8601 " +
                        "date-time (e.g. 2026-01-01T02:05:00.000Z, what recent ST versions " +
                        "write, or 2026-01-01T10:05:00+08:00) — older ST export formats " +
                        "(humanized dates, epoch millis) are not supported",
                    e,
                )
            }
            val message = obj["mes"]?.jsonPrimitive?.contentOrNull
            if (message == null) {
                warnSkippedLine(lineNumber, line, "no mes")
                return@mapIndexedNotNull null
            }

            val extra = obj["extra"]?.jsonObject
            // document attachments ride in `extra.files`, separate from the
            // `media` array: reject them instead of silently dropping
            val files = extra?.get("files") as? JsonArray
            require(files.isNullOrEmpty()) {
                "Line $lineNumber: extra.files carries file attachments, which are not " +
                    "supported: $files"
            }
            val media = extra?.get("media")?.jsonArray?.map { item ->
                val type = requireNotNull(item.jsonObject["type"]?.jsonPrimitive?.contentOrNull) {
                    "Media entry without a type: $item"
                }
                require(type == "image") {
                    "Unsupported media type $type"
                }
                val urlStr = item.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: ""
                require(urlStr.startsWith("/user/images/")) {
                    "Unexpected media url: $urlStr"
                }
                val filename = File(urlStr).name
                val file = File(imageFolder, filename)
                require(file.exists() && file.isFile) {
                    "Image file does not exist: $file"
                }
                file
            }

            SillyTavernMessage(
                isUser = isUser,
                sendDate = sendDate,
                message = message,
                images = media ?: emptyList()
            )
        }.toList()
    }

    require(rawMessages.isNotEmpty()) { "No messages found in $chatJsonLineFile" }
    require(!rawMessages.first().isUser) {
        "First message should be prologue, but is user message: ${rawMessages.first()}"
    }
    require(!rawMessages.last().isUser) {
        "Last message is a user message: the export must end with an assistant reply"
    }

    return rawMessages.mapIndexed { index, msg ->
        msg.toChatMessage(isProlog = index == 0)
    }
}
