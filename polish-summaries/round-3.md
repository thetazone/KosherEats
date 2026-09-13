# KosherEats Polish — Round 3
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_bugs_backend] Payout retry schedule outruns Stripe's 24h idempotency window, double-paying couriers** — FIXED
  In backend/internal/scheduler/dispatcher.go, payoutBackoffSecs (line 1584) returns 5m/15m/1h/6h/24h and maxPayoutAttempts is 6 (line 128), so a failin
  > Fixed. Both halves of the hazard are closed, in `backend/` only.

**`backend/internal/payments/stripe.go`**
- Added `Ide

- **[7/10] [ke_bugs_backend] Password reset does not invalidate existing sessions; refresh tokens are self-renewing and unrevocable** — FIXED
  There is no session store, token epoch, jti, or denylist anywhere in the backend (grep for token_version|revoked|jti|sessions across internal/ returns
  > Fixed. All changes are under `backend/`.

## What was wrong

`ResetPassword` rewrote `password_hash` and nothing else. R

- **[5/10] [ke_bugs_backend] Deleting a courier account cascade-deletes their outstanding payout queue rows** — FIXED
  backend/internal/database/migrations/009_courier_payout_queue.sql:23 declares courier_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE, and no 
  > Fixed. The cascade is gone and the financial record now outlives the courier's user row.

## Changes

**`backend/interna
