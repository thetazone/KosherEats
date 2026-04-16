# KosherEats Consumer — App Store Submission Checklist

Everything you need to get the iOS consumer app into TestFlight and then onto
the App Store. Items marked `[ ]` are things you (Salto) still need to do;
items marked `[x]` are already handled in the codebase.

> **Note:** This is a pre-flight checklist, not a guarantee of approval. App
> Store Review is a human process — a reviewer might still ding you on
> something subjective. But having everything below in place eliminates the
> common mechanical rejections.

---

## 1. Xcode project + signing

- [x] `com.koshereats.consumer` bundle ID
- [x] `MARKETING_VERSION` 1.0.0, `CURRENT_PROJECT_VERSION` 1
- [x] iOS deployment target 17.0
- [x] Portrait + landscape orientations
- [ ] **Set `DEVELOPMENT_TEAM` in `project.yml`** (currently blank) — needs
      your Apple Developer Team ID. Then re-run `xcodegen generate`.
- [ ] Create an App ID + provisioning profile in Apple Developer portal for
      `com.koshereats.consumer`. Enable the Push Notifications capability.
- [ ] Enable Sign in with Apple capability in Xcode (required because the app
      offers Google social sign-in — Apple requires parity).

## 2. Privacy + permissions

- [x] `PrivacyInfo.xcprivacy` declaring: no tracking, UserDefaults access,
      collected data types (name, email, phone, address, precise location,
      payment info, purchase history) — all marked linked to user, none used
      for tracking.
- [x] `ITSAppUsesNonExemptEncryption = false` in `Info.plist` (we only use
      HTTPS, which is exempt).
- [x] `NSLocationWhenInUseUsageDescription` with a concrete user-facing reason.
- [ ] **Write a privacy policy** and host it at a public URL (e.g.
      `https://koshereats.com/privacy`). Apple requires one for every app.
- [ ] In App Store Connect → App Privacy, mirror what's in
      `PrivacyInfo.xcprivacy`. They have to match exactly or review will
      reject.

## 3. App icon + launch screen

- [x] 1024×1024 placeholder icon in `Assets.xcassets/AppIcon.appiconset/`
      (orange circle, white K, dark background). **This is a placeholder** —
      get a designer to produce a real icon before launch.
- [x] `AccentColor` asset catalog color (brand orange #F97316).
- [x] Launch screen auto-generated via `INFOPLIST_KEY_UILaunchScreen_Generation`.
      Matches the app's dark background. Swap in a logo image later if desired.

## 4. Third-party SDK configuration

The project has placeholder Google/Stripe keys that will fail at
runtime. Before you submit:

- [ ] **Google Sign-In:** Replace `com.googleusercontent.apps.YOUR_CLIENT_ID`
      in `Info.plist` with the real client ID from Google Cloud Console.
- [ ] **Stripe:** Set `STRIPE_SECRET_KEY` and `STRIPE_PUBLISHABLE_KEY` in the
      backend `.env` to real **live** keys (not test) before App Store release.
      Test keys are fine for TestFlight.

## 5. Push notifications

- [x] APNs token-auth backend client ready (`internal/notify/apns.go`).
- [x] iOS push registration wired (`AppDelegate.swift` + `PushNotifications.swift`).
- [ ] **Generate an APNs Auth Key** (.p8) in Apple Developer → Keys. Set
      `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_P8_KEY` env vars on the prod backend.
- [ ] Enable the Push Notifications entitlement on the App ID and in Xcode's
      Signing & Capabilities tab.

## 6. Backend readiness

- [ ] Backend deployed to a real host (not localhost). Point `APIService.baseURL`
      production branch at `https://api.koshereats.com/api/v1`.
- [ ] Postgres in a managed service (RDS / Supabase / Neon), not docker-compose.
- [ ] `STRIPE_SECRET_KEY` = live key, webhooks configured.
- [ ] `S3_BUCKET` + AWS credentials set for real document upload (currently
      stub mode).
- [ ] SSL cert on the API domain.

## 7. App Store Connect

- [ ] Create the app record in App Store Connect with the bundle ID.
- [ ] Upload screenshots for required device sizes:
      - iPhone 6.9" (Pro Max) — **required**
      - iPhone 6.5" — optional but recommended
      - iPad 13" if you want iPad support (currently iPhone-only)
- [ ] Marketing description, keywords, support URL, marketing URL,
      age rating (probably 4+ for a delivery app).
- [ ] Categories: Food & Drink (primary), Lifestyle (secondary).
- [ ] Demo account credentials (App Store Review needs a test account they
      can sign in with — create a dedicated `review@koshereats.dev` user).

## 8. TestFlight

- [ ] Archive a release build (`Product → Archive` in Xcode).
- [ ] Upload to App Store Connect via the Organizer.
- [ ] Add internal testers (yourself + anyone else) → test the full flow:
      sign up → browse → order → checkout → confirmation → order tracking.
- [ ] Invite external testers (up to 10k). Requires a short Beta App Review
      — usually same-day.

## 9. Pre-submission smoke test

Run through this list on a TestFlight build before hitting Submit for Review:

- [ ] Cold launch, no crash.
- [ ] Register a new account, confirm auth persists across app quit.
- [ ] Home screen loads restaurants with real images (network, not cached).
- [ ] Restaurant detail loads menu, modifiers work.
- [ ] Add to cart, see cart badge update.
- [ ] Checkout: address sheet, tip selector, Stripe PaymentSheet opens,
      test card succeeds.
- [ ] Land on confirmation screen, tap "Track your order", see live map.
- [ ] Background the app, receive a push notification, tap it, deep-link
      into the right order screen.
- [ ] Logout, log back in, see past orders.
- [ ] Pull-to-refresh works on Home + Orders.
- [ ] Empty states show correctly (new user with no orders).
- [ ] Error states show a retry button (turn off wifi mid-browse).

## 10. Known gaps to disclose / fix before v1.0

- **No reviews feature** — restaurants show aggregated ratings from seed data
  but users cannot write reviews. Deferred to post-MVP.
- **Seller app + courier app not yet submitted** — they're built but need
  their own App Store Connect records. Consumer can launch solo if sellers
  are onboarded via web admin.
- **Android apps not shipped** — iOS-only launch.
- **Menu item modifiers working on iOS**, not yet on the Android consumer
  app (Android scaffold exists but is unverified — no JDK on dev machine).
- **Stripe Connect courier payouts** work in dev stub mode; needs a real
  Connect platform account in Stripe Dashboard before couriers can be paid.

---

## Quick commands

```sh
# Regenerate Xcode project after editing project.yml
cd ~/projects/KosherEats/ios/consumer && xcodegen generate

# Archive for TestFlight (requires signing setup)
xcodebuild -project KosherEatsConsumer.xcodeproj \
  -scheme KosherEatsConsumer \
  -configuration Release \
  -archivePath build/KosherEats.xcarchive \
  archive

# Upload archive to App Store Connect
xcrun altool --upload-app \
  -f build/KosherEats.ipa \
  -t ios \
  -u YOUR_APPLE_ID \
  -p "@keychain:AC_PASSWORD"
```
