# KosherEats Polish — Round 3
**Max severity found:** 5
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[5/10] [ke_uiux_web] Meat/Dairy/Pareve item badge implemented three ways with divergent shape, size, and tint** — FIXED
  Rubric dim 3 requires the kosher triad to be consistent everywhere, but the item-level dietary badge exists as two identical copy-pasted `DietaryBadge
  > Done. `tsc --noEmit` and `eslint` are both clean on the touched files.

**What changed (all under `web/`):**

- **New `w

- **[5/10] [ke_uiux_web] Kosher filter bottom sheet is the only modal with no dialog semantics** — FIXED
  Below `md`, `KosherFilterPanel.tsx:242-249` opens a full-screen `fixed inset-0` sheet with body-scroll lock and Escape handling, but has zero `role="d
  > No type errors. Added `role="dialog"`, `aria-modal={isSheet}` (derived from the existing `matchMedia` check), and `aria-

- **[4/10] [ke_uiux_web] `scheduled` order status is the last raw Tailwind hue (sky-*), blocking M3 = 0** — FIXED
  `lib/orderStatus.ts:35` (`bg-sky-500/15 text-sky-300`) and `components/seller/ActiveOrderCard.tsx:154` (`text-sky-300`) are the only raw-hue classes l
  > Done — `tsc` and `eslint` both exit 0.

**Changes** (only the two named files under `web/`):

- `web/src/lib/orderStatus
