/**
 * Composer attachment processing, extracted from Composer.svelte so the
 * downscale/budget ladder is unit-testable without a DOM (the browser
 * primitives arrive through the injectable [ImageEncoder]; see
 * image-attachment.test.ts).
 *
 * Budget model (mirrors the frontend spec): a per-attachment byte cap
 * applies to the DOWNSCALED OUTPUT too — PNG alpha is flattened onto white
 * (behind the pixels, `destination-over`, so no black-background JPEG
 * artifact) and JPEG quality steps down until the result fits; a
 * still-oversized result is refused entirely (`too-large`) so nothing
 * balloons into reactive state / the request body. Images at or under the
 * longest-edge cap keep their ORIGINAL bytes (normal screenshots and
 * animated GIFs are untouched).
 */

/** Per-attachment byte budget (pre-base64): a 50 MB paste would balloon
 * into reactive state as a base64 string and blow up the request body. */
export const MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** Longest edge kept when downscaling; vision models gain nothing beyond this. */
export const MAX_IMAGE_EDGE = 1568

export type ImageAttachmentResult =
  { ok: true; dataUrl: string } | { ok: false; reason: 'not-an-image' | 'too-large' | 'unprocessable' }

/**
 * The browser primitives the pipeline needs. The real implementation is
 * [browserEncoder]; tests substitute fakes that record the canvas
 * operations and serve canned blob sizes.
 */
export interface ImageEncoder {
  /** Decode a file into a bitmap (browser: `createImageBitmap`). */
  createBitmap(file: File): Promise<ImageBitmap>
  /** A 2D canvas of the given pixel size, or null when unavailable. */
  createCanvas(width: number, height: number): { canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D } | null
  /** Encode a canvas to a blob (browser: `canvas.toBlob`). */
  toBlob(canvas: HTMLCanvasElement, mime: string, quality?: number): Promise<Blob | null>
  /** Read a blob (or the original file) into a data URL (browser: FileReader). */
  readAsDataUrl(blob: Blob): Promise<string>
}

/** The real browser implementation (createImageBitmap / canvas 2d / canvas.toBlob / FileReader). */
export const browserEncoder: ImageEncoder = {
  createBitmap: (file) => createImageBitmap(file),
  createCanvas: (width, height) => {
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const ctx = canvas.getContext('2d')
    return ctx ? { canvas, ctx } : null
  },
  toBlob: (canvas, mime, quality) => new Promise((resolve) => canvas.toBlob(resolve, mime, quality)),
  readAsDataUrl: (blob) =>
    new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error ?? new Error('read failed'))
      reader.readAsDataURL(blob)
    }),
}

/**
 * Encode the downscaled bitmap within [maxBytes]. The primary format keeps
 * PNG alpha; if that still exceeds the budget (noise-heavy sources do, even
 * at the edge cap), alpha is flattened onto white BEHIND the pixels
 * (`destination-over` — no black-background JPEG artifact) and lower-quality
 * JPEG steps run until it fits. Returns the data URL, or NULL when the
 * result is still oversized after the full ladder. Throws only when
 * encoding itself failed.
 */
async function encodeWithinBudget(
  canvas: HTMLCanvasElement,
  ctx: CanvasRenderingContext2D,
  keepAlpha: boolean,
  enc: ImageEncoder,
  maxBytes: number,
): Promise<string | null> {
  let blob = await enc.toBlob(canvas, keepAlpha ? 'image/png' : 'image/jpeg', 0.85)
  if (blob && blob.size > maxBytes && keepAlpha) {
    ctx.globalCompositeOperation = 'destination-over'
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.globalCompositeOperation = 'source-over'
    blob = await enc.toBlob(canvas, 'image/jpeg', 0.85)
  }
  for (const quality of [0.7, 0.55, 0.4]) {
    if (!blob || blob.size <= maxBytes) break
    blob = await enc.toBlob(canvas, 'image/jpeg', quality)
  }
  if (!blob) throw new Error('encode failed')
  if (blob.size <= maxBytes) return await enc.readAsDataUrl(blob)
  return null
}

/**
 * One attachment file → data URL result. Oversized images are downscaled on
 * a canvas to the edge cap and then forced through [encodeWithinBudget]
 * (the budget applies to the OUTPUT too — PNG alpha flattens onto white,
 * JPEG quality steps down, a still-oversized result is refused);
 * everything at or under the edge passes through with its ORIGINAL bytes,
 * so normal screenshots/animated GIFs are untouched. Decode/encode
 * failures are `unprocessable` (unusable in the optimistic bubble AND
 * likely rejected by the backend anyway).
 */
export async function imageFileToDataUrl(
  file: File,
  enc: ImageEncoder,
  opts: { maxBytes?: number; maxEdge?: number } = {},
): Promise<ImageAttachmentResult> {
  const maxBytes = opts.maxBytes ?? MAX_IMAGE_BYTES
  const maxEdge = opts.maxEdge ?? MAX_IMAGE_EDGE
  if (!file.type.startsWith('image/')) return { ok: false, reason: 'not-an-image' }
  if (file.size > maxBytes) return { ok: false, reason: 'too-large' }
  let bitmap: ImageBitmap | null = null
  try {
    bitmap = await enc.createBitmap(file)
    const longest = Math.max(bitmap.width, bitmap.height)
    if (longest <= maxEdge) {
      return { ok: true, dataUrl: await enc.readAsDataUrl(file) }
    }
    const scale = maxEdge / longest
    const target = enc.createCanvas(Math.round(bitmap.width * scale), Math.round(bitmap.height * scale))
    if (!target) throw new Error('canvas unavailable')
    const { canvas, ctx } = target
    const keepAlpha = file.type === 'image/png'
    if (!keepAlpha) {
      // JPEG has no alpha channel: paint the background first
      ctx.fillStyle = '#ffffff'
      ctx.fillRect(0, 0, canvas.width, canvas.height)
    }
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    const dataUrl = await encodeWithinBudget(canvas, ctx, keepAlpha, enc, maxBytes)
    if (dataUrl === null) return { ok: false, reason: 'too-large' }
    return { ok: true, dataUrl }
  } catch {
    return { ok: false, reason: 'unprocessable' }
  } finally {
    bitmap?.close()
  }
}
