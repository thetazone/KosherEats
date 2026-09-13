# KosherEats Polish — Round 4
**Max severity found:** 8
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[8/10] [ke_bugs_backend] StartPhoneChange resets the phone-OTP lockout for any phone number** — FIXED
  phone_otp_starts is keyed by phone alone (migration 013) and shared by every OTP flow. StartPhoneLogin (phone_auth.go:66-72) deliberately refuses to s
  > Both fixes applied, backend builds and vets clean, test packages compile.

**`backend/internal/handlers/phone_auth.go`**

- **[7/10] [ke_bugs_backend] DeleteAccount nulls orders.user_id on live paid orders, permanently breaking the auto-reject refund** — FIXED
  DeleteAccount runs `UPDATE orders SET user_id = NULL WHERE user_id = $1` (user.go:284) with no status filter, so it anonymizes orders in pending/accep
  > All green. Here's what I changed.

## Root cause fix — `backend/internal/handlers/user.go`

`DeleteAccount` now refuses 

- **[6/10] [ke_bugs_backend] Token-epoch revocation check turns any transient DB error into a 401 logout for every user** — FIXED
  The new (uncommitted) revocation check calls currentTokenEpoch on every authenticated request and collapses all failure modes into one branch: `if err
  > Done. The fix is in `backend/internal/handlers/auth.go` + `email_otp.go`.

**What changed**

1. **Shared classifier** (`
