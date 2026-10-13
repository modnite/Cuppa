# Cuppa Web UI — UI/UX Review

**Scope:** `webapp/web` (React + Vite + TS, styles in `webapp/web/src/styles.css`)
**Build reviewed:** `cuppa-web:dev` (git `adf6be1`), served from the Docker image on `http://localhost:18641`
**Date:** 2026-10-13

---

## Summary

The web UI is in good shape. It does **not** read as a generic "rounded box with an outline" template: the
borderless cards with soft shadows, the translucent sidebar, the hairline dividers and the accent-gradient
buttons give it a coherent, slightly macOS-flavoured identity, and the light/dark/accent theming system is
implemented consistently and cleanly. Light and dark themes both hold together, and the six accents all look
deliberate rather than bolted on.

The weaknesses are not in the visual language but in **density and responsiveness**:

1. On phones (the platform the product explicitly targets — the copy repeatedly mentions iPhone/Android) the
   **top navigation does not fit**: at 400px the five tabs overflow a horizontally-scrolling bar, and Settings
   is entirely off-screen with no affordance that the bar scrolls.
2. The **Jobs table is not responsive** — at 400px it is 291px wider than its container, so Size, Submitted and
   the Cancel action are hidden behind a horizontal scroll that nothing hints at.
3. **Small-text contrast is too low** in both themes (`--text-tertiary` ≈ 2.4–3.9:1) for section titles, hints,
   device URIs and stat captions; light-theme status pills are also around 3.4–4.4:1.
4. The **printer card action cluster is overloaded** — up to a share toggle plus 8 icon-only buttons in one row,
   including a destructive Remove sitting next to routine actions, with no grouping or overflow menu. It wraps
   into three ragged rows on mobile.
5. **Touch targets are small** (icon buttons 32×30, share switch 42×25, accent swatches 26×26, segmented buttons
   ~31px tall) and most printer actions are **labelled only by hover tooltips**, which do not exist on touch.

The layout, spacing scale, and radii are mostly coherent; the issues below are the exceptions.

### What works well

- De-bordered elevated surface language is consistent across cards, stat tiles, modals and toasts.
- Accent theming (6 accents × light/dark) is genuinely coherent; the active nav item, primary buttons, copy
  chips and pills all follow the accent.
- The printer card's icon → name → meta → origin → pills → actions ordering is logical, and long values
  truncate with ellipsis (verified at 400px).
- Empty states, the remove-confirmation modal, tooltips and toasts are all well-judged.
- `role="switch"`/`aria-checked` on toggles, `aria-label`s on icon buttons, and a visible keyboard focus ring
  (UA outline, light in dark theme / dark in light theme) are all present.

---

## Method / screenshots

The `browser` desktop tool was **not connected** to this session (`browser.disconnected`), so I could not use
`tools.browser.screenshot`. Instead I drove the running container with a local headless Chromium (Playwright)
and captured real screenshots, plus DOM geometry and computed-style measurements, from the same image. The
`/api/*` responses were stubbed in-browser to populate the pages with representative printers, jobs and
diagnostics (no source files were changed).

All captures are in `C:\Users\c\AppData\Local\Temp\opencode\uireview\shots\`
(kept outside the repo because this review is only permitted to write `docs/ui-review.md`):

| File | What it shows |
|---|---|
| `light-dashboard.png`, `dark-dashboard.png` | Dashboard, populated |
| `light-printers-populated.png`, `dark-printers-populated.png` | Printer cards, populated |
| `light-printers-empty.png` | Printers empty state |
| `light-jobs-populated.png`, `dark-jobs-populated.png` | Jobs table, populated |
| `light-jobs-empty.png` | Jobs empty state |
| `light-diagnostics.png`, `dark-diagnostics.png` | Diagnostics, populated |
| `light-diagnostics-empty.png` | Diagnostics empty states |
| `light-settings.png`, `dark-settings.png` | Settings |
| `light-add-printer-modal.png`, `dark-add-printer-modal.png` | Add-printer modal |
| `light-remove-modal.png` | Remove-confirmation modal |
| `light-tooltip-hover.png` | Icon-button tooltip on hover |
| `narrow400-dashboard.png`, `narrow400-printers.png`, `narrow400-jobs.png`, `narrow400-settings.png`, `narrow400-dark-printers.png` | 400px-wide viewport |

---

## Findings

### P0 — High severity

#### P0-1. Mobile top navigation is clipped and Settings is unreachable without a hidden horizontal scroll
- **Screen:** All pages at ≤640px (`narrow400-dashboard.png`, `narrow400-printers.png`, `narrow400-settings.png`).
- **What's wrong:** At 400px the sidebar (`styles.css` `@media (max-width: 640px)` block) collapses to a
  horizontal bar. Measured: `.sidebar` `clientWidth` 400 / `scrollWidth` 538, `.nav` `clientWidth` 436 — i.e.
  the nav is 36px wider than the viewport, the bar is 138px wider than the viewport, and `overflow-x: auto` is
  the only way to reach the last tabs. "Diagnostics" renders as "Diag…" and **Settings is entirely off-screen**
  on first load. In `narrow400-settings.png` the bar has scrolled so far that "Dashboard" is cut off on the
  left. There is no scroll hint, no fade, and the active tab can be the hidden one. The CSS comment even says
  "so all four tabs fit" — there are five tabs.
- **Why it matters:** Primary navigation is effectively broken on the exact form factor the product targets
  (phones printing over AirPrint). A user can't discover Settings.
- **Suggested fix:** Make the mobile nav genuinely fit five items. Either (a) shrink per-item cost — drop the
  leading icon under ~480px, reduce `.nav-item` padding/font and the 64×64 `.brand` mark (see P1-5) — or (b)
  switch to an icon-only bottom tab bar at ≤640px, or (c) at minimum add a right-edge fade/scroll affordance and
  `scroll-snap`/`scrollIntoView` so the active tab is always visible. The `@media (max-width: 640px)` `.sidebar`
  rules in `styles.css` are the place to change.

#### P0-2. Jobs table overflows horizontally on phones and hides the Cancel action
- **Screen:** Jobs at ≤640px (`narrow400-jobs.png`).
- **What's wrong:** The card is `overflow-x: auto` (`Jobs.tsx` line 104) around a fixed 7-column `.table`.
  Measured at 400px: card `clientWidth` 372 / `scrollWidth` 663 → **291px of content is off-screen**, and the
  first data row is **123px tall** (vs ~73px at desktop) because the job-name cell wraps to four lines. The
  Size, Submitted and the Cancel button columns are all past the right edge; the "Completed" pill is clipped
  mid-word. Nothing indicates the table scrolls.
- **Why it matters:** Cancelling a job — the main reason to open this page — is not reachable without discovering
  an invisible horizontal scroll, and the wrapped rows make scanning hard.
- **Suggested fix:** Add a narrow-width presentation for `.table` (e.g. at ≤640px render rows as stacked
  label/value blocks, or hide `Printer`/`User`/`Size`/`Submitted` columns and surface them under the job name).
  Keep the primary action (Cancel) visible. If horizontal scroll is kept, add a visible scroll shadow and
  `-webkit-overflow-scrolling: touch`.

---

### P1 — Should fix

#### P1-1. Small-text contrast fails WCAG AA in both themes
- **Screen:** All pages — `.section-title`, `.stat-hint`, `.printer-origin`, `.hint`, `.brand-sub`,
  `.sidebar-version`, `.device-uri`, `.empty` icon.
- **What's wrong:** `--text-tertiary` is used for a lot of small text. Measured contrast:
  - light: `#9a9aa0` on card `#ffffff` = **2.8:1**, on content `#efede7` = **2.39:1**
  - dark: `#77777c` on card `#242427` = **3.47:1**, on content `#19191c` = **3.94:1**

  All of these are 11.5–13px, so AA requires 4.5:1. (`--text-secondary` is fine: 5.3:1 light / 6.02:1 dark.)
- **Why it matters:** Section headings, hints and device URIs are the lowest-contrast text on screen and are
  hard to read, especially on a phone in daylight.
- **Suggested fix:** Darken `--text-tertiary` (light: ~`#6f6f75`; dark: ~`#9a9aa0`) or stop using it for
  content-bearing text and reserve it for decorative icons. At minimum, bump `.section-title`, `.printer-origin`
  and `.hint` to `--text-secondary`.

#### P1-2. Printer-card action cluster is overloaded, and Remove is adjacent to routine actions
- **Screen:** Printers (`light-printers-populated.png`, `narrow400-printers.png`).
- **What's wrong:** Each `.printer-actions` row holds a share `.switch` plus up to **8 icon-only buttons**
  (default, thermal, test, print file, upload PPD, pause, rename, remove) at 32×30 each — 9 controls, 270px
  wide. Destructive Remove (trash) sits directly beside Rename and Pause with no separator, colour or grouping.
  At 400px this wraps into **three ragged rows** (measured `rows: 3` for the 9-item card), leaving a lone trash
  icon on its own line.
- **Why it matters:** High misclick risk (Remove next to Pause/Rename), and the row is visually noisy and hard
  to parse — the least-used actions (upload PPD, thermal settings) get the same prominence as Share and Test.
- **Suggested fix:** Group by intent and demote the rest into an overflow "⋯" menu. Keep the share `.switch`
  and one primary action (Test print) inline; move Set-default / PPD upload / Thermal / Rename / Remove into a
  menu. Style the destructive item distinctly (use `.btn-danger`/`--danger`), and never place it next to Pause.

#### P1-3. Touch targets are below the 44px recommendation, and printer actions are labelled only by tooltips
- **Screen:** Printers, Settings, Jobs, segmented controls (all).
- **What's wrong:** Measured hit targets: `.btn-icon` **32×30**, `.switch` **42×25**, `.swatch` **26×26**,
  `.segmented button` **~70×31**, `.pill` 23px tall. They clear WCAG 2.2's 24×24 minimum but are well under the
  44×44 mobile recommendation. Separately, the printer-card icon buttons and the share `.switch` are labelled
  **only** via `data-tooltip` (`ui.tsx` `Toggle`, `Printers.tsx` action buttons) — tooltips are hover/focus only,
  so on a touch device they never appear and the controls are unlabelled to sighted users.
- **Why it matters:** On the phones Cuppa targets, these are the controls people actually tap; small targets plus
  no visible labels makes sharing/pausing/printing error-prone.
- **Suggested fix:** Raise the mobile hit area (e.g. `@media (max-width: 640px) .btn-icon { width/height: 40px }`,
  larger `.switch`), and give the share toggle and key actions a visible text label or an accessible grouping
  with a visible label on mobile.

#### P1-4. Light-theme status pills are borderline low-contrast
- **Screen:** Printers (`.pill.ok/.warn/.bad/.accent`), Jobs status pills, Settings "On" pill
  (`light-printers-populated.png`, `light-jobs-populated.png`, `light-settings.png`).
- **What's wrong:** Coloured pills use a saturated foreground on a 10–12% tint of the same hue. Computed
  examples: `.pill.ok` ≈ **3.45:1**, `.pill.warn` ≈ **4.4:1** — both below 4.5:1 for 11.5px semibold text.
  (Dark-theme pills are fine, ~5.6:1.)
- **Why it matters:** Pills are the primary way state is communicated; "Idle"/"Offline"/"Not accepting jobs"
  are exactly the labels a user must read quickly.
- **Suggested fix:** Darken the light-theme pill foregrounds (e.g. `--success`/`--warning`/`--danger` at ~30%
  darker for pill text) or increase the tint background so the pair clears 4.5:1. Keep the hue identity.

---

### P2 — Nice to have

#### P2-1. Full-width copy chip dominates the printer card
- **Screen:** Printers and Dashboard (`light-printers-populated.png`, `light-dashboard.png`).
- **What's wrong:** `.printer-uri` (the `CopyButton`) is a full-card-width tinted bar — measured **964×25**
  per card, one per printer. It is the only element with a filled background, so it out-shouts the printer
  name, and stacked three-high it creates strong banding down the page.
- **Suggested fix:** Make it an inline/compact chip next to the name or origin (auto width, not `width: 100%`),
  or de-emphasise it (hairline/`.mono` text with an icon button) so it reads as metadata rather than a field.

#### P2-2. Printer icon/name misalignment when the name wraps
- **Screen:** Printers at 400px (`narrow400-printers.png`).
- **What's wrong:** For the long "Brother HL-L2350DW Series (Cuppa)" card, `.printer-main` (`flex-wrap: wrap`)
  pushes the wrapped title above the icon, and the icon then aligns with the "Thermal" pill line instead of the
  name. It looks accidental.
- **Suggested fix:** Keep the icon aligned to the first line (e.g. `align-items: flex-start` and a fixed icon
  column, or move the pills below the whole `.printer-main` on narrow widths).

#### P2-3. Mixed surface language: bordered boxes nested inside borderless cards
- **Screen:** Diagnostics (`light-diagnostics.png`).
- **What's wrong:** `.card` is deliberately borderless with a shadow, but `.usb-step` and `.diag-pre` still use
  `border: 1px solid var(--border)`, so Diagnostics shows bordered boxes inside borderless cards.
- **Suggested fix:** Move `.usb-step`/`.diag-pre` to the borderless `--card-bg-2` + `--shadow-sm` treatment used
  by `.device-row`, so the de-bordered language is consistent.

#### P2-4. Add-printer selection border is a no-op
- **Screen:** Add-printer modal → Discovered (`AddPrinterDialog.tsx` line ~284).
- **What's wrong:** The selected device row sets inline `borderColor: "var(--accent)"`, but `.device-row` has
  `border: 0`, so no border renders — only the `boxShadow: 0 0 0 3px var(--accent-soft)` ring appears, which
  also *replaces* `--shadow-sm` and flattens the row. The intended selection affordance is half-missing.
- **Suggested fix:** Add a dedicated `.device-row.selected` class with `box-shadow: 0 0 0 2px var(--accent)` and
  keep the base shadow, instead of inline styles on a borderless element.

#### P2-5. Segmented control has no accessible selected state
- **Screen:** Jobs scope tabs, Settings theme picker, Add-printer tabs.
- **What's wrong:** `Segmented` (`ui.tsx`) renders plain `<button>`s; the active option is conveyed by a CSS
  class only — no `role="tablist"`/`role="radio"`, no `aria-pressed`/`aria-selected`.
- **Suggested fix:** Add `aria-pressed={active}` (or `role="tablist"` + `aria-selected`) to the buttons.

#### P2-6. Diagnostics renders all 17 system sections, including empty ones
- **Screen:** Diagnostics → System (`light-diagnostics.png`).
- **What's wrong:** Every entry in `SECTIONS` (`Diagnostics.tsx`) is always rendered as its own card, with
  `<pre className="diag-pre">(empty)</pre>` when there's no content. On a real box that's a very long page of
  mostly-empty cards, each with its own copy button.
- **Suggested fix:** Hide sections whose value is empty (or collapse them behind a "Show empty sections"
  toggle), and/or make each section collapsible.

#### P2-7. Keyboard focus relies on the unstyled UA outline
- **Screen:** All interactive controls.
- **What's wrong:** No `:focus-visible` styling is defined for buttons/links/toggles/segments; only `.input`/
  `.select` get a ring. Focus currently works via the browser default (`outline: auto 1px`), which is thin and
  off-language.
- **Suggested fix:** Add a shared `:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }`
  (and a matching ring for `.switch`, `.swatch`, `.segmented button`).

#### P2-8. Minor consistency nits
- **Screen:** Across the app.
- **What's wrong:** Radii are mostly coherent (cards 20px, modal 22px, controls 12px) but `.nav-item` uses 9px
  and `.btn-sm` 8px against `.btn`'s 12px. Dashboard hard-codes `.stat-value` sizes inline (`Dashboard.tsx`
  lines 88, 95 use `style={{ fontSize: 22 }}`) for the "Server"/"Uptime" tiles while the numeric tiles are 30px,
  producing uneven stat hierarchy with magic numbers in JSX.
- **Suggested fix:** Add a `.stat-value.text` modifier class and align the small-control radius to a single
  token (e.g. 8px everywhere, or 12px everywhere).

---

## Measurements appendix

Captured from the running image (light theme, 1440×900 unless noted):

| Token | Value |
|---|---|
| Hit targets | `.btn` 96×36 · `.btn-primary` 120×36 · `.btn-icon` **32×30** · `.switch` **42×25** · `.pill` 57×23 · `.swatch` **26×26** · `.segmented button` ~70×31 · `.nav-item` 225×36 |
| Radii | `.card` 20px · `.modal` 22px · `.btn` 12px · `.btn-sm` 8px · `.nav-item` 9px · `.printer-icon` 12px · `.pill` 999px |
| Copy chip | 964×25 per printer card (full card width) |
| Printer card | 1000×164 (desktop); actions 270px wide, 7–9 buttons |
| Contrast — light | tertiary/card **2.8:1** · tertiary/content **2.39:1** · secondary/card 5.3:1 · text/card 17.0:1 |
| Contrast — dark | tertiary/card **3.47:1** · tertiary/content **3.94:1** · secondary/card 6.02:1 · text/card 14.2:1 |
| Narrow 400px — nav | `.sidebar` 400 client / **538 scroll** · `.nav` 436 client (> viewport) · `.brand` icon 64×64 |
| Narrow 400px — printer actions | 9-button card wraps to **3 rows**; 8-button card to 2 rows |
| Narrow 400px — Jobs table | card 372 client / **663 scroll** (291px hidden) · first row 123px tall |

No source files, `webapp/**` files, or other docs were modified for this review.
