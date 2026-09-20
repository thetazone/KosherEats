# KosherEats Polish — Round 1
**Max severity found:** 5
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[5/10] [ke_uiux_web] No focus-visible styling anywhere in web/ — buttons, chips, and btn-primary links fall back to the browser's blue UA ring** — FIXED
  `grep -rn focus-visible web/src` returns 0 matches. `.input` gets an orange focus:ring-brand-500, but `.btn-primary`, `.btn-secondary`, every pill chi
  > Done. All five files are within `web/`; nothing outside it was touched. Changes are uncommitted on `feat/web-hunt-202609

- **[5/10] [ke_uiux_web] text-dark-500 helper copy fails AA contrast — 97 uses, ~40 of them at text-xs/text-sm** — FIXED
  #737373 measures 3.8:1 on dark-900 cards, 3.2:1 on dark-800 surfaces, and 4.2:1 on the dark-950 body — all under WCAG AA 4.5:1 — and the rubric explic
  > Done. `tsc` clean, ESLint clean.

## What changed

Swapped `text-dark-500` → `text-dark-400` on **49 informational text 

- **[4/10] [ke_uiux_web] Error banner hand-rolled ~28 times in 5 recipes; only 9 of 86 danger messages have role="alert"** — FIXED
  Five distinct error-banner recipes coexist: `bg-danger-900/30 border border-danger-800 text-danger-400 rounded-xl px-4 py-3` (×17 across auth, auth/fo
  > Done. `tsc`, `next lint`, and `next build` all pass, and both new classes are present in the compiled CSS.

## What chan
