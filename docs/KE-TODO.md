# KosherEats — Master TODO (live tracker)

The single working checklist. Deeper context in `docs/KE-WORK-HANDOFF.md`.
Last updated: 2026-06-25.

---

## ✅ CI fixed — fully green (2026-06-25, commit `a8d8c03e`)
CI had been red on every push: the **Android (consumer)** job failed at `:app:processDebugGoogleServices` ("google-services.json is missing"). Root cause — consumer is the only Android app applying the `com.google.gms.google-services` plugin (FCM + Crashlytics + Analytics), which hard-requires the gitignored `google-services.json` at build time; seller/courier do manual FCM init without the plugin (so they were green). Fix: CI writes a minimal placeholder `google-services.json` (correct package_name) for the consumer compile only — CI just type-checks, never runs the app. Verified locally that the placeholder passes `processDebugGoogleServices` + `compileDebugKotlin`, then confirmed the full run green (all 7 jobs ✓). Gitignore policy unchanged.
- **Noted (not blocking):** the iOS consumer job emits Swift-6 main-actor isolation **warnings** (`LocationManager.swift`, `OrderDetailView.swift`) — fine today, would become errors under Swift 6 language mode. Future cleanup.
- **Noted (CI coverage gap):** the Android + iOS CI matrices cover only consumer + seller — **courier** isn't built in CI. Worth adding.

## ✅ Post-backlog review pass — DONE & DEPLOYED (2026-06-25, commit `86b128fd`)
Fresh adversarial review (4 lenses: regression of the recent changes + perf + security + concurrency, each finding skeptic-verified). 5 confirmed, all fixed; build + vet + full suite green; deployed (migrations 049 + 050 verified live on prod, `/health` 200).
- [x] **Perf — GetMenu N+1** (consumer restaurant-detail, hot path): was 1+N item queries (one per category) → one batched query bucketed by category_id. Added a test asserting correct item→category bucketing.
- [x] **Perf — GetSellerMenu N+1**: same per-category fix, seller side.
- [x] **Perf — GetDashboardStats lifetime scan**: each 30s poll full-scanned the restaurant's entire order history (non-sargable `created_at AT TIME ZONE ::date` filters). Rewritten to a sargable `created_at >= NY-midnight-today` range + a separate status-indexed active-orders count; new composite index `idx_orders_restaurant_created` (migration 049).
- [x] **Perf — ListNearbyDeals unbounded**: added `LIMIT 100`.
- [x] **Concurrency — courier busy-guard race**: the NOT EXISTS in the claim CAS / auto-dispatch wasn't race-safe (two concurrent claims by one courier could double-book under READ COMMITTED). Added partial-unique index `uq_courier_one_active_order` (migration 050) as the race-safe backstop; ClaimOrder + tryAutoAssign catch 23505 → "busy" / fall through. Verified prod had 0 existing duplicates before adding the unique index. Added a DB-level constraint test.

---

## ✅ Medium/low backlog pass — DONE (2026-06-25)
Re-triaged the cycle 1–3 med/low tail (153 raw → 132 real); fixed the 16 worth-fixing, intentionally skipped the noise (per Salto: "skip the med/low noise for now").
- [x] **Backend mediums (5)** — committed `da3e8324`, pushed, **DEPLOYED** (`fly deploy`, clean boot, `/health` 200):
  - `cart.go` snapshotModifiers rejects paused modifiers (`AND m.is_available = true`) → can't order/charge for an unavailable add-on.
  - `courier_orders.go` ListCourierHistory checks `rows.Err()` (no silently-truncated history); ClaimOrder gains the auto-dispatch busy-guard (friendly pre-check + race-safe `NOT EXISTS` in the CAS) so a courier can't manually stack concurrent deliveries.
  - `orders.go` CreateOrder idempotent-replay short-circuit by PaymentIntent (user-scoped, no IDOR) before any cart/deal processing — a retry now returns the existing order (200) instead of "cart is empty" / "deal already used".
  - `phone_auth.go` StartPhoneLogin rejects a new code during an active brute-force lockout (no lockout-wipe, no SMS spam).
- [x] **Client mediums (11 across 4 apps)** — committed `42cf8e4a`, pushed; all build-green in the main tree (3× Android `compileDebugKotlin` + iOS `xcodebuild` BUILD SUCCEEDED). android-seller (5: decimal deal %, per-order delivery_mode chip/address gating, self-delivery action from per-order field, local-zone "Activates"), android-consumer (4: tip≤subtotal, 409 recover by PI, paused-modifier + unavailable-item gating), android-courier (1: lifetime delivery count from profile, not capped-history sum), ios-consumer (1: reorder routes through restaurant-switch confirm). **Client builds NOT shipped to TestFlight/Play — that's Salto's release step.**
- [x] **Entire low/trivial tail also closed (2026-06-25, per Salto: "fix all findings med and low, all of them"):** the remaining 1 medium + 94 low + 20 trivial were fanned out one-agent-per-app in worktrees, deduped, build-verified, and re-verified in the main tree. **93 distinct fixes** landed across 2 commits — backend `30e7d75d` (35; **DEPLOYED**, clean boot, `/health` 200) and client `cf6494be` (58 across all 6 apps; 3× Android compile + 3× iOS BUILD SUCCEEDED). The open medium ([8] courier-payout double-pay) is now closed: Temporal's StripeTransfer idempotency key is aligned to the legacy queue-row id (code-only; Temporal off in prod). Backend highlights: unchecked `rows.Err()` across ~18 read paths, Clover/POS money consistency, detached-context notification dispatch, auto-dispatch TOCTOU collapse, VerifyCourierPhone match-check, SetCourierOnline always-allow-offline. The gap from 115→93 is deduped overlapping findings.
- [x] **Last deferred item — owner_id leak — NOW FIXED & DEPLOYED (2026-06-25, commit `cd1c59ef`):** public restaurant endpoints were leaking the seller's internal `owner_id` (users.id). Couldn't use `json:"-"` because the shipped consumer iOS app does a *required* decode of the key. Fix redacts the VALUE but keeps the KEY: `redactPublicRestaurants()` blanks owner_id to `""` on all 5 consumer endpoints (List/Get/Search/Suggested/ListFavorites); the shared `scanRestaurants` is untouched so seller/admin views still get owner_id. Added an integration test (present-but-empty assertion). **Verified live on prod:** key present on all, zero leaks. No client change needed.
- **Client builds NOT shipped to TestFlight/Play** — that remains Salto's release step. (A future nicety: make the iOS `Restaurant.ownerID` optional so the field can eventually be dropped entirely — not required now.)

---

## 🔬 Adversarial bug-hunt — cycle 1 (2026-06-25) — full list in `docs/bug-backlog-2026-06-25.md`
Multi-agent hunt (8 lenses × 5 rounds, skeptic-verified): **102 confirmed — 3 critical, 36 high, 40 medium, 23 low.** Fixed + committed so far (branch `feat/seller-delivery-mode-ui`):
- [x] **CRITICAL — anon admin self-registration** (auth/social/phone signup took `role` from the body, no allowlist → admin JWT → full /admin surface). Allowlist (`allowedSignupRole`) on all 3 creation paths + regression test. **Committed `d3dab2e0` and DEPLOYED — verified live: prod now returns `400 invalid role`.**
- [x] **CRITICAL — scheduled orders auto-rejected+refunded on promotion** (stale-rejection keyed off `created_at`; scheduled orders are old by the time they go pending). Key off `updated_at`. Committed `664527cb`.
- [x] **HIGH — CreateOrder built a partial order on a cart-scan error** (`rows.Err()` unchecked → undercharged, items missing). Committed `9fd6c0cf`.
- [x] **HIGH — UpdateCartItem dropped edited notes** (read but never written). `*string` + COALESCE. Committed `9fd6c0cf`.
- [x] **HIGH — KE courier could poach self-delivery orders** (claim CAS only excluded external_provider, which is NULL for restaurant-mode) + feeds listed self-delivery/external orders. Scoped feed + claim to `delivery_mode='platform' AND external_provider IS NULL`. Committed `a47aa0ff`.
- [x] **HIGH — seller could force a delivery order ready→completed** (CompleteOrder's ready branch had no pickup guard), stranding the courier + skipping payout. Pickup-only now. Committed `a47aa0ff`.
- [x] **HIGH — charged-but-no-order on deal re-redemption** (deal unique index 043 conflict → 500, no refund). Detect the constraint, refund, return 409. Committed `09ad8a39`.
- ⏳ **~31 high + 40 med + 23 low remain** in the backlog doc, triaged + with proposed fixes. Deployed: all backend fixes above are live (`fly deploy`, /health 200).
- [x] **Client-side highs fixed (6, all build-green, 2026-06-25):** iOS consumer — custom tip now capped vs subtotal (was flat $500 → server 400 on small carts), delivery-address change re-prices the bundle (was charging old zone's fee), pickup no longer records the home address as delivery_address. iOS seller — push `load()` preserves in-flight optimistic edits (mergeFresh), order detail always fetches the authoritative copy (was rendering courier/escalate off the modifier-less list copy). Android consumer — cart merge keeps differing special instructions.
- [x] **Client highs — ALL fixed (build-green):** iOS seller self-delivery pickup/deliver flow (added `delivery_mode`/`external_provider`/`external_tracking_url` to the order payload, APIService + VM + buttons). iOS consumer PaymentIntent-lock (timestamped marker, 5-min expiry) + modifier-default preselect (available + single-select cap). Android consumer tip-recovery on process-death + menu-default preselect. Android courier card now includes the tip in payout. (Consumer fixes + the Uber-tracking feature below were implemented by a 3-agent worktree workflow, patches re-verified in the main tree.)
- [x] **Consumer Uber-delivery tracking — DONE (iOS + Android):** `external_provider`/`external_delivery_id`/`external_tracking_url` on the consumer Order; the tracking screen now swaps the dead platform map / "finding a courier" UI for a "Delivered by Uber/DoorDash" card + a "Track delivery" button opening `external_tracking_url`. Backend serializes the fields (deployed).
- [x] **Flagged backend — done where safe:** self-delivery seller now keeps 100% of the courier tip (was dropped to the platform); added a reaper for leaked `external_provider='dispatching'` claim sentinels (>2 min → re-dispatch).
- [x] **4 more open highs fixed (2026-06-25, committed `64a0cacd`, deploying):** courier available-deliveries feed no longer leaks the customer's exact address/GPS pre-claim (address withheld + coords coarsened to ~block level; full address on claim); suspended-after-claim courier can no longer Pickup/Deliver (`requireApprovedCourier` re-check); seller dashboard "today" totals now use America/New_York not UTC (single-market assumption noted); provider (Uber/DoorDash) cancel after pickup resets picked_up→ready instead of stranding the order.
- **Flagged backend — still deferred (need migration / product / ops decision):** courier-payout double-pay (legacy↔Temporal idempotency-key mismatch — only fires on a mode switch, and Temporal payouts are OFF in prod; fix = key Temporal's StripeTransfer on the queue row id like legacy, do it WHEN wiring Temporal); refund-before-commit atomicity in CancelOrder/tryStaleReject (needs idempotent-refund + reconcile design — current order fails toward free-food, flip-first fails toward charged-customer; don't change without the reaper); LinkProvider multi-account (needs a UNIQUE constraint migration on user_auth_providers); courier-no-Stripe-Connect payout; kosher-cert change post-approval (lock + re-review = product call); courier one-at-a-time busy-guard (product call). Full details in `docs/bug-backlog-2026-06-25.md`.
- **Flagged (need product/migration/ops decisions, NOT auto-fixed):** courier-payout double-pay (legacy↔Temporal idempotency keys — coordinated), courier-payout amount/2.5% model, UTC "today" reporting (needs restaurant TZ), refund-before-commit atomicity in CancelOrder/tryStaleReject (needs idempotent-refund design), external-cancel post-pickup stranding (where's the food?), `dispatching` sentinel reaper, LinkProvider multi-account (needs DB constraint), self-delivery tip→seller earnings (product), kosher-cert change post-approval (trust/product), device-token overwrite (intentional for login/logout — NOT a bug). Consumer apps have **no Uber-delivery tracking** (frozen map) — feature-sized. NOTE: single-skeptic verify is permissive — re-verify before applying (e.g. the device-token "hijack" overwrite is intentional for login/logout hand-off; not fixed). Notable un-fixed highs: courier-payout double-pay (legacy↔Temporal idempotency-key mismatch), consumer apps have **no external/Uber delivery tracking** (frozen map), self-delivery tip dropped from seller earnings, external-cancel webhook strands orders, `dispatching` claim sentinel can leak (no reaper), couriers can poach self-delivery orders, checkout address change doesn't re-price.
- Cycles 2 & 3 NOT yet run — pending decision (see report): burning down this backlog likely beats finding ~200 more.

## 🐛 Bug-fix cycle — 2026-06-25 (seller order/settings; all build-green, uncommitted)
- [x] **iOS misleading delivery status (HIGH-frequency):** `courierStatusCard` showed "Waiting for a courier to claim this order…" (spinner, never resolves) for EVERY Uber-dispatched order — there's no platform courier row, so the no-courier branch fired. Now: when `external_delivery_id` is set, shows "Handed to a delivery partner — a courier is on the way" / "Out for delivery with a partner courier". (Fix enabled by the new `externalDeliveryId` model field.)
- [x] **iOS escalate button lingered + stale status after escalate:** `escalateButton` calls the escalate API directly (returns `EscalateResponse`, not an `Order`) and never updated `vm.orders`; `syncOrderFromVM` is cache-first, so it kept the stale list copy (which doesn't even carry `external_delivery_id`) → button stayed on, status never flipped. Fixed by forcing `vm.fetchOrder(id:)` before sync. (Android was already correct — its VM escalate re-fetches via `getOrderDetail`.)
- [x] **iOS silently flipped `platform`→`external` on any settings save:** the whole-object `PUT` always wrote `restaurant.deliveryMode = deliveryMode`, and the toggle seeds a platform restaurant to "external" — so an unrelated settings save rerouted a platform-fleet restaurant's orders to Uber. Now guarded by a seeded-compare (only commit when the seller actually moved the toggle), matching the Android `buildRestaurantChanges` protection.
- Verification: backend `go vet` clean + full handler integration suite green (test PG on :5433); iOS `xcodebuild` BUILD SUCCEEDED. Reviewed dispatch claim-CAS / escalate guard / 50-50 split / scheduler mode-filter — all correct (no fix needed).

## ✅ Done 2026-06-23
- [x] T1 — land branch (merged `4bc667e5`)
- [x] T2 — Fly DB pre-flight (validated by clean boot; migration 043 unique index applied → zero dup redemptions)
- [x] T3 — deploy backend (rev `…D9F37`, `/health` 200)
- [x] T6 — Firebase/APNs key rotation (deleted leaked SA key `c484c84a3c`; APNs already rotated to `77W7RLLZTB`)
- [x] #1 — delivery PI/order $6 mismatch: backend stamps `delivery_fee` into PI metadata + `CreateOrder` reuses it (drift-proof); `/payments/intent` quotes cart's restaurant; iOS sends `delivery_address` (Android already did). **Order now goes through (confirmed).**
- [x] #2 — doubled-email 500: `mail.ParseAddress` guard deployed + prod row corrected (`mamiyesammy@gmail.com`)
- [x] #4 (partial) — checkout PI + add-card SetupIntent are card-only; **Link disabled on the KE Stripe account**
- [x] Committed deployed changes (`0c0c1875` on main; HEAD == prod)
- [x] Consumer **4.2 (3)** archived + uploaded to TestFlight

## 🚚 DELIVERY DISPATCH — launch-blocker (added 6/24)
**Strategy:** bootstrap delivery with 3rd-party couriers (Uber Direct now, DoorDash when verified) — a brand-new app has NO courier supply, so the in-house courier app is a LATER channel. Backend `external-dispatch` (dispatcher.go ~590) already picks cheapest provider per order + falls back.
- [x] Uber Direct **quote** confirmed working (live keys, prod `api.uber.com`, the $11.99).
- [ ] **Uber Direct DISPATCH (`CreateDelivery`) UNVERIFIED in prod** — wired in `external-dispatch`, fires after seller accepts/ready. Never exercised (pickup test bypassed it). MUST verify before delivery launch.
- [x] **Uber account mode = TESTING/sandbox** (6/24). Uber Direct account "Kosher Shop" has prod + testing envs; backend deploys the **testing** creds (customer `5fe655a7-…-badfe1fa180b`, client `oE-dcKx…`) — NOT prod (`54c8a18a…`/`jjKE4k30g…`). So CreateDelivery = robo-courier sim, safe to test. **GO-LIVE later: swap backend UBER_DIRECT_* to the production creds** (like the Stripe cutover).
- [x] **Prereqs fixed 6/24 for the dispatch test:** (a) set placeholder phones on both consumer accounts (`+13477120001/2`) — Uber requires a dropoff phone; **swap for Salto's real # if live**; (b) dispatch graces now env-tunable (`AUTO_DISPATCH_GRACE_SECONDS`/`EXTERNAL_DISPATCH_GRACE_SECONDS`), default **30s** (was 5min) so Uber dispatches ~1min after 'ready' instead of idling. Restaurant data (Pizza Kids N Action) already valid: phone, address, lat/lng. Platform mode falls through to Uber when no own courier (confirmed).
- [ ] **Verify Uber status webhook** (assigned→picked_up→delivered) flows back (`UBER_DIRECT_WEBHOOK_SECRET` set, handler exists).
- [ ] **DoorDash Drive = OFF** (no `DOORDASH_*` keys → stub). Needs merchant onboarding/verification (business task); code ready, just needs keys.
- [ ] **Courier-dedicated app = DEFERRED** until there's demand/supply (3rd-party covers delivery at launch).
- [ ] Make the seller-accept verification test a **delivery** order so it exercises dispatch + push + completion together.

## 💸 Delivery pricing — REWORKED & SHIPPED 6/24 (pass-through model)
Per Salto's model (replaced the earlier min/free-delivery attempt): **consumer pays the cheapest courier (Uber/DoorDash) quote + a flat markup we keep. No minimum, no free delivery, no floor/ceiling.**
- [x] Pass-through markup shipped (`71311735`, deployed): **+$1 normally, +$2 once item subtotal > $40**. Everything is deliverable regardless of order size. `quoteDeliveryFee` takes subtotal for the tier; floor/ceiling clamps removed; `/delivery/quote` preview matches checkout.
- [ ] **Evaluate:** tune live — `DELIVERY_MARKUP_CENTS` (100), `DELIVERY_MARKUP_LARGE_CENTS` (200), `DELIVERY_LARGE_ORDER_CENTS` (4000) via `fly secrets set … -a koshereats-api`.
- [ ] **Note — no cap anymore:** removed the old $11.99 ceiling, so an extreme courier quote passes straight through (+markup). Tied to verifying Uber quotes are sane for real routes (see delivery-dispatch section). Re-add a sanity cap if quotes ever spike.

## 🛵 DELIVERY MODEL — spec from Salto (6/24); BACKEND BUILT 6/25
- [x] **Uber Direct dispatch PROVEN** (6/24): order 8b939d41 → external mode → `CreateDelivery` → `del_75os5…` (test/robo).
- [x] **BACKEND BUILT, VERIFIED & DEPLOYED** (`73eddd1f`, 6/25; migration 044 confirmed applied in prod). Adversarial verify caught + fixed 3 real bugs pre-deploy (double-charge race via courier-claim-between-claim-and-create; silent dispatch-skip on client disconnect; courier-vs-dispatch collision). Gates all green.
  - New `internal/dispatch` pkg — shared dispatch fn with **claim-before-create CAS** (fixes a double-create money-loss race the mapping caught). Scheduler + handlers both call it.
  - **Instant inline dispatch** on 'ready' for `external` mode (fire-and-forget, no 60s wait); courier broadcast suppressed for external/restaurant.
  - **Per-order `PATCH /seller/orders/{id}/escalate`** (own→Uber, one-way lock via the claim CAS, synchronous, returns tracking URL).
  - **50/50 split**: migration `044_seller_delivery_earnings`; recorded once in `SellerDeliverOrder`'s status CAS, keyed off who actually delivered (courier_id/external_delivery_id); surfaced on the seller dashboard (`today_delivery_earnings`).
  - **Toggle**: no backend change needed — `PUT /seller/restaurant` already handles `delivery_mode`.
- [x] **Seller-app UI — iOS** (built + demoed live in sim 6/25, logged in as jo@ke against prod): (1) "Who delivers" segmented toggle in Settings→Delivery w/ dynamic caption (Uber: auto-dispatch on Ready; "I deliver": keep 50%, can still escalate); (2) "Delivery Earnings" dashboard card (`today_delivery_earnings`); (3) "Dispatch to Uber" escalate button on accepted/preparing delivery orders. `xcodebuild` green; 3 screenshots captured.
- [x] **Seller-app UI — Android (koshereats `android/seller`)** (6/25): mirrored all 4 pieces — `DashboardStats.today_delivery_earnings` + `EscalateResponse` models, `PATCH .../escalate` in ApiService, `escalateOrderToUber` in OrdersViewModel, "Dispatch to Uber" (`EscalateToUberButton`), "Who delivers" toggle (`DeliveryModeSegment`) in Settings threaded through `buildRestaurantChanges` (compares vs seeded mode so untouched saves don't flip platform→external), "Delivery Earnings" StatCard on Dashboard. **Demoed live in Pixel 8 emulator (6/25)** — all 4 elements + escalate-at-Ready confirmed; 3 screenshots captured. (Demo used a temp `DEV_BASE_URL=prod` in local.properties, since reverted; the debug→localhost guardrail is restored.)
- [ ] **`android/greeneats-seller` intentionally NOT touched** — it's a stale copy-paste fork (frozen ~May 31, slated for **deletion** per `docs/white-label-consolidation.md`); it inherits this feature when both brands collapse into one flavored module. Porting into the fork now would deepen the drift the plan warns against.
- [x] **Escalate-at-Ready — CLOSED (6/25, both platforms + backend deployed):** "Dispatch to Uber" now also shows on **Ready** delivery orders that are unclaimed/undispatched. Unified the gate to `courier == null && external_delivery_id == null && !isPickup` (matches the backend escalate guard) across accepted/preparing/ready. Backend now serializes `external_delivery_id` in `loadOrderWithCourier` (was never scanned before) so the button hides once a provider owns the order — deployed to Fly 6/25. iOS `externalDeliveryId` model field + Ready branch; Android `externalDeliveryId` field + `canEscalate` param. Verified live in the Android emulator (self-delivery Ready order shows both "Mark Picked Up" + "Dispatch to Uber"). Also observed: the instant-dispatch scheduler re-dispatches an external-mode Ready order within ~1 min of `external_delivery_id` going null — confirms auto-dispatch is aggressive/working.
- [ ] **Product flags from the build (confirm):** KE keeps 50% on self-delivered but 0% on KE-courier orders (earns more self-delivered — intended?); escalating a self-delivery order to Uber: platform absorbs the provider-cost-vs-customer-fee delta (no re-charge); consumer app must use `external_tracking_url` for Uber-delivered orders (the SSE courier stream doesn't apply).
- [ ] **Cleanup:** offline seed test courier `courier@koshereats.dev`; refund delivery-test charges `pi_3Tm2R4…` ($17.26) + `pi_3Tm2cZ…` ($17.80).

## 🔴 NOW / urgent
- [ ] **Payment methods** (investigated 6/23). Target: **iOS = Apple Pay + card**, **Android = Google Pay + card**.
  - [x] **Link**: account-level → disabled on the **KE** Stripe account. No rebuild; gone on next fresh checkout (reopen the sheet). Removes Link on BOTH platforms.
  - [x] **Android Google Pay + no-ACH**: FIXED (`CheckoutScreen.kt` — `GooglePayConfiguration` env-tracks the Stripe key; `allowsDelayedPaymentMethods=false`). Committed `892fa81c`. **Signed release AAB built (v1.0.9 / versionCode 12) at `android/consumer/app/build/outputs/bundle/release/app-release.aab` — needs MANUAL upload to Google Play Console (no Play automation configured).**
  - [ ] **iOS Bank**: checkout PI is card-only server-side AND 4.2(2) already has `allowsDelayedPaymentMethods=false`, so Bank should NOT show on the checkout sheet against the current backend. The Bank in the screenshot is most likely an **older build** and/or a **stale saved us_bank_account** on the Stripe Customer. Verify on 4.2(3); if it persists, delete the saved bank PM in the Dashboard. (No new iOS code needed for checkout.)
  - [ ] **iOS "add a card" screen**: used automatic methods (Link+ACH) until today's `0c0c1875` fix → needs build 4.2(3) to land.
  - [ ] Confirm live `/payments/intent` returns `payment_method_types:["card"]` (create a test PI + inspect)
- [ ] **Verify delivery checkout E2E on TestFlight 4.2(3)** once it processes (also confirms push — the original open question)
- [ ] **Public App Store app is months-old** vs a backend 43+ commits ahead → current public users may be broken. Submit current consumer build (4.2(3)) for review.
- [ ] **Seller + courier flows untested in prod** — exercise end-to-end (only consumer checkout tested so far)

## 🟢 Stripe / payments — LIVE KEYS SET (6/23 cutover)
- [x] **Account activated + live-ready** ("Go live" all green; live keys exist).
- [x] **Live Fly secrets set** by Salto (`STRIPE_SECRET_KEY`/`PUBLISHABLE_KEY`/`WEBHOOK_SECRET`); machine restarted, `/health` 200, not stub mode.
- [x] **`stripe_customer_id` nulled** at cutover (8 cleared, 0 remain).
- [x] **Live test order SUCCEEDED 6/23** — real CC, pickup: `/payments/intent` 200 → `POST /orders` **201** (order e31359d3). Confirmed live + the delivery-fee fix holds. (Refund the test charge in live Dashboard.)
- [x] Webhook confirmed LIVE (Stripe sent a live event) — but see webhook-version bug below.
- [x] **Webhook API-version mismatch** — FIXED (`38e6b96d`): `ConstructEventWithOptions{IgnoreAPIVersionMismatch:true}` (signature still verified). Unblocks payout-ready + dispute events. Stripe will retry the 400'd events automatically (or hit Resend in the dashboard).
- [x] **`location/stream` 500** — FIXED (`38e6b96d`): root cause was the logger's `statusRecorder` hiding `http.Flusher` → 500'd EVERY SSE stream (delivery tracking too, not just pickup). Added `Unwrap()` + flush via `http.ResponseController`.
- [~] **Push root-caused + fixed 6/24** — pushes WERE firing but APNs returned **400 BadDeviceToken**: the seller device's token is a **sandbox** token (registered by a dev/Xcode build), while the backend sends to **production** APNs (`APNS_PRODUCTION=true`). Ruled out: topic (matches `com.koshereats.seller`), token format (valid 64-hex), auth key (would be 403). **Fix deployed:** `apns.go` now retries on the alternate APNs host on BadDeviceToken (dev + TestFlight + App Store all deliver). Added reason logging. **Pending: confirm banner actually arrives after this deploy.**
- [x] First live order verified: $3.27 charge succeeded + order created, then **auto-rejected+refunded 10 min later** by `sweepStaleRejection` (no seller accepted). Correct protective behavior — not a bug. Charge already refunded (no manual refund needed).
- [ ] **Next E2E test (collapses 3 questions into 1):** log into seller app → place order → ACCEPT as seller → confirms (a) order stays/doesn't auto-refund, (b) seller PUSH fires, (c) accept flow. Note: real sellers must accept within ~10 min or orders auto-cancel+refund.
- [ ] Did NOT roll the `sk_live_` key (was exposed in a Google Doc; Salto deleted the doc, chose not to roll). Residual risk accepted.

## 🟠 Known HIGH-severity bugs (deferred 6/21) — UPDATE 6/23: appear FIXED in main
- [x] Verify the **6 "safe fixes"** from 6/21 — **ALL SURVIVED**, committed in `c20a9859` + `2152afd1`, ancestors of deployed main. Nothing lost.
- [x] Courier payout **double-pay** — effectively resolved: real double-*charges* are already prevented by the Stripe idempotency key (`p.id`) in `TransferToCourier`, and the lock-gap only bites under concurrent sweepers (impossible on the single Fly machine). Proper atomic-claim fix is built + gated behind Temporal (migration `039`, commit `f8dcab87`). Full cutover = T7.
- [~] Password-reset **cross-account** — appears fixed in main (migrations `037`+`041_reset_code_attempts`; password_reset.go scopes by (email, role, vertical) + caps attempts). **Confirm + needs the matching client to be shipped.**

## 🟠 Unshipped fixes & review gaps
- [x] **PR #1** — cherry-picked (a)+(b)+(c)+(d) into main as `4614e86a` and **CLOSED** the PR. (a) category move, (b) today-revenue re-expressed vs `discount_cents`, (c) webp, (d) web `.dockerignore`+`Dockerfile.local`. Backend redeployed.
  - [ ] (e) `CancelOrder` scheduled-order cancel — still deferred, re-apply ONLY paired with the mobile client change
- [ ] Run **`/security-review`** on backend money paths (skipped before deploy)

## 🟡 App releases (all stale)
- [ ] Consumer → **App Store submission** (4.2(3) uploaded, ready)
- [ ] Seller → rebuild + TestFlight + App Store
- [ ] Courier → rebuild + TestFlight + App Store

## 🟢 Planned / board (T4, T7–T9) + product decisions
- [ ] T4 — optional Fly secrets: `SENTRY_DSN`, `ADMIN_DASHBOARD_KEY`, `ADMIN_ALERT_EMAIL`, `STRIPE_TAX_ENABLED`
- [ ] T7 — Temporal Cloud provisioning + payout cutover (the *proper* double-pay fix)
- [ ] T8 — GreenEats white-label consolidation (Android consumer flavor pilot first)
- [ ] T9 — Clover POS go-live (needs Clover creds/secrets)
- [ ] Seller menu-import **V5 (Android)** — last piece of the UberEats importer
- [ ] Product/ops: dashboard "today" timezone (restaurant-tz column); server-side filter/pagination + multi-cert; partial-PATCH seller-restaurant endpoint

## ⚪ Housekeeping
- [ ] Confirm Fly `APNS_KEY_ID == 77W7RLLZTB` (or let the order-push test confirm it)
- [ ] Clean up ~30 stale `agent/*` worktree branches

## 🔬 Adversarial bug-hunt — cycles 2-3 (2026-06-25) — full list in `docs/bug-backlog-2026-06-25-cycles23.md`
Second sweep (cycle-1's 102 excluded): **136 NEW confirmed — 1 critical, 45 high, 48 medium, 42 low.** Fixed + deployed:
- [x] **CRITICAL — phone/OAuth account takeover** via derivable synthetic password ("phone-"+phone) + no auth_provider guard in /login. Login guard + crypto-random synthetic passwords + migration 045 + regression test. Deployed + live.
- [x] **HIGH — self-pickup ↔ Uber-escalate TOCTOU** (double delivery): guards on both SellerPickupOrder + dispatch claim CAS.
- [x] **HIGH — courier could claim a pickup order**: `fulfillment_type='delivery'` guard on ClaimOrder.
- [x] **HIGH — DoorDash pickup webhook too narrow** (stranded escalated-while-preparing orders): widened to match Uber.
- ⏳ **Client highs (16) being fixed** by a per-app worktree workflow (ios/android seller, android consumer/courier, ios courier).
- ⏳ **~38 backend/other highs + 48 med + 42 low remain** — documented with fixes. Many need product/migration decisions; the med/low tier is single-skeptic-verified so expect some false positives. **This is a real backlog larger than one session — needs prioritization.**

## 🔁 Highs re-triage + fixes — 2026-06-25 (on main)
Re-triaged all 44 open highs against current code: **27 real · 8 need-decision · 8 already-fixed · 1 false-positive**. Re-triage also caught a risk in my own dispatch-reaper. Fixed this pass (all build/test-green, deployed):
- [x] **reaper window 2m→10m** — my earlier reaper could reset a still-in-flight dispatch → orphaned paid delivery.
- [x] **EscalateToUber on detached context** — request-cancel mid-dispatch orphaned a paid delivery (→ double-pay re-dispatch).
- [x] **CreatePaymentIntent cart `rows.Err()`** — PI priced off a truncated cart (undercharge).
- [x] **pickup-PI free-delivery exploit** — stamp + verify `fulfillment_type` on the PaymentIntent.
- [x] **LinkProvider OTP brute-force lockout** — phone-link bypassed the lockout phone-login enforces (shared `verifyPhoneOTP`).
- ⏳ **Still real & open (~22):** UpdateProfile unverified-phone (SECURITY — flagged, needs a phone-change-flow product call), webhook-stranding cluster (delivered/pickup webhook drops), CancelOrder-refunds-a-dispatched-order, ClaimOrder busy-guard, ListCourierActiveOrders `rows.Err()`, external-persist-error orphan, Android seller courier-contact card (the deferred merge). All in the backlog docs with fixes.
- **8 need-decision** (NOT auto-fixable): courier-comp model + the 2.5% bump, legacy↔Temporal payout idempotency, courier-without-Stripe-Connect, `user_auth_providers` unique constraint (migration), refund/dispute→halt-payout.

## ✅ The 8 "needs-decision" highs — RESOLVED (2026-06-25, walkthrough w/ Salto)
All implemented + deployed (or noted), build/test-green:
- [x] **#1 Courier comp** — dropped the 2.5% bump (loss funded by a $0 service fee); payout = delivery_fee + 100% tip. Added `orders.provider_fee_cents` (migration 047) so external-delivery margin is explicit.
- [x] **#2 Payout idempotency (Temporal cutover)** — deferred (Temporal off); TODO at the legacy payout site documenting the double-pay risk + the fix (align both on `payout-`+orderID).
- [x] **#3 Courier without Stripe Connect** — chose backfill: queue the payout with a NULL connect id (migration 048), sweep skips NULL-connect rows, `account.updated` webhook backfills the id on onboarding → backlog paid.
- [x] **#4 `user_auth_providers` unique constraint** — migration 046: de-dup + UNIQUE(provider, provider_id) (one external identity → one account).
- [x] **#5 Refund/dispute → halt payout** — StripeWebhook halts a pending courier_payout_queue row for a refunded/disputed order.
- [x] **#6 UpdateProfile unverified phone** — built the verified phone-change flow (POST /user/phone/change/{start,verify}); UpdateProfile no longer writes phone.

**Session grand total: ~62 bugs fixed**, all 4 criticals + every clear money/security high + all 6 product decisions deployed. `main` carries everything (~42 commits since the feature base); backend healthy throughout.
