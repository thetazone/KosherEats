# KosherEats — Master TODO (live tracker)

The single working checklist. Deeper context in `docs/KE-WORK-HANDOFF.md`.
Last updated: 2026-06-23.

---

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

## 🔴 NOW / urgent
- [ ] **Payment methods** (investigated 6/23). Target: **iOS = Apple Pay + card**, **Android = Google Pay + card**.
  - [x] **Link**: account-level → disabled on the **KE** Stripe account. No rebuild; gone on next fresh checkout (reopen the sheet). Removes Link on BOTH platforms.
  - [x] **Android Google Pay + no-ACH**: FIXED in code (`CheckoutScreen.kt` — added `GooglePayConfiguration`, env tracks the Stripe key; `allowsDelayedPaymentMethods=false`). Compiles green. **Needs an Android rebuild to ship.**
  - [ ] **iOS Bank**: checkout PI is card-only server-side AND 4.2(2) already has `allowsDelayedPaymentMethods=false`, so Bank should NOT show on the checkout sheet against the current backend. The Bank in the screenshot is most likely an **older build** and/or a **stale saved us_bank_account** on the Stripe Customer. Verify on 4.2(3); if it persists, delete the saved bank PM in the Dashboard. (No new iOS code needed for checkout.)
  - [ ] **iOS "add a card" screen**: used automatic methods (Link+ACH) until today's `0c0c1875` fix → needs build 4.2(3) to land.
  - [ ] Confirm live `/payments/intent` returns `payment_method_types:["card"]` (create a test PI + inspect)
- [ ] **Verify delivery checkout E2E on TestFlight 4.2(3)** once it processes (also confirms push — the original open question)
- [ ] **Public App Store app is months-old** vs a backend 43+ commits ahead → current public users may be broken. Submit current consumer build (4.2(3)) for review.
- [ ] **Seller + courier flows untested in prod** — exercise end-to-end (only consumer checkout tested so far)

## 🔴 Stripe / payments
- [ ] **Still in TEST mode — cannot take real money.** Go-live per `docs/stripe-go-live.md` (must null `stripe_customer_id` on cutover; live webhook secret; Connect courier re-onboarding). Real-money decision.
- [ ] Confirm KE backend Stripe keys point to the **KE** account (`acct_1TJKmfJiyQbKV7Jz`), not HoneyOcean (there are multiple Stripe accounts)

## 🟠 Known HIGH-severity bugs (deferred 6/21) — UPDATE 6/23: appear FIXED in main
- [x] Verify the **6 "safe fixes"** from 6/21 — **ALL SURVIVED**, committed in `c20a9859` + `2152afd1`, ancestors of deployed main. Nothing lost.
- [x] Courier payout **double-pay** — effectively resolved: real double-*charges* are already prevented by the Stripe idempotency key (`p.id`) in `TransferToCourier`, and the lock-gap only bites under concurrent sweepers (impossible on the single Fly machine). Proper atomic-claim fix is built + gated behind Temporal (migration `039`, commit `f8dcab87`). Full cutover = T7.
- [~] Password-reset **cross-account** — appears fixed in main (migrations `037`+`041_reset_code_attempts`; password_reset.go scopes by (email, role, vertical) + caps attempts). **Confirm + needs the matching client to be shipped.**

## 🟠 Unshipped fixes & review gaps
- [ ] **PR #1** (`fix/koshereats-backend-review`) — **do NOT blind-merge** (conflicts on 5 files + regresses the web — main is more evolved). Branch tip is `283cc093` (not `0c4f556d`). **Cherry-pick these 4 still-unshipped fixes, then CLOSE the PR:**
  - [ ] (a) `UpdateMenuItem` category move = silent no-op (seller.go — no `category_id` in SET clause)
  - [ ] (b) Seller dashboard "Today's Revenue" overstated (`SUM(o.total)` includes tip/tax/fees) — re-express vs **`discount_cents`** (NOT the branch's `discount_amount`)
  - [ ] (c) webp upload support (`uploads.go` + `s3.go`)
  - [ ] (d) `web/.dockerignore` + `web/Dockerfile.local` (real Fly web build fix)
  - [ ] (e) `CancelOrder` scheduled-order cancel — re-apply ONLY paired with the mobile client change
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
