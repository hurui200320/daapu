<script lang="ts">
  // iOS-style track-and-knob switch on the monochrome palette: gray track +
  // white knob off, inverted (near-white track + dark knob) when on.
  // Accessible name comes from a wrapping <label>, a <label for>/`id` pair,
  // or `aria-label` (rest).
  import { Switch as SwitchPrimitive } from 'bits-ui'
  import { cn } from '../../utils'

  type Props = {
    checked: boolean
    onCheckedChange?: (checked: boolean) => void
    disabled?: boolean
    class?: string
    [key: string]: unknown
  }

  let { checked, onCheckedChange, disabled = false, class: className = '', ...rest }: Props = $props()

  // `$derived` so a dynamic `class` prop re-merges (a plain const would
  // capture only the initial value)
  const track = $derived(
    cn(
      'inline-flex h-6 w-11 shrink-0 items-center rounded-full bg-muted p-0.5',
      'transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
      'disabled:cursor-not-allowed disabled:opacity-50',
      'data-[state=checked]:bg-primary',
      className,
    ),
  )

  const knob = cn(
    'size-5 rounded-full bg-foreground shadow-sm',
    'transition-all duration-200',
    'data-[state=checked]:translate-x-5 data-[state=checked]:bg-primary-foreground',
  )
</script>

<SwitchPrimitive.Root {checked} {disabled} {onCheckedChange} class={track} {...rest}>
  <SwitchPrimitive.Thumb class={knob} />
</SwitchPrimitive.Root>
