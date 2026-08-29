# KosherEats Polish — Round 2
**Max severity found:** 7
**Issues found:** 3
**Fixes attempted:** 3
**Fixes succeeded:** 3

## Issues & Fixes
- **[7/10] [ke_bugs_backend] Transient Stripe error silently disarms all four CreateOrder money guards** — FIXED
  CreateOrder read four PaymentIntent stamps via four separate paymentintent.Get calls, and every call site was written as `err == nil && ok && mismatch
  > The fix for this issue was already fully present in the working tree when I started (uncommitted, alongside other change

- **[6/10] [ke_bugs_backend] CreateOrder ran discarded live courier quotes inside the open order transaction** — FIXED
  CreateOrder called quoteDeliveryFee (up to three provider HTTP quotes, each with a 30s client timeout) on every delivery order and then unconditionall
  > The fix is already present in the working tree — `backend/internal/handlers/orders.go` (lines 243–298) already gates `qu

- **[5/10] [ke_bugs_backend] A rejected AddToCart destroyed the customer's existing cart** — FIXED
  AddToCart clears every cart_items row and re-points carts.restaurant_id when the restaurant changes, and that transaction was COMMITTED (cart.go:175) 
  > Fixed and verified. The two files named in the issue (`backend/internal/handlers/cart.go`, `backend/internal/handlers/in
