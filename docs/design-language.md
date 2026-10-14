# Cuppa web — design language

The webapp is macOS-native-inspired (system type, translucent sidebar, light/dark
from the OS) and OS-agnostic: the office uses Android, macOS and iOS, never
Windows. No Material Design. This file records the rules so a change can be
checked against them instead of argued about.

It also borrows deliberately from Juxtopposed's **"I Redesigned VLC After 25
Years"** concept, because the problem is the same shape: an old, dense, deeply
configurable tool that should feel calm and current without losing any power.

## Rules

1. **Content first, chrome last.** Surfaces are defined by layered background and
   soft shadow, never by a 1px outline. A control earns a border only if it
   would otherwise be invisible.
2. **One accent, used sparingly.** A single accent (user-selectable) marks the
   primary action, the active nav item, and the brand mark. Everything else is
   neutral. The accent is a focal point, not decoration.
3. **No modal over the content unless it is genuinely blocking.** Prefer an
   inline row, a popover, or a panel. (VLC moves all playback settings into a
   right-hand sidebar for exactly this reason.)
4. **Every icon-only control has a tooltip and an `aria-label`.** Nothing is
   discoverable only by hovering.
5. **Thick, hittable targets.** Controls are comfortable on a phone; a segmented
   control or a switch is a real target, not a hairline.
6. **Icons are one drawn set.** Consistent stroke, consistent optical size.
   Section headers get an icon; page titles do not.
7. **Nothing is cut off by the window.** Long names, addresses and URLs wrap or
   truncate with the full value available (copy chip, tooltip). This is checked
   at 390 px, 820 px and desktop widths.
8. **Contrast is a requirement, not a preference.** Small text and status pills
   meet WCAG AA in both themes.
9. **A failure is visible.** No control reports success while its side effect was
   dropped. Errors surface in the UI, not only in a log.
10. **The version is always visible.** Sidebar and Settings → About show the
    build, so "am I current?" is never a question.

## Applying it

- Colours, spacing and radii live as CSS custom properties at the top of
  `web/src/styles.css`; themes only swap the values.
- Status colour is semantic: `--success`, `--warning`, `--danger`, each with a
  soft variant for pills and dots.
- The responsive breakpoints are 1100 px and 640 px (see the media queries at
  the end of `styles.css`). The phone top bar is icon-only so all five tabs fit
  without a hidden scroll.
