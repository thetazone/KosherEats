# KosherEats Polish — Round 2
**Max severity found:** 6
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[6/10] [ke_uiux_web] Half of all buttons skip the contract's .focus-ring (123/227), including every seller order-state button and modal close/stepper control** — FIXED
  globals.css declares `.focus-ring` as "the single source of truth" for keyboard focus and instructs that it be applied directly to one-off controls th
  > Done. Every `<button>` outside `admin/` now carries the contract's `.focus-ring` — 123 → 0 unfocused buttons, gate green

- **[5/10] [ke_uiux_web] Restaurant detail page never renders the cover photo or menu item photos that sellers are required to upload** — FIXED
  `Restaurant` carries `image_url` + `cover_image_url` and `MenuItem` carries `image_url` (types/index.ts:39-40, :102). The seller onboarding form label
  > Done — additive fix, both files under `web/`, typecheck and lint clean.

**`web/src/app/restaurant/[id]/page.tsx`**
- Im

- **[4/10] [ke_uiux_web] text-dark-500 captions fail WCAG AA on every canvas surface (3.2–4.2:1) — ~30 real text instances, mostly 12px** — FIXED
  Measured contrast for `text-dark-500` (#737373): 4.18:1 on dark-950, 3.78:1 on dark-900 (.card), 3.19:1 on dark-800 (inputs, chat bubbles) — all below
  > Done. `tsc --noEmit` and `next lint` are both clean.

## What changed

**Contrast fix (`text-dark-500` → `text-dark-400`
