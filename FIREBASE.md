# Firebase / FCM setup

One-time steps to enable Android push notifications. The code is already in
place — this document just captures the values you have to drop in.

The Android apps initialize Firebase **manually** from `BuildConfig` values
that are read out of each project's `local.properties`. This avoids the
`google-services` Gradle plugin, which would require `google-services.json`
checked into each app directory and break builds when the file is missing.

---

## 1. Create a Firebase project

1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Name it `koshereats` (or similar). Turn off Google Analytics — not needed.
3. Wait for provisioning.

---

## 2. Register three Android apps

Inside the Firebase project, click the **gear icon → Project settings → Your apps → Add app → Android**. Do this **three times**, once per app:

| App label       | `applicationId`              |
|-----------------|------------------------------|
| KosherEats      | `com.koshereats.consumer`    |
| KosherEats Seller | `com.koshereats.seller`    |
| KosherEats Courier | `com.koshereats.courier`  |

For each one:
- Leave the SHA-1 field blank (you only need it for Google Sign-In, not FCM).
- Skip the "download google-services.json" step — we don't use it.
- Skip the "add the plugin to your build" step — we don't use it.

After registering all three, back on **Project settings → General**, you'll see
a "Your apps" section listing all three. You need **four values**:

- **Web API Key** (top of the page, under "Project ID") — same for all three apps
- **Sender ID** (in General → "Cloud Messaging" tab) — same for all three apps
- **Project ID** (top of the page) — same for all three apps
- **App ID** (`1:xxx:android:yyy`) — **different for each app**, listed under each app's section

---

## 3. Drop the values into each `local.properties`

Each Android project has its own `local.properties`. Open all three and append:

```properties
# Firebase — shared
FIREBASE_PROJECT_ID=koshereats-prod
FIREBASE_API_KEY=AIzaSyXXXXXXXXXXXXXXXXXXXX
FIREBASE_SENDER_ID=123456789012

# Firebase — per-app (pick the right App ID for each project)
FIREBASE_CONSUMER_APP_ID=1:123456789012:android:abcdef1234567890
FIREBASE_SELLER_APP_ID=1:123456789012:android:fedcba0987654321
FIREBASE_COURIER_APP_ID=1:123456789012:android:1234567890abcdef
```

Only the App ID that matches the project is actually read by that project's
`build.gradle.kts`, but having the full set in each `local.properties` makes
onboarding a new machine trivial — one file to copy.

After editing, rebuild. Logcat will print `Firebase initialized for project=...`
on first launch if everything was wired correctly, or
`Firebase keys missing in local.properties — skipping FCM init` if anything is
still blank.

---

## 4. Backend: service account key

The Go backend signs OAuth2 assertions to call the FCM HTTP v1 API. You need
a **service account** with the `Firebase Cloud Messaging API` permission.

1. Firebase console → **Project settings → Service accounts → Manage service account permissions** (this opens Google Cloud Console IAM).
2. Find the service account named `firebase-adminsdk-XXXXX@<project>.iam.gserviceaccount.com` — it was auto-created with your project. Make sure it has the role `Firebase Cloud Messaging API Admin` (or the broader `Firebase Admin SDK Administrator Service Agent`).
3. Click the three dots → **Manage keys → Add key → Create new key → JSON**. Download the file.
4. **Do not commit this file.** It's an API credential equivalent to a password for push sending.

Load it into Fly as a multiline secret:

```sh
fly secrets set FCM_SERVICE_ACCOUNT_JSON="$(cat /path/to/firebase-adminsdk-xxxxx.json)" --app koshereats-api
fly secrets set FCM_PROJECT_ID="koshereats-prod" --app koshereats-api
```

(`FCM_PROJECT_ID` is technically redundant — the JSON file also contains the
project id — but setting it explicitly lets you point a staging backend at a
different Firebase project without swapping the whole service account.)

For local dev, just `export FCM_SERVICE_ACCOUNT_JSON="$(cat firebase-adminsdk-xxxxx.json)"` before starting the backend. If the var is empty the backend runs in "stub" mode — Android pushes get logged but not sent. Same pattern as APNs.

---

## 5. Verify end-to-end

1. Build + install the courier Android app on a device (emulator won't work — FCM needs Google Play Services and an actual token endpoint).
2. Log in. Logcat should show `[PushBootstrap] FCM token registered`.
3. From a second machine or the seller/consumer app, transition an order to `ready`. The backend's `OrderReady` event fires and the courier device should receive a notification.
4. If the push doesn't arrive, check:
   - Backend logs for `[fcm] ready — project=...` at startup (means the service account was parsed).
   - `device_tokens` table — the courier's row should have `platform = 'android'` and a populated `token` column.
   - Backend logs on the `OrderReady` call — look for `[fcm]` lines. `[fcm stub]` means the service account wasn't loaded. `[fcm] non-2xx status=...` means the real API rejected the call (usually invalid token or project mismatch).

---

## 6. What's already in place

You don't need to touch any of this — it's already wired:

- **`backend/internal/notify/fcm.go`** — FCM HTTP v1 client. OAuth2 JWT-bearer grant flow, access token caching with 5-minute expiry buffer, POST to `/v1/projects/{project}/messages:send` with Android high-priority payloads.
- **`backend/internal/notify/notifier.go`** — dispatches per device platform. iOS tokens → APNs, Android tokens → FCM. The old SQL filter that hardcoded `platform='ios'` is gone.
- **`android/<app>/app/build.gradle.kts`** — loads Firebase BOM + `firebase-messaging-ktx`, injects four `BuildConfig` fields from `local.properties`.
- **`android/<app>/app/src/main/java/.../push/PushBootstrap.kt`** — manual `FirebaseApp.initializeApp` from `BuildConfig`. Skips init gracefully if any field is blank.
- **`android/<app>/app/src/main/java/.../push/KosherEatsMessagingService.kt`** — `FirebaseMessagingService` subclass. Handles `onNewToken` (registers with backend) and `onMessageReceived` (shows a local notification when the app is in the foreground).
- **`AndroidManifest.xml`** — `<service>` entry for the messaging service, `POST_NOTIFICATIONS` permission for API 33+.
- **`AuthViewModel`** in each app — calls `PushBootstrap.registerCurrentToken(...)` after login / signup / resumed session.

The notification small-icon is currently `android.R.drawable.sym_def_app_icon`
(Android's default app icon) — swap this for a dedicated monochrome vector
drawable before launch. Search for `TODO: replace with a dedicated monochrome`
to find the three places it's used.
