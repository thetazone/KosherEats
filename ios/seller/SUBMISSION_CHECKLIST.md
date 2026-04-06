# KosherEats Seller — App Store Submission Checklist

Parallel to the consumer app's checklist but specific to the seller-facing
restaurant management experience. See `ios/consumer/SUBMISSION_CHECKLIST.md`
for anything that applies to both apps (signing, Apple Dev account, etc.).

---

## 1. Xcode project + signing

- [x] `com.koshereats.seller` bundle ID
- [x] Portrait + landscape orientations
- [ ] Same `DEVELOPMENT_TEAM` as consumer (blank in project.yml today).
- [ ] Provisioning profile for `com.koshereats.seller` in Apple Dev portal.
- [ ] Enable Push Notifications capability (sellers get pushes on new orders).
- [ ] Enable Sign in with Apple (required since Google + Facebook are offered).

## 2. Privacy + permissions

- [x] `PrivacyInfo.xcprivacy` declaring: no tracking, UserDefaults access,
      collected data types (name, email, phone, physical address) — the
      seller provides their *own* identity + restaurant address; they don't
      collect customer data client-side.
- [x] `ITSAppUsesNonExemptEncryption = false`.
- [ ] Privacy policy URL (can share the consumer URL).
- [ ] Mirror `PrivacyInfo.xcprivacy` in App Store Connect → App Privacy.

## 3. App icon + accent color

- [x] 1024×1024 placeholder icon — blue (#2563EB) rounded square with
      white "S" on dark background. Intentionally different shape + color
      from the consumer icon (orange circle, "K") so users can tell them
      apart on their home screen.
- [x] `AccentColor` asset (blue #2563EB).
- [ ] Replace with a real designer-made icon before launch.

## 4. Third-party SDKs

- [ ] Replace Google + Facebook placeholder IDs in `Info.plist` (same as
      consumer).
- Sellers don't need Stripe SDK client-side — payouts are handled
  server-side via Stripe Connect (separate from consumer Stripe).

## 5. Backend readiness — seller-specific endpoints just added

- [x] `GET /seller/dashboard/stats` — returns today's orders, revenue,
      active count, avg prep time.
- [x] `PATCH /seller/restaurant/status` — toggle open/closed.
- [x] `PATCH /seller/menu/items/{id}/availability` — per-item 86.
- [x] `POST /seller/menu/categories` / `DELETE /seller/menu/categories/{id}`.
- [x] Order transition handlers return the full updated Order (not just a
      status map — was breaking decoding in iOS before).
- [x] `ListSellerOrders` now JOINs restaurant name + batch-loads line items
      so the dashboard renders in one round trip (previously N+1).

## 6. Known seller-side gaps

**Multiple restaurants per owner is broken.** The backend's
`GetSellerRestaurant` does `SELECT id FROM restaurants WHERE owner_id = $1`
with an implicit LIMIT 1. If a seller owns 5 restaurants (which the dev
seed user does), they only see the first one. **Fix before launch:** either
- return all owned restaurants and add a restaurant picker in the seller UI, or
- enforce one-restaurant-per-owner at registration.

**Menu item image upload is not implemented** — the `UpdateMenuItem` form
accepts name/description/price/dietary flags, but there's no image picker
or S3 upload wired in. Seed data handles this server-side; sellers
uploading new items will see placeholder icons until this is fixed.

## 7. TestFlight

- [ ] Archive + upload same as consumer.
- [ ] Test account: `seller@koshereats.dev` / `sellerpass` (seeded in
      `dev_seed.sql`). App Store Review needs credentials they can sign in
      with — share this account (or create a dedicated review one in prod).

## 8. Pre-submission smoke test

- [ ] Log in as seeded seller → see Shalom Grill with today's stats
- [ ] Toggle restaurant open/closed — confirm consumer side sees "Closed"
- [ ] Menu tab: see the 6 seeded items, open/edit one, save, see the update
- [ ] Orders tab: see the pending consumer orders
- [ ] Tap a pending order → Accept → Start Preparing → Mark as Ready
- [ ] After marking ready, courier app gets the broadcast push (TestFlight
      alongside the courier app, not solo)
- [ ] Push notifications arrive on new consumer order (requires real APNs)
- [ ] Pull-to-refresh works on all list screens
