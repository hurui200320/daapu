import { cn } from '../../utils'

/**
 * Shared message chrome class strings (MessageItem/MessageList). The same
 * recipes used to be inlined per call site; a constants module keeps them in
 * lockstep, like ui/dropdown-styles.ts does for the dropdowns.
 */

/** Click-to-zoom button around an inline image attachment (an ImageLightbox trigger). */
export const lightboxTriggerBtn = (...extra: (string | false | undefined)[]) =>
  cn(
    'inline-block max-w-full cursor-zoom-in overflow-hidden rounded-lg',
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
    ...extra,
  )

/** Monospace block for tool args / tool-result text (scroll + wrap + code colors). */
export const toolPreBlock = (...extra: (string | false | undefined)[]) =>
  cn(
    'max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-code-background p-3 font-mono text-xs leading-5 text-code-foreground',
    ...extra,
  )
