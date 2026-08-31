package info.skyblond.daapu.agent.chat

import kotlin.io.encoding.Base64

/**
 * Serialize a data URL (`data:image/png;base64,...`) into a neutral image
 * attachment part. Throws [ChatValidationException] on a malformed URL or
 * payload, so callers validate requests early with a clear 400 instead of an
 * opaque gateway error mid-stream. Used by every request path that accepts
 * caller-supplied image data URLs (`ChatService.prepareRun`, the ELTM
 * import — see `server/endpoint/EltmRoute.kt`).
 */
internal fun parseImageDataUrl(dataUrl: String): ChatMessagePart.Attachment {
    val match = dataUrlRegex.matchEntire(dataUrl.trim())
        ?: throw ChatValidationException("Invalid image data URL")
    val mimeType = match.groupValues[1]
    val base64 = match.groupValues[2].filterNot { it.isWhitespace() }
    // validate early so a malformed payload fails with a clear 400 instead
    // of an opaque gateway error mid-stream
    runCatching { Base64.decode(base64) }
        .getOrElse { throw ChatValidationException("Invalid base64 in image data URL") }
    return ChatMessagePart.Attachment(
        kind = AttachmentKind.Image,
        content = AttachmentContent.Base64(base64),
        mimeType = mimeType,
    )
}

// `.+` with DOT_MATCHES_ALL: data URLs may fold base64 across lines
// (semantically `[\s\S]+`). Display-side mirror: DATA_URL_RE in
// frontend/src/lib/display.ts — update both patterns together
// (that copy only prunes non-image parts from the optimistic bubble;
// this one is authoritative).
private val dataUrlRegex = Regex(
    """^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$""",
    RegexOption.DOT_MATCHES_ALL,
)
