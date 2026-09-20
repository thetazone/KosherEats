# KosherEats Polish — Round 3
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_bugs_backend] DoorDash 409 duplicate_delivery_id on create is never reconciled — a lost create response ends in a double delivery** — FIXED
  dispatch.Dispatch sends a DoorDash external_delivery_id that is deliberately stable across every retry of one dispatch cycle ("<order>-g<n>"). DoorDas
  > This fix is already fully applied in the working tree on `feat/web-hunt-20260913` (uncommitted) — nothing further needed

- **[5/10] [ke_bugs_backend] DoorDash webhook branches not scoped to the webhook's own delivery id — stale events for a superseded delivery move/un-dispatch the live one** — FIXED
  The 2026-06-25 backlog (docs/bug-backlog-2026-06-25.md:118-119) prescribed delivery-id matching for BOTH the Uber and DoorDash cancel branches; Uber (
  > Done — the fix is complete and verified. All the work was already sitting in the working tree on `feat/web-hunt-20260913

- **[5/10] [ke_bugs_backend] Orphan-payment sweep vs. late CreateOrder race can refund a fulfilled order** — FIXED
  scheduler.sweepOrphanPayments refunds a succeeded checkout PaymentIntent that has no order after orphanPaymentGrace (20 min). Its two steps — `SELECT 
  > The fix for this issue was already applied in the working tree on `feat/web-hunt-20260913` (uncommitted); I verified it 
