package info.skyblond.daapu.koog

import ai.koog.prompt.message.AttachmentContent as KoogAttachmentContent
import ai.koog.prompt.message.AttachmentSource as KoogAttachmentSource
import ai.koog.prompt.message.Message as KoogMessage
import ai.koog.prompt.message.MessagePart as KoogMessagePart
import ai.koog.prompt.message.RequestMetaInfo as KoogRequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo as KoogResponseMetaInfo
import info.skyblond.daapu.history.AttachmentContent
import info.skyblond.daapu.history.AttachmentKind
import info.skyblond.daapu.history.HistoryMeta
import info.skyblond.daapu.history.HistoryMessage
import info.skyblond.daapu.history.HistoryPart
import info.skyblond.daapu.history.HistoryRole
import kotlin.time.Instant

/**
 * One-way converters between koog's `Message` model and the framework-neutral
 * history DTOs (`info.skyblond.daapu.history`).
 *
 * The mapping is a bijection for every shape this application produces:
 *
 * - koog `Message.System` ↔ `role = system`
 * - koog `Message.User` ↔ `role = user`, except a user message consisting
 *   solely of `Tool.Result` parts ↔ `role = tool` (koog has no tool message
 *   type; tool results ride inside `User`). A mixed message is split into
 *   order-preserving runs of user parts and tool-result parts.
 * - koog `Message.Assistant` ↔ `role = assistant`, carrying `finishReason`
 *   and `ResponseMetaInfo` token usage. Every assistant message the app
 *   stores carries a non-blank `finishReason` (the streaming client only
 *   emits the terminal frame when the gateway sent a `finish_reason`, see
 *   `CustomOpenAILLMClient`); [HistoryCodec] fails fast at decode if a row
 *   lacks one, so a future non-streaming node that appends an assistant
 *   message without a finish reason is caught at the next load instead of
 *   silently bricking the chat.
 * - `MessagePart.Text/Reasoning/Tool.Call/Tool.Result/Attachment` ↔ the
 *   corresponding [HistoryPart] variants.
 *
 * Deliberately dropped (never populated in this codebase, so dropping is
 * lossless for real data): `Message.id`, `metaInfo.metadata`,
 * `MessagePart.cacheControl`, `Message.Assistant.rawResponse`, and
 * `Reasoning.summary/encrypted/id`.
 * The `RequestMetaInfo.Empty`/`ResponseMetaInfo.Empty` sentinels normalize to
 * an absent [HistoryMeta] so the stored JSON stays clean.
 */
fun List<KoogMessage>.toNeutralHistory(): List<HistoryMessage> = flatMap { it.toNeutralMessages() }

private fun KoogMessage.toNeutralMessages(): List<HistoryMessage> = when (this) {
    is KoogMessage.System -> listOf(
        HistoryMessage(
            role = HistoryRole.System,
            parts = parts.map { HistoryPart.Text(it.text) },
            meta = metaInfo.toNeutralMeta(),
        )
    )

    is KoogMessage.User -> {
        // split into order-preserving runs: tool-result parts become `tool`
        // messages, everything else stays `user`
        val messages = mutableListOf<HistoryMessage>()
        var runRole: HistoryRole? = null
        var runParts = mutableListOf<HistoryPart>()
        parts.forEach { part ->
            val role = if (part is KoogMessagePart.Tool.Result) HistoryRole.Tool else HistoryRole.User
            if (runRole != role) {
                if (runRole != null) {
                    messages += HistoryMessage(role = runRole, parts = runParts, meta = metaInfo.toNeutralMeta())
                }
                runRole = role
                runParts = mutableListOf()
            }
            runParts += part.toNeutralPart()
        }
        if (runRole != null) {
            messages += HistoryMessage(role = runRole, parts = runParts, meta = metaInfo.toNeutralMeta())
        }
        messages
    }

    is KoogMessage.Assistant -> listOf(
        HistoryMessage(
            role = HistoryRole.Assistant,
            parts = parts.map { it.toNeutralPart() },
            meta = metaInfo.toNeutralMeta(),
            finishReason = finishReason,
        )
    )
}

private fun KoogMessagePart.toNeutralPart(): HistoryPart = when (this) {
    is KoogMessagePart.Text -> HistoryPart.Text(text)
    is KoogMessagePart.Reasoning -> HistoryPart.Reasoning(content)
    is KoogMessagePart.Tool.Call -> HistoryPart.ToolCall(
        // `withGeneratedToolCallIds` guarantees an id on every accepted
        // message; refuse to store a call without one, since koog would
        // re-send it with mismatched random ids and strict providers would
        // reject the history forever
        id = id.requireToolId("tool_call"),
        tool = tool,
        args = args,
    )
    is KoogMessagePart.Tool.Result -> HistoryPart.ToolResult(
        id = id.requireToolId("tool_result"),
        tool = tool,
        parts = parts.map { it.toNeutralContentPart() },
        isError = isError,
    )
    is KoogMessagePart.Attachment -> toNeutralAttachment()
}

private fun String?.requireToolId(kind: String): String =
    if (this.isNullOrBlank()) {
        throw IllegalStateException(
            "Refusing to store a $kind without a stable non-blank id: koog would re-send it " +
                    "with mismatched random ids and strict providers would reject the history " +
                    "forever. Fix the tool-call id upstream (withGeneratedToolCallIds)."
        )
    } else {
        this
    }

private fun KoogMessagePart.ContentPart.toNeutralContentPart(): HistoryPart.ContentPart = when (this) {
    is KoogMessagePart.Text -> HistoryPart.Text(text)
    is KoogMessagePart.Attachment -> toNeutralAttachment()
}

private fun KoogMessagePart.Attachment.toNeutralAttachment(): HistoryPart.Attachment =
    HistoryPart.Attachment(
        kind = when (source) {
            is KoogAttachmentSource.Image -> AttachmentKind.Image
            is KoogAttachmentSource.Video -> AttachmentKind.Video
            is KoogAttachmentSource.Audio -> AttachmentKind.Audio
            is KoogAttachmentSource.File -> AttachmentKind.File
        },
        content = when (val content = source.content) {
            is KoogAttachmentContent.PlainText -> AttachmentContent.PlainText(content.text)
            is KoogAttachmentContent.URL -> throw IllegalStateException(
                "Refusing to store a URL attachment (${content.url}): the neutral format does not " +
                        "support external URLs: nothing in this app produces them, and allowing " +
                        "stored external resources into the system is a risk we don't need yet. " +
                        "Re-add AttachmentContent.Url when a real use case exists."
            )
            is KoogAttachmentContent.Binary.Bytes -> AttachmentContent.Base64(content.asBase64())
            is KoogAttachmentContent.Binary.Base64 -> AttachmentContent.Base64(content.base64)
        },
        format = source.format,
        mimeType = source.mimeType,
        fileName = source.fileName,
    )

private fun KoogRequestMetaInfo.toNeutralMeta(): HistoryMeta? =
    if (this == KoogRequestMetaInfo.Empty) {
        null
    } else {
        HistoryMeta(timestamp = timestamp.toString())
    }

private fun KoogResponseMetaInfo.toNeutralMeta(): HistoryMeta? =
    if (this == KoogResponseMetaInfo.Empty) {
        null
    } else {
        HistoryMeta(
            timestamp = timestamp.toString(),
            inputTokens = inputTokensCount,
            outputTokens = outputTokensCount,
            totalTokens = totalTokensCount,
            modelId = modelId,
        )
    }

fun List<HistoryMessage>.toKoogHistory(): List<KoogMessage> = map { it.toKoogMessage() }

private fun HistoryMessage.toKoogMessage(): KoogMessage = when (role) {
    HistoryRole.System -> KoogMessage.System(
        parts = parts.map { it.asSystemTextPart() },
        metaInfo = meta.toKoogRequestMetaInfo(),
    )

    HistoryRole.User -> KoogMessage.User(
        parts = parts.map { it.toKoogRequestPart() },
        metaInfo = meta.toKoogRequestMetaInfo(),
    )

    HistoryRole.Tool -> KoogMessage.User(
        parts = parts.map { it.toKoogToolResultPart() },
        metaInfo = meta.toKoogRequestMetaInfo(),
    )

    HistoryRole.Assistant -> KoogMessage.Assistant(
        parts = parts.map { it.toKoogResponsePart() },
        metaInfo = meta.toKoogResponseMetaInfo(),
        finishReason = finishReason,
    )
}

private fun HistoryPart.asSystemTextPart(): KoogMessagePart.Text = when (this) {
    is HistoryPart.Text -> KoogMessagePart.Text(text)
    else -> error("System messages can only contain text parts, got: $this")
}

private fun HistoryPart.toKoogRequestPart(): KoogMessagePart.RequestPart = when (this) {
    is HistoryPart.Text -> KoogMessagePart.Text(text)
    is HistoryPart.Attachment -> toKoogAttachment()
    is HistoryPart.ToolResult -> toKoogToolResult()
    else -> error("User messages can only contain text, attachment and tool_result parts, got: $this")
}

private fun HistoryPart.toKoogResponsePart(): KoogMessagePart.ResponsePart = when (this) {
    is HistoryPart.Text -> KoogMessagePart.Text(text)
    is HistoryPart.Attachment -> toKoogAttachment()
    is HistoryPart.Reasoning -> KoogMessagePart.Reasoning(content = content)
    is HistoryPart.ToolCall -> KoogMessagePart.Tool.Call(id = id, tool = tool, args = args)
    else -> error("Assistant messages can only contain text, attachment, reasoning and tool_call parts, got: $this")
}

private fun HistoryPart.toKoogToolResultPart(): KoogMessagePart.Tool.Result = when (this) {
    is HistoryPart.ToolResult -> toKoogToolResult()
    else -> error("Tool messages can only contain tool_result parts, got: $this")
}

private fun HistoryPart.ToolResult.toKoogToolResult(): KoogMessagePart.Tool.Result =
    KoogMessagePart.Tool.Result(
        id = id,
        tool = tool,
        parts = parts.map { it.toKoogContentPart() },
        isError = isError,
    )

private fun HistoryPart.ContentPart.toKoogContentPart(): KoogMessagePart.ContentPart = when (this) {
    is HistoryPart.Text -> KoogMessagePart.Text(text)
    is HistoryPart.Attachment -> toKoogAttachment()
}

private fun HistoryPart.Attachment.toKoogAttachment(): KoogMessagePart.Attachment =
    KoogMessagePart.Attachment(
        source = when (kind) {
            AttachmentKind.Image -> KoogAttachmentSource.Image(
                content = content.toKoogContent(),
                format = format,
                mimeType = mimeType,
                fileName = fileName,
            )

            AttachmentKind.Video -> KoogAttachmentSource.Video(
                content = content.toKoogContent(),
                format = format,
                mimeType = mimeType,
                fileName = fileName,
            )

            AttachmentKind.Audio -> KoogAttachmentSource.Audio(
                content = content.toKoogContent(),
                format = format,
                mimeType = mimeType,
                fileName = fileName,
            )

            AttachmentKind.File -> KoogAttachmentSource.File(
                content = content.toKoogContent(),
                format = format,
                mimeType = mimeType,
                fileName = fileName,
            )
        }
    )

private fun AttachmentContent.toKoogContent(): KoogAttachmentContent = when (this) {
    is AttachmentContent.Base64 -> KoogAttachmentContent.Binary.Base64(base64)
    is AttachmentContent.PlainText -> KoogAttachmentContent.PlainText(text)
}

private fun HistoryMeta?.toKoogRequestMetaInfo(): KoogRequestMetaInfo =
    if (this == null) {
        KoogRequestMetaInfo.Empty
    } else {
        KoogRequestMetaInfo(timestamp = timestamp.toKoogInstant())
    }

private fun HistoryMeta?.toKoogResponseMetaInfo(): KoogResponseMetaInfo =
    if (this == null) {
        KoogResponseMetaInfo.Empty
    } else {
        KoogResponseMetaInfo(
            timestamp = timestamp.toKoogInstant(),
            totalTokensCount = totalTokens,
            inputTokensCount = inputTokens,
            outputTokensCount = outputTokens,
            modelId = modelId,
        )
    }

// a null timestamp can only come from a non-koog source (koog always has one);
// fall back to the Empty sentinel's timestamp to stay total
private fun String?.toKoogInstant(): Instant = this?.let { Instant.parse(it) } ?: Instant.DISTANT_PAST
