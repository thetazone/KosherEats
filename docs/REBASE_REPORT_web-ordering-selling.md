# Rebase report — `feat/web-ordering-selling` onto `main`

**Date:** 2026-09-13 (overnight, unattended)
**Result:** ✅ rebase completed cleanly; all gates green; nothing pushed, nothing deployed.

| | |
|---|---|
| **Result branch** | `feat/web-ordering-selling-rebased` |
| **HEAD** | `88339ce7` — `fix(web): restore main's UI/UX + preview-listing intent after the rebase` |
| **Base** | `main` @ `83daef7c` (`Merge branch 'chore/ke12-20260828'`) |
| **Old merge-base** | `d3d9f4c3` |
| **Commits** | 24 replayed + 1 new reconciliation commit = 25 ahead of `main`, 0 behind |
| **Original branch** | `feat/web-ordering-selling` @ `875e837f` — **untouched** |
| **`main`** | **untouched** |

Nothing was pushed. Nothing was deployed. `main` and the original branch were
never checked out for writing; all work happened on the new branch inside the
isolated worktree.

---

## 1. Conflict-by-conflict resolution

16 files were flagged as the conflict surface; every one of them is under
`web/`. Several conflicted repeatedly across the 24 replayed commits — the
description below is the **net** resolution for each file.

| # | File | How it was resolved |
|---|------|---------------------|
| 1 | `web/src/app/auth/page.tsx` | Branch's email-first multi-step flow (email → password / OTP → details → phone) is the structure; main's `safeNext()` open-redirect guard, error-banner `role="alert"` + `danger-*` tokens, every `htmlFor`/`id` label pair, and the linked Terms/Privacy copy were grafted onto it. Branch's `sanitizeNext()` was deleted in favour of main's stricter `safeNext()` (rejects `/\`, rejects self-redirect to `/auth`, defaults to `/search`). **Two judgment calls — see §2.** |
| 2 | `web/src/app/cart/page.tsx` | Branch's R3 extraction of checkout into `components/checkout/CheckoutPanel.tsx` + `checkoutShared.ts` supersedes main's inline checkout, so the branch structure was taken and main's intent re-applied on top (reconciliation commit `88339ce7`): `?next=/cart` on both remaining forced-sign-in redirects, `red-*` → `danger-*` on the finalize-error surfaces. Main's fee-preview honesty fix and its "Place Order" (no fake total) fix were already **superseded and improved** by the branch — the panel is now fully server-authoritative with `—` / "Updating…" placeholders while a re-quote is in flight. Main's 44px qty steppers survive verbatim in the cart list. |
| 3 | `web/src/app/globals.css` | Main's removal of the `@import url('fonts.googleapis.com/…Inter…')` kept (the font ships via `next/font` in `layout.tsx`). Verified: 0 occurrences of `fonts.googleapis` remain. |
| 4 | `web/src/app/orders/page.tsx` | Both sides merged in full. From main: semantic status tokens, `activeProgressPercent` (with the `picked_up: 90` stop) + `role="progressbar"` a11y + non-clipping stop labels, 20 s visibility-gated polling, the manual Refresh button, and the two-step "Cancel order? / Keep Order" confirmation. From the branch: the shared `ORDER_STATUS_META` map, the `!order.external_delivery_id` cancel guard, Track Order link, courier-rating modal + localStorage rated-id cache + per-order hydration, pickup-aware copy, `Loader2` spinners, `min-h-[44px]` touch targets. The branch's duplicate plain `loadOrders` was dropped in favour of main's `useCallback` version. |
| 5 | `web/src/app/privacy/page.tsx` | Took the branch's version — it makes the *same* `neutral-*` → `dark-*` / `orange-*` → `brand-*` token change main made, and additionally wraps the page in `<Header />`. Strict superset. |
| 6 | `web/src/app/restaurant/[id]/page.tsx` | Branch structure taken (modifier-aware `MenuItemModal` flow, `KosherBadge`, `KosherCertificateModal`, deals strip, Kashrus section, modifier-keyed local cart hydrated from `cartApi.get`). Main's **entire preview-listing feature was restored by hand** in `88339ce7`: `isPreviewListing`, dimmed hero + "Coming soon" chip, ratings/delivery terms hidden, Add buttons hidden, `RequestButton` in the desktop sidebar and the fixed mobile bar, and page padding that clears that bar. Main's category-tab `scrollIntoView` was also restored (`categoryRefs` + `selectCategory`). Main's certification-placeholder guards were superseded by the branch's richer `lib/kosher.ts` — see #14. |
| 7 | `web/src/app/search/page.tsx` | Both sides merged. From main: the `CUISINES` chip row (server-side `?cuisine=`, plus the idempotent client-side tag match for text-search results), the shared `ResultsSkeleton` used by both the in-page loading state and the Suspense fallback, and the **preview partition in the sort comparator** (orderable rows always sort above previews). From the branch: `KosherFilterPanel` (a strict superset of main's cert chips + Glatt toggle — it adds Cholov Yisroel / Pas Yisroel), favourites hearts with optimistic toggle, the "Suggested for you" row, geolocation + haversine "Nearest First" sort, and filter persistence. |
| 7b | `web/src/app/support/page.tsx` | Branch version (same token change as main, plus `<Header />` and the reusable `card` component class). |
| 8 | `web/src/app/terms/page.tsx` | Branch version (same token change as main, plus `<Header />`). |
| 9 | `web/src/components/layout/Header.tsx` | Main's a11y attributes kept (`aria-label={\`Cart, N items\`}` with correct pluralisation, `aria-expanded`, `aria-controls="mobile-menu"` ↔ `id="mobile-menu"`, `aria-hidden` on decorative glyphs, 44px button); the branch's lucide `ShoppingCart`/`Menu`/`X` icons replace main's hand-rolled inline SVG paths. One duplicate `aria-label`/`aria-expanded` pair that the merge left behind was removed in `88339ce7` (it was a hard `TS17001` build break). |
| 10 | `web/src/components/restaurant/RestaurantCard.tsx` | Rewritten to hold both: main's preview-listing rendering (dimmed card, "Coming soon", no rating row, `RequestButton` instead of delivery terms) on top of the branch's shared `Restaurant` type, `KosherBadge`, `formatUSD`, lucide `Star`, and favourite-heart props. **Judgment call:** the favourite heart is suppressed on preview listings so a card never shows two different hearts — see §2. |
| 11 | `web/src/components/ui/SearchBar.tsx` | Main's version (trimmed-query push, empty submit opens browse, `type="search"`, `aria-label`, `input` component class, `mx-auto` dropped) plus the branch's `role="search"` on the form. The branch's duplicate `aria-label` was dropped. |
| 12 | `web/src/lib/api.ts` | Merged. Main's preview-listing plumbing kept intact — `restaurantQuery()` stamping `include_previews=1`, `optionalToken()` riding along on the optional-auth routes, and `restaurants.request()`. The branch's `Restaurant[]` generics, finite-coordinate guard on `list()` (folded into `restaurantQuery`), `suggested`, `deals`, and `favorites` were all preserved. |
| 13 | `web/src/lib/kosher.ts` | **add/add.** The branch's 104-line trust-guard module is the base (broader placeholder detection: `tbd`/`pending`/`n/a`/`none`/"to be provided"…, plus placeholder-image-host detection for the "View Certificate" button). Main's `certLabel()` / `certIsPending()` were **re-implemented on top of it** so main's three-way distinction survives, and a `certIsAbsent()` helper was added. `KosherBadge` now renders **no** certification chip at all when the field is empty — preserving main's rule that a preview listing seeded without a hashgacha must not show "Certification pending" (which would imply an application in flight that may not exist). |
| 14 | `web/src/types/index.ts` | Auto-merged cleanly; verified main's preview fields (`orderable?`, `listing_visibility?`, `request_count?`, `requested_by_me?`), the `isPreviewListing()` helper, and `RestaurantRequestState` are all present alongside the branch's ~200 lines of new types. |
| 15 | `web/tsconfig.tsbuildinfo` | Build artifact — never hand-merged. Took `main`'s side on all 7 conflicts, then regenerated it with `npm run build` and committed the regenerated file. |
| 16 | `web/src/lib/orderStatus.ts` | Not in the original conflict list, but it is where the branch moved the status map that main had retokenized in place. Remapped to the rubric's semantic aliases (`info`/`warning`/`transit`/`success`/`danger`) — pixel-identical, greppable intent. **See §2** for the two entries that could not be remapped. |

**Verification that nothing was dropped:** `git diff main..HEAD --diff-filter=D`
returns nothing (no file on `main` was deleted), and every web file `main`
touched that the branch does not touch (`app/page.tsx`, `app/layout.tsx`,
`components/restaurant/RequestButton.tsx`, `tailwind.config.ts`, all of
`app/admin/*`) is byte-identical to `main`.

---

## 2. Needs Salto's eyes — genuine judgment calls

> Each of these is a case where the two sides made *different valid choices
> about the same thing*. My default was "match `main`'s newer conventions",
> and I note below where I deviated and why.

**A. Dead Google/Apple sign-in buttons: removed (branch) vs. disabled with a
"coming soon" note (main). — I took the BRANCH.**
`main`'s `133075ff` left the buttons rendered but `disabled`, with a
`#social-login-note` reading *"Google and Apple sign-in are coming soon — use
your email below."* The branch's residual-debug pass **deleted them outright**
and restructured that whole step (no fragment wrapper, no "or" divider),
leaving a comment saying exactly how to re-add them once the provider SDK flows
land. Restoring main's version would mean re-introducing the fragment and
divider the branch removed, contradicting a documented decision in the branch's
own debug pass. *If you prefer main's behaviour, it is `main:web/src/app/auth/page.tsx`
lines 89–128 — a five-minute restore.*

**B. Post-sign-in navigation: `window.location.href` (branch) vs
`router.replace()` (main). — I took the BRANCH.**
Both honour `?next=`. Main used a client-side transition, reasoning that
in-flight app state and history survive. The branch uses a **full** navigation,
reasoning that `Header` reads the session from `localStorage` on mount, so a
client-side transition leaves the header showing the signed-out state. The
branch's reason describes a concrete observed bug ("header badge flash",
`MORNING_HANDOFF.md`), so it wins. Main's stricter `safeNext()` guard is kept
either way.

**C. Escape-to-close on the Stripe checkout modal: main added it, the branch
forbids it. — I took the BRANCH, and kept the rest of main's a11y.**
`main` added a full focus trap **plus** Escape-to-close. The branch explicitly
documents that neither backdrop clicks nor Escape may dismiss this modal,
because closing mid-`confirmPayment` can hide a charge that is about to
capture. I restored main's **focus containment** (Tab cycling, body scroll
lock, `aria-labelledby`, initial focus) and deliberately **did not** restore
the Escape handler. Worth a sanity check that you agree a keyboard user can
still escape the flow via the (44px, correctly labelled) close button.

**D. Favourite heart on preview listings. — my call, no precedent on either
side.** `main` puts a Request heart in the card body for previews; the branch
puts a Favourite heart at the card's top-right. Showing both on one card is
ambiguous, so the Favourite heart is now suppressed when
`restaurant.orderable === false`. If you'd rather let people favourite a
preview listing, delete the `&& !isPreview` in `RestaurantCard.tsx`.

**E. Two order-status colours could not be de-hued.** `ORDER_STATUS_META` now
uses the rubric's semantic aliases everywhere an exact alias exists
(`blue→info`, `yellow→warning`, `purple→transit`, `green→success`,
`red→danger` — same pixels). `scheduled` uses `sky-*` and `pending` uses
`amber-*`; the Ember palette has no alias for either hue, and picking one is a
design decision, not a merge decision. There's a `TODO(rubric)` in the file.
Note also that the branch and `main` disagree on the *assignment*: `main` had
`preparing = brand` / `ready = success`, the branch has `preparing = warning` /
`ready = brand`. I kept the branch's assignment (it is the newer, shared,
three-surface map).

**F. Rubric debt: 288 raw-hue class occurrences in the branch's new surfaces.**
`main` is at **0** raw Tailwind hue classes across `web/src` (`docs/DESIGN_RUBRIC.md`
§ "Zero raw Tailwind hue classes"). The rebased branch is at **288**, all of
them in the 39 files the branch adds — these were written in July, before
`main`'s August Ember token migration. Worst offenders:
`app/account/page.tsx` (38), `app/seller/orders/[id]/page.tsx` (36),
`components/seller/ActiveOrderCard.tsx` (22), `app/seller/menu/page.tsx` (20),
`app/orders/[id]/page.tsx` (20), `app/seller/deals/page.tsx` (19).
**I deliberately did not mass-rewrite these** — that's a design sweep across
~20 files, not conflict resolution, and doing it unattended risks silently
changing brand colours. I *did* retokenize every specific line `main` had
already retokenized. Recommend a `/sweep` or polish round before this ships.

**G. Saved-card selection at checkout is still not implemented.** Carried over
verbatim from the branch's `MORNING_HANDOFF.md` as a deliberate omission: the
backend payment-intent response lacks the customer / ephemeral-key context
`PaymentElement` needs. Unchanged by this rebase.

---

## 3. Gate output (verbatim tails)

All gates were run from the rebased worktree at HEAD `88339ce7`.
**Note:** `web/` is **npm**, not a pnpm workspace — there is no
`pnpm-workspace.yaml` and no `pnpm-lock.yaml`; `web/package-lock.json` is the
lockfile. `package.json` exposes `dev`, `build`, `start`, `lint`; the
type-check is `npx tsc --noEmit` (as documented in `docs/KE-WORK-HANDOFF.md`
§ Gating).

### Install
```
$ cd web && npm ci
(completed; only the usual audit/npm-version notices)
```

### Type-check
```
$ npx tsc --noEmit
(exit 0 — no output)
```

### Lint
```
$ npm run lint

> koshereats-web@0.1.0 lint
> next lint

✔ No ESLint warnings or errors
```

### Build
```
$ npm run build

   ▲ Next.js 14.2.35
 ✓ Compiled successfully
   Linting and checking validity of types ...
   Collecting page data ...
 ✓ Generating static pages (31/31)
   Finalizing page optimization ...
   Collecting build traces ...

Route (app)                              Size     First Load JS
┌ ○ /                                    725 B           102 kB
…
└ ○ /terms                               1.95 kB         102 kB
+ First Load JS shared by all            87.3 kB
```
(31 routes; full table in the session log. The only warning is the unrelated
`caniuse-lite is 6 months old` browserslist notice.)

### Backend
The branch touches **no** files under `backend/` (`git diff --stat main...HEAD -- backend/`
is empty), so backend gates were not strictly required — they were run anyway
to prove the merged tree is sound end to end:
```
$ cd backend && go build ./...
BUILD_OK
$ go vet ./...
VET_OK
$ go test ./...
(10 packages ok, 0 failures — Postgres WAS up on :5433, so the handler
 integration suite ran rather than skipping)
```

---

## 4. Campaign smoke-test checklist (`82a59b93`) — code-path audit

Walked by reading the rebased tree, not by running a server. Each item is
marked for whether the code path **still exists post-rebase**; none of them is
a claim that the feature *works* at runtime — that's still your manual pass.

| # | Checklist item | Status | Evidence in the rebased tree |
|---|---|---|---|
| 1 | Sign up fresh: **email OTP delivery** | ✅ path intact | `auth.emailOtp.start/verify` in `lib/api.ts:195`; `auth/page.tsx` `step === "otp"` with `OtpInput`, 30 s resend cooldown, and expired-proof recovery (restarts the OTP leg instead of stranding the form) |
| 1 | …**phone OTP** | ✅ path intact | `Step` now includes `"verify-phone"` (`auth/page.tsx:17`, added by `d37ca1f7`); it renders `VerificationGate` running only its phone leg → `/user/phone/change/start` → `/user/phone/change/verify` (`lib/api.ts:586`) |
| 1 | …**403 → verify → resume-checkout** | ✅ path intact | `isVerificationRequired()` in `checkoutShared.ts:109` traps the backend's `403 verification_required`; cart routes to `VERIFY_ROUTE = "/account/verify?next=/cart"`; `account/verify/page.tsx:136` renders a "Back to checkout" button that returns to the sanitized `?next=`. The cart's own forced-sign-in redirects now also carry `?next=/cart` (restored in `88339ce7`). |
| 2 | Stripe test-card checkout: **order lands** | ✅ path intact | `CheckoutPanel.tsx` — `loadStripe` (memoised to one instance), `<Elements>` + `<PaymentElement>`, `confirmPayment`, then the pending-order recovery machinery in `cart/page.tsx` (`PENDING_ORDER_KEY`, idempotent replay probed via `GET /orders/by-payment-intent`) |
| 2 | …**receipt sums (subtotal/fees/tax/tip/discount = total)** | ✅ path intact | `orders/[id]/page.tsx:830–872` renders line items → Subtotal → Discount → Delivery fee (delivery only) → Service fee → Tax → Courier tip → **Total**, every figure straight from the server order. The checkout-side breakdown is likewise server-authoritative (no client arithmetic) with `—`/"Updating…" while a re-quote is pending. |
| 3 | Order tracking: **status timeline** | ✅ path intact | `buildTimeline()` / `<Timeline>` in `orders/[id]/page.tsx:178–230`, one step per `OrderStatus` |
| 3 | …**SSE courier stream** | ✅ path intact | `orders/[id]/page.tsx:385` — gated on `token && order && isActive && !isPickup && !isExternalDelivery`, `AbortController`, capped exponential backoff on failure + short fixed delay on clean close, falls back to the courier's last known position |
| 4 | Seller: **onboarding with a photo (iPhone HEIC)** | ✅ path intact | `components/seller/PhotoUpload.tsx:68` — `accept="image/jpeg,image/png,image/webp,image/heic,image/heif,.jpg,.jpeg,.png,.webp,.heic,.heif"`; the extension entries are what keep HEIC selectable in iOS pickers, with a comment saying so, plus a MIME-fallback helper at `sellerApi.ts:459` |
| 4 | …**menu CRUD** | ✅ path intact | `sellerApi.ts` — `menu.createCategory` (297), `createItem` (264), `updateItem` (269), `deleteItem` (274); UI in `app/seller/menu/page.tsx` + `MenuItemForm` + `ModifierGroupsEditor` |
| 4 | …**accept → ready an order** | ✅ path intact | `sellerApi.ts` — `orders.accept` (`PATCH /seller/orders/:id/accept`, 328) and `orders.markReady` (339); UI in `app/seller/orders/page.tsx` + `components/seller/ActiveOrderCard.tsx` |
| 5 | Push when satisfied | ⛔ **not done, by instruction** | This run was explicitly forbidden to push or deploy. The branch is local only. |

**Nothing on the checklist was silently dropped by conflict resolution.** The
two places where a resolution changed observable behaviour are §2-A (social
buttons removed rather than disabled) and §2-C (Escape no longer closes the
payment modal); neither appears on the checklist.

---

## 5. Still outstanding from the branch's `MORNING_HANDOFF.md`

The branch's own handoff (`MORNING_HANDOFF.md`, commit `82a59b93`) is still at
the repo root and still accurate except where noted.

| Item | Status now |
|---|---|
| **Branch `feat/seller-latlng-api`** (`78808ba9`, optional lat/lng on seller restaurant create/update) — listed as "ships alone" | ✅ **Already on `main`** (`git merge-base --is-ancestor 78808ba9 main` → yes). Nothing to do. |
| **Branch `feat/web-seller-geocode`** (`e748314e`) — gated: *"merge only after `feat/seller-latlng-api` is deployed, otherwise the UI collects coords the API discards"* | 🔓 **Unblocked.** Its dependency is on `main`, so the gate has cleared. It is 1 commit sitting on an older point of the un-rebased web branch and will need the same rebase treatment (or a cherry-pick onto `feat/web-ordering-selling-rebased`). **Not done tonight** — out of scope. |
| **Saved-card selection at checkout** — "deliberately NOT done; backend payment-intent response lacks customer/ephemeral-key context for `PaymentElement`" | ⬜ Still outstanding, unchanged. Needs a backend change first. |
| **Manual smoke-test pass (items 1–4)** | ⬜ Still outstanding — nobody has run it in a browser. §4 above is a code-path audit only. |
| **`git push -u origin feat/web-ordering-selling`** (handoff step 5) | ⬜ Not done, by instruction. |
| **Ops: `claude` CLI OAuth dead → run `claude login`** | ⬜ Unknown / unchanged — not verifiable from here. |
| **Ops: stale worktree `wt-seller-geocode` in the session scratchpad** | ⬜ Probably still mounted; `git worktree remove` it after the geocode branch lands. |
| **Ops: Temporal Cloud lane `koshereats.fnvcs` provisioned + auth preflights** | ℹ️ Informational; unaffected by this rebase. |
| Round-by-round detail in `polish-summaries/` | ℹ️ Carried through the rebase intact. |

---

## 6. What to do next

1. Read §2 — the six judgment calls. A and C are the two with user-visible
   behaviour changes.
2. Run the §4 smoke test for real (`cd web && npm run dev`).
3. Decide on the rubric sweep (§2-F) before or after landing — 288 raw-hue
   occurrences vs `main`'s 0.
4. Rebase or cherry-pick `feat/web-seller-geocode` onto this branch; its gate
   has cleared.
5. Then push / open the PR. Both `main` and the original
   `feat/web-ordering-selling` are untouched, so there is no rush and no risk
   in re-running any of this.
