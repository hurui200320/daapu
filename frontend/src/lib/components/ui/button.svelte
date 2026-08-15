<script lang="ts" module>
  import { cn } from '../../utils'

  const base =
    'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50'

  const variants = {
    default: 'bg-primary text-primary-foreground hover:bg-primary/90',
    secondary: 'bg-secondary text-secondary-foreground hover:bg-secondary/80',
    ghost: 'hover:bg-accent hover:text-accent-foreground',
    outline: 'border border-border bg-transparent hover:bg-accent hover:text-accent-foreground',
    destructive: 'bg-destructive text-destructive-foreground hover:bg-destructive/90',
  } as const

  const sizes = {
    default: 'h-9 px-4 py-2',
    sm: 'h-8 rounded-md px-3 text-xs',
    lg: 'h-10 rounded-md px-8',
    icon: 'h-9 w-9',
  } as const

  export type Variant = keyof typeof variants
  export type Size = keyof typeof sizes

  export function buttonVariants({
    variant = 'default',
    size = 'default',
    class: className = '',
  }: { variant?: Variant; size?: Size; class?: string } = {}) {
    return cn(base, variants[variant], sizes[size], className)
  }
</script>

<script lang="ts">
  import type { Snippet } from 'svelte'

  type Props = {
    variant?: Variant
    size?: Size
    class?: string
    children: Snippet
    [key: string]: unknown
  }

  let { variant = 'default', size = 'default', class: className = '', children, ...rest }: Props = $props()
</script>

<button class={buttonVariants({ variant, size, class: className })} {...rest}>
  {@render children()}
</button>
