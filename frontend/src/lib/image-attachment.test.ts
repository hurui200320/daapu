import { describe, expect, it } from 'vitest'
import { imageFileToDataUrl, type ImageEncoder } from './image-attachment'

/** A File stand-in: the pipeline only reads type/size and passes the reference back to readAsDataUrl. */
function fakeFile(type: string, size: number): File {
  return { type, size } as unknown as File
}

/**
 * Fake encoder: records the pipeline's canvas operations as a log and serves
 * canned blob sizes per (mime, quality). `flattened` flips when the pipeline
 * paints white BEHIND the pixels (destination-over) — the alpha-flatten step.
 */
function fakeEncoder(opts: {
  bitmap: { width: number; height: number } | Error
  canvasAvailable?: boolean
  /** Encoded size in bytes per (mime, quality); null = encode failure. */
  blobBytes?: (mime: string, quality?: number) => number | null
  file: File
}) {
  const log: string[] = []
  let flattened = false
  const enc: ImageEncoder = {
    createBitmap: async () => {
      log.push('decode')
      if (opts.bitmap instanceof Error) throw opts.bitmap
      return {
        width: opts.bitmap.width,
        height: opts.bitmap.height,
        close: () => {},
      } as unknown as ImageBitmap
    },
    createCanvas: (width, height) => {
      if (opts.canvasAvailable === false) return null
      log.push(`canvas:${width}x${height}`)
      return {
        canvas: { width, height } as HTMLCanvasElement,
        ctx: {
          set globalCompositeOperation(value: string) {
            if (value === 'destination-over') flattened = true
          },
          fillRect: () => log.push('fill'),
          drawImage: () => log.push('draw'),
        } as unknown as CanvasRenderingContext2D,
      }
    },
    toBlob: async (_canvas, mime, quality) => {
      const size = opts.blobBytes?.(mime, quality) ?? null
      log.push(`encode:${mime}${quality != null ? '@' + quality : ''}=${size === null ? 'fail' : size}`)
      return size === null ? null : ({ size } as Blob)
    },
    readAsDataUrl: async (blob) => {
      log.push(blob === opts.file ? 'read:file' : 'read:blob')
      return 'data:image/png;base64,QUJD'
    },
  }
  return { enc, log, isFlattened: () => flattened }
}

describe('imageFileToDataUrl', () => {
  // small budgets/edges so the fakes stay readable
  const OPTS = { maxBytes: 100, maxEdge: 1000 }

  it('skips non-image types without touching the decoder', async () => {
    const file = fakeFile('text/plain', 10)
    const { enc, log } = fakeEncoder({ bitmap: { width: 10, height: 10 }, file })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'not-an-image' })
    expect(log).toEqual([])
  })

  it('rejects files over the byte budget before decoding', async () => {
    const file = fakeFile('image/png', 101)
    const { enc, log } = fakeEncoder({ bitmap: { width: 10, height: 10 }, file })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'too-large' })
    expect(log).toEqual([])
  })

  it('passes at-or-under-edge images through with their original bytes', async () => {
    const file = fakeFile('image/png', 50)
    const { enc, log } = fakeEncoder({ bitmap: { width: 800, height: 600 }, file })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({
      ok: true,
      dataUrl: 'data:image/png;base64,QUJD',
    })
    // decode + the ORIGINAL file: no canvas, no re-encode
    expect(log).toEqual(['decode', 'read:file'])
  })

  it('downscales to the longest edge and keeps png alpha when it fits', async () => {
    const file = fakeFile('image/png', 50)
    const { enc, log, isFlattened } = fakeEncoder({
      bitmap: { width: 2000, height: 1500 }, // scale 0.5 → 1000x750
      file,
      blobBytes: () => 10,
    })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({
      ok: true,
      dataUrl: 'data:image/png;base64,QUJD',
    })
    expect(log).toContain('canvas:1000x750')
    expect(log.some((l) => l.startsWith('encode:image/png'))).toBe(true)
    expect(isFlattened()).toBe(false)
    // the ENCODED blob is read back, not the original file
    expect(log.at(-1)).toBe('read:blob')
  })

  it('flattens png alpha onto white when the png exceeds the budget', async () => {
    const file = fakeFile('image/png', 50)
    const { enc, isFlattened } = fakeEncoder({
      bitmap: { width: 2000, height: 1500 },
      file,
      blobBytes: (mime) => (mime === 'image/png' ? 101 : 10), // png too big → jpeg fits
    })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({
      ok: true,
      dataUrl: 'data:image/png;base64,QUJD',
    })
    expect(isFlattened()).toBe(true)
  })

  it('paints the background before drawing for non-png sources', async () => {
    const file = fakeFile('image/jpeg', 50)
    const { enc, log } = fakeEncoder({
      bitmap: { width: 2000, height: 1500 },
      file,
      blobBytes: () => 10,
    })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({
      ok: true,
      dataUrl: 'data:image/png;base64,QUJD',
    })
    expect(log.indexOf('fill')).toBeGreaterThanOrEqual(0)
    expect(log.indexOf('fill')).toBeLessThan(log.indexOf('draw'))
  })

  it('steps the jpeg quality down until the result fits', async () => {
    const file = fakeFile('image/jpeg', 50)
    const { enc, log } = fakeEncoder({
      bitmap: { width: 2000, height: 1500 },
      file,
      blobBytes: (_mime, quality) => (quality === 0.55 ? 10 : 101),
    })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({
      ok: true,
      dataUrl: 'data:image/png;base64,QUJD',
    })
    const qualities = log.filter((l) => l.startsWith('encode:image/jpeg')).map((l) => l.split('@')[1]?.split('=')[0])
    expect(qualities).toEqual(['0.85', '0.7', '0.55'])
  })

  it('refuses the attachment when nothing fits the budget', async () => {
    const file = fakeFile('image/png', 50)
    const { enc } = fakeEncoder({ bitmap: { width: 2000, height: 1500 }, file, blobBytes: () => 101 })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'too-large' })
  })

  it('reports a failed encode as unprocessable', async () => {
    const file = fakeFile('image/png', 50)
    const { enc } = fakeEncoder({ bitmap: { width: 2000, height: 1500 }, file, blobBytes: () => null })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'unprocessable' })
  })

  it('reports a decode failure as unprocessable', async () => {
    const file = fakeFile('image/png', 50)
    const { enc } = fakeEncoder({ bitmap: new Error('unsupported format'), file })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'unprocessable' })
  })

  it('reports an unavailable canvas as unprocessable', async () => {
    const file = fakeFile('image/png', 50)
    const { enc } = fakeEncoder({ bitmap: { width: 2000, height: 1500 }, canvasAvailable: false, file })
    await expect(imageFileToDataUrl(file, enc, OPTS)).resolves.toEqual({ ok: false, reason: 'unprocessable' })
  })
})
