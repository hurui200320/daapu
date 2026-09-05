package info.skyblond.daapu.agent.chat

import kotlin.io.encoding.Base64

// the image-MIME shape (`image/<subtype>`), the MIME half of [dataUrlRegex]:
// the single source for both the regex below and the ELTM digest route's
// attachment mimeType check (see server/endpoint/EltmRoute.kt)
private const val IMAGE_MIME_PATTERN = "image/[a-zA-Z0-9.+-]+"

/** The digestible image MIME shape; match with [Regex.matchEntire]. */
internal val imageMimeTypeRegex = Regex(IMAGE_MIME_PATTERN)

/**
 * Serialize a data URL (`data:image/png;base64,...`) into a neutral image
 * attachment part. Throws [ChatValidationException] on a malformed URL or
 * payload, so callers validate requests early with a clear 400 instead of an
 * opaque gateway error mid-stream. Used by the chat-send path
 * (`ChatService.prepareRun`) — the only request path that accepts
 * caller-supplied image data URLs; the ELTM digest
 * (`server/endpoint/EltmRoute.kt`) takes base64 attachment parts instead and
 * validates them itself (its mimeType check shares [imageMimeTypeRegex]).
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
    "^data:(" + IMAGE_MIME_PATTERN + ");base64,(.+)$",
    RegexOption.DOT_MATCHES_ALL,
)
