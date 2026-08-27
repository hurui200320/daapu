import { cn } from '../../utils'

/**
 * Shared bits-ui dropdown chrome. The trigger/content/item style strings
 * were duplicated across Sidebar's chat menu, ModelDropdown and
 * PersonaDropdown; a constants module keeps them in lockstep without a
 * wrapper component hiding the DropdownMenu primitives.
 */

/** Chip-style trigger base used by the model/persona pickers in the composer. */
export const dropdownChipTrigger = (...extra: (string | false | undefined)[]) =>
  cn(
    'inline-flex h-8 min-w-0 items-center gap-1.5 rounded-md bg-muted px-2 text-xs text-foreground transition-colors',
    'hover:bg-muted-foreground/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
    'disabled:pointer-events-none disabled:opacity-50',
    ...extra,
  )

/** Popover panel base; pass the width classes per dropdown. */
export const dropdownContentPanel = (...extra: (string | false | undefined)[]) =>
  cn('z-50 rounded-lg border border-border bg-popover p-1.5 text-popover-foreground shadow-md', ...extra)

/** Interactive menu-item row (sidebar chat actions, dropdown options). */
export const dropdownItemClass = (...extra: (string | false | undefined)[]) =>
  cn(
    'flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm',
    'data-[highlighted]:bg-accent data-[highlighted]:text-accent-foreground',
    'disabled:pointer-events-none disabled:opacity-40',
    ...extra,
  )

/** Destructive menu-item variant (the sidebar's Delete action). */
export const dropdownItemDestructive = () =>
  cn(dropdownItemClass(), 'text-destructive data-[highlighted]:bg-destructive/10 data-[highlighted]:text-destructive')
