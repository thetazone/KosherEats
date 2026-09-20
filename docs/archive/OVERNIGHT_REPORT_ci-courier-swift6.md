# Overnight report — courier in CI + Swift-6 main-actor cleanup

Branch: `chore/ci-courier-and-swift6` (cut from `main`) · Date: 2026-09-13 · Nothing pushed, nothing deployed.

Closes both "noted, not blocking" items from the 2026-06-25 CI section of `docs/KE-TODO.md`:

- line 17 — "the Android + iOS CI matrices cover only consumer + seller — **courier** isn't built in CI"
- line 16 — "the iOS consumer job emits Swift-6 main-actor isolation **warnings** (`LocationManager.swift`, `OrderDetailView.swift`)"

---

## Job 1 — courier added to both CI matrices

### Discovery

| | value |
|---|---|
| iOS project | `ios/courier/KosherEatsCourier.xcodeproj` |
| iOS scheme | `KosherEatsCourier` (the only scheme; the only target is also `KosherEatsCourier`) |
| Android module | `android/courier` |

`xcodebuild -list -project ios/courier/KosherEatsCourier.xcodeproj`:

```
Information about project "KosherEatsCourier":
    Targets:
        KosherEatsCourier

    Build Configurations:
        Debug
        Release

    Schemes:
        KosherEatsCourier
```

The `greeneats-*` directories under `android/` and `ios/` were left alone (separate brand, out of scope).

### Diff — `.github/workflows/ci.yml`

Two additions, 5 lines total:

```diff
@@ -87,7 +87,7 @@ jobs:
     strategy:
       fail-fast: false
       matrix:
-        app: [consumer, seller]
+        app: [consumer, seller, courier]
     defaults:
       run:
         working-directory: android/${{ matrix.app }}
@@ -183,6 +183,9 @@ jobs:
           - app: seller
             project: ios/seller/KosherEatsSeller.xcodeproj
             scheme: KosherEatsSeller
+          - app: courier
+            project: ios/courier/KosherEatsCourier.xcodeproj
+            scheme: KosherEatsCourier
     steps:
       - uses: actions/checkout@v4
```

`ci.yml` is the only workflow file, and it holds the only `app:` matrix in the repo — nothing else needed updating.

### google-services placeholder guard — confirmed correct for courier

The placeholder step is gated `if: matrix.app == 'consumer'`, so adding `courier` to the matrix does **not** give it the placeholder. That is the behaviour we want: `android/courier/app/build.gradle.kts` applies only

```
com.android.application
org.jetbrains.kotlin.android
com.google.dagger.hilt.android
org.jetbrains.kotlin.plugin.serialization
kapt
```

— no `com.google.gms.google-services` — and the dependency block says so explicitly (~line 185):

> `// Firebase Cloud Messaging (Android push). Manual FirebaseApp init in`
> `// PushBootstrap — no google-services plugin, so builds work without a`
> `// google-services.json file being present. See FIREBASE.md for setup.`

`:app:processDebugGoogleServices` therefore never runs for courier. The existing comment above the step already reads "Seller/courier do manual FCM init (no plugin) and don't need this", so it stays accurate with no edit.

### Validation — the exact CI commands run locally for courier

**Android** — the workflow's build step is `./gradlew compileDebugKotlin --no-daemon --stacktrace` from `android/courier`.

First attempt failed on a *local-environment* gap, not on courier code:

```
* What went wrong:
Could not determine the dependencies of task ':app:compileDebugKotlin'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
  variable or by setting the sdk.dir path in your project's local properties file at
  '.../android/courier/local.properties'.
```

`local.properties` is gitignored so a fresh worktree has none; GitHub's `ubuntu-latest` runner exports `ANDROID_HOME` itself. Re-ran with `ANDROID_HOME=/Users/samma/Library/Android/sdk` (exactly what the runner provides) — **passing tail, verbatim**:

```
w: file:///.../android/courier/app/src/main/java/com/koshereats/courier/ui/screens/onboarding/OnboardingFlowScreen.kt:309:42 'DirectionsBike: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.DirectionsBike
w: file:///.../android/courier/app/src/main/java/com/koshereats/courier/ui/screens/onboarding/OnboardingFlowScreen.kt:312:42 'DirectionsWalk: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.DirectionsWalk
w: file:///.../android/courier/app/src/main/java/com/koshereats/courier/ui/screens/payouts/PayoutsSetupScreen.kt:102:5 Parameter 'onBack' is never used
w: file:///.../android/courier/app/src/main/java/com/koshereats/courier/ui/theme/Theme.kt:31:5 Parameter 'darkTheme' is never used
w: file:///.../android/courier/app/src/main/java/com/koshereats/courier/ui/theme/Theme.kt:39:20 'setter for statusBarColor: Int' is deprecated. Deprecated in Java

BUILD SUCCESSFUL in 35s
17 actionable tasks: 17 executed
EXIT=0
```

Only deprecation/unused-param warnings — the module compiles clean. No placeholder `google-services.json` was written, confirming courier doesn't need one.

**iOS** — the workflow's invocation, courier project/scheme, simulator destination (`xcbeautify` dropped since it's a CI-only pretty-printer):

```
xcodebuild build \
  -project "ios/courier/KosherEatsCourier.xcodeproj" \
  -scheme "KosherEatsCourier" \
  -destination "generic/platform=iOS Simulator" \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY=""
```

**Passing tail, verbatim:**

```
Touch /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsCourier-dhjyvsfqnzirqocxdamqddlzvvxj/Build/Products/Debug-iphonesimulator/KosherEatsCourier.app (in target 'KosherEatsCourier' from project 'KosherEatsCourier')
    cd /.../ios/courier
    /usr/bin/touch -c /Users/samma/Library/Developer/Xcode/DerivedData/KosherEatsCourier-dhjyvsfqnzirqocxdamqddlzvvxj/Build/Products/Debug-iphonesimulator/KosherEatsCourier.app

** BUILD SUCCEEDED **
```

The iOS job is `continue-on-error: true` (slow lane), so courier can't block merges regardless.

### YAML validation

```
$ python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"   # via a scratch venv; system python3 is PEP-668 managed
YAML OK

$ actionlint .github/workflows/ci.yml
actionlint EXIT=0        # installed via brew for this check; no output = clean
```

---

## Job 2 — Swift-6 main-actor isolation, consumer iOS

### Before

Default build settings, `xcodebuild build -project ios/consumer/KosherEatsConsumer.xcodeproj -scheme KosherEatsConsumer -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO`:

| file | warnings |
|---|---|
| `Services/LocationManager.swift` | **5** |
| `Views/Orders/OrderDetailView.swift` | **1** |

```
LocationManager.swift:39:18: warning: main actor-isolated property 'manager' can not be referenced from a Sendable closure
LocationManager.swift:46:34: warning: main actor-isolated property 'wasUpdatingBeforeBackground' can not be referenced from a Sendable closure
LocationManager.swift:47:18: warning: main actor-isolated property 'wasUpdatingBeforeBackground' can not be mutated from a Sendable closure
LocationManager.swift:48:31: warning: main actor-isolated property 'manager' can not be referenced from a Sendable closure
LocationManager.swift:50:22: warning: main actor-isolated property 'manager' can not be referenced from a Sendable closure
OrderDetailView.swift:228:9:  warning: switch must be exhaustive
```

### Changes

**`ios/consumer/KosherEatsConsumer/Services/LocationManager.swift`** — 4 changes

1. **Both `NotificationCenter` block observers now run their bodies inside `MainActor.assumeIsolated { … }`** (clears all 5 default-mode warnings). `addObserver(forName:object:queue:using:)` takes a `@Sendable` closure, so the compiler can't see that `queue: .main` already guarantees main-thread delivery; `assumeIsolated` states that fact. Chose it over `Task { @MainActor in … }` deliberately — the hop would make the handlers async and let a rapid background/foreground pair execute out of order, which would leave the location session in the wrong state. It also matches how the two `CLLocationManagerDelegate` callbacks in this same file already bridge.
2. **`locationManagerDidChangeAuthorization` now calls `self.manager.startUpdatingLocation()` instead of the callback's `manager` parameter.** The parameter is nonisolated and `CLLocationManager` isn't `Sendable`, so capturing it in the `MainActor` closure "sends" it across an isolation boundary (a Swift-6 hard error). It's the same object we already own on the main actor, so we reach for that copy; the `authorizationStatus` read still happens off the nonisolated parameter before the hop.
3. **The observer handles are boxed in a small `ObserverToken: @unchecked Sendable` struct.** `deinit` is always nonisolated even on a `@MainActor` class, so it cannot read a stored property whose type (`any NSObjectProtocol`) isn't `Sendable` — a Swift-6 hard error. The alternative (an async hop out of `deinit`) is worse: it would have to capture `self` mid-teardown. `NotificationCenter` tokens are opaque and `removeObserver` accepts them from any thread, so the `@unchecked` assertion is sound and documented at the declaration.
4. Rationale comments added at each of the three sites so the next reader doesn't "simplify" them back.

**`ios/consumer/KosherEatsConsumer/Views/Orders/OrderDetailView.swift`** — 1 change

5. **`statusColor(_:)` now matches `case .unknown` explicitly** instead of relying on `@unknown default`. `OrderStatus` is declared in this same module (`Models/Models.swift`) and carries its own `.unknown` catch-all for backend-added statuses; `@unknown default` only covers cases arriving from *other* modules, so the switch was genuinely non-exhaustive. Returns `.keTextSecondary` (neutral), which is what the `@unknown default` arm returned.

> Not a main-actor warning, but it was the file's only warning and the gate is "zero warnings referencing those two files".

### After — default build settings

```
** BUILD SUCCEEDED **
EXIT=0
```

- warnings referencing `LocationManager.swift` or `OrderDetailView.swift`: **0**
- both files confirmed genuinely recompiled in that run (`SwiftCompile normal arm64 …/LocationManager.swift`, `…/OrderDetailView.swift`) — not skipped by incremental caching

| file | before | after |
|---|---|---|
| `Services/LocationManager.swift` | 5 | **0** |
| `Views/Orders/OrderDetailView.swift` | 1 | **0** |

### Project-wide `SWIFT_STRICT_CONCURRENCY=complete` survey

⚠️ **Methodology note.** Passing `SWIFT_STRICT_CONCURRENCY=complete` on the `xcodebuild` command line applies it to *every* target in the build graph, including the vendored SPM packages. That makes `StripeCore` fail to compile — 7 `SwiftCompile … failed with a nonzero exit code` failures (`Timeout.swift`, `STPURLCallbackHandler.swift`, `ServerErrorMapper.swift`, …) with **no diagnostics printed at all**, so the app target never even got compiled and the survey returned nothing. To scope the setting to the app target only I temporarily added `SWIFT_STRICT_CONCURRENCY = complete;` to the two `XCBuildConfiguration` blocks that carry `SWIFT_VERSION = 5.9` in `project.pbxproj`, ran the survey, then **restored `project.pbxproj` byte-for-byte from a backup** — it is unmodified on this branch (`git diff --stat` shows only the 3 intended files).

That Stripe-SDK breakage is worth knowing before anyone flips the project to Swift 6 language mode for real: it has to be scoped per-target (or Stripe upgraded), because a project-level setting will take the SPM checkouts down with it.

**App-target results, after the fixes above.** Total distinct warnings: **30**; of those, isolation/`Sendable`/concurrency-related: **28**. Both target files are **clean even under `complete`** (`LocationManager` had 4 additional strict-only warnings before change #2/#3 above; all now gone).

By file — isolation/concurrency warnings only:

| file | count |
|---|---|
| `Services/Haptics.swift` | 10 |
| `Services/DeliveryActivityManager.swift` | 4 |
| `ViewModels/OrderTrackingViewModel.swift` | 2 |
| `ViewModels/CheckoutViewModel.swift` | 2 |
| `Services/APIService.swift` | 2 |
| `Views/Profile/ConnectedAccountsView.swift` | 1 |
| `Views/Profile/AddressFormSheet.swift` | 1 |
| `Views/Components/RemoteImage.swift` | 1 |
| `AppDelegate.swift` | 1 |
| **`Services/LocationManager.swift`** | **0** (was 4) |
| **`Views/Orders/OrderDetailView.swift`** | **0** |
| *(non-isolation: `DeliveryActivityManager.swift` + `OrdersListView.swift`, 1 non-exhaustive switch each)* | 2 |

Full list, for whoever picks this up:

```
AppDelegate.swift:7:59: conformance of 'AppDelegate' to protocol 'UNUserNotificationCenterDelegate' crosses into main actor-isolated code and can cause data races; this is an error in the Swift 6 language mode
Services/APIService.swift:135:38: main actor-isolated static property 'iso8601Fractional' can not be referenced from a Sendable closure
Services/APIService.swift:139:38: main actor-isolated static property 'iso8601Plain' can not be referenced from a Sendable closure
Services/DeliveryActivityManager.swift:65:28: sending 'activity' risks causing data races; this is an error in the Swift 6 language mode
Services/DeliveryActivityManager.swift:79:28: sending 'activity' risks causing data races; this is an error in the Swift 6 language mode
Services/DeliveryActivityManager.swift:102:28: sending 'activity' risks causing data races; this is an error in the Swift 6 language mode
Services/DeliveryActivityManager.swift:129:32: sending 'activity' risks causing data races; this is an error in the Swift 6 language mode
Services/DeliveryActivityManager.swift:139:9: switch must be exhaustive
Services/Haptics.swift:13:25: main actor-isolated static property 'isReduceMotionEnabled' can not be referenced from a nonisolated context
Services/Haptics.swift:20:19: call to main actor-isolated initializer 'init()' in a synchronous nonisolated context
Services/Haptics.swift:21:13: call to main actor-isolated instance method 'prepare()' in a synchronous nonisolated context
Services/Haptics.swift:22:13: call to main actor-isolated instance method 'notificationOccurred' in a synchronous nonisolated context
Services/Haptics.swift:29:19: call to main actor-isolated initializer 'init(style:)' in a synchronous nonisolated context
Services/Haptics.swift:30:13: call to main actor-isolated instance method 'prepare()' in a synchronous nonisolated context
Services/Haptics.swift:31:13: call to main actor-isolated instance method 'impactOccurred()' in a synchronous nonisolated context
Services/Haptics.swift:38:19: call to main actor-isolated initializer 'init()' in a synchronous nonisolated context
Services/Haptics.swift:39:13: call to main actor-isolated instance method 'prepare()' in a synchronous nonisolated context
Services/Haptics.swift:40:13: call to main actor-isolated instance method 'selectionChanged()' in a synchronous nonisolated context
ViewModels/CheckoutViewModel.swift:279:19: reference to class property 'defaultPublishableKey' is not concurrency-safe because it involves shared mutable state; this is an error in the Swift 6 language mode
ViewModels/CheckoutViewModel.swift:369:19: reference to class property 'defaultPublishableKey' is not concurrency-safe because it involves shared mutable state; this is an error in the Swift 6 language mode
ViewModels/OrderTrackingViewModel.swift:1:1: add '@preconcurrency' to suppress 'Sendable'-related warnings from module 'ObjectiveC'
ViewModels/OrderTrackingViewModel.swift:206:9: cannot access property 'pushObserver' with a non-Sendable type '(any NSObjectProtocol)?' from nonisolated deinit; this is an error in the Swift 6 language mode
Views/Components/RemoteImage.swift:159:64: main actor-isolated static property 'maxPixelSize' cannot be accessed from outside of the actor; this is an error in the Swift 6 language mode
Views/Orders/OrdersListView.swift:329:9: switch must be exhaustive
Views/Profile/AddressFormSheet.swift:267:30: sending 'results' risks causing data races; this is an error in the Swift 6 language mode
Views/Profile/ConnectedAccountsView.swift:340:43: sending 'result' risks causing data races; this is an error in the Swift 6 language mode
```

Notes for a follow-up pass:

- `OrderTrackingViewModel.swift:206` is the **exact same `deinit` + `NSObjectProtocol` token pattern** fixed in `LocationManager` — the `ObserverToken` box drops straight in. Cheapest win on the list.
- `OrdersListView.swift:329` is the same non-exhaustive `OrderStatus` switch fixed in `OrderDetailView`. Also a one-liner.
- `Haptics.swift` (10 of 28) is one file and almost certainly one root cause — `UIFeedbackGenerator` is `@MainActor` and the helpers are nonisolated. Marking the file's API `@MainActor` would likely clear all ten.
- Left untouched per scope: none of the above are in the two named files' call chains.

---

## Gating

Per `docs/KE-WORK-HANDOFF.md` § Gating, backend / web / Android-consumer / Android-seller / iOS-seller are untouched by this work, so the relevant gates are the app builds themselves:

| gate | result |
|---|---|
| `android/courier` — `./gradlew compileDebugKotlin --no-daemon --stacktrace` | ✅ BUILD SUCCESSFUL |
| `ios/courier` — CI's `xcodebuild build` | ✅ BUILD SUCCEEDED |
| `ios/consumer` — `xcodebuild build`, default settings | ✅ BUILD SUCCEEDED, 0 warnings in the two files |
| `ios/consumer` — `xcodebuild build`, strict concurrency scoped to the app target | ✅ BUILD SUCCEEDED, 0 warnings in the two files |
| `.github/workflows/ci.yml` | ✅ `yaml.safe_load` parses · ✅ `actionlint` clean |

## Files touched

```
.github/workflows/ci.yml                                              |  5 +-
ios/consumer/KosherEatsConsumer/Services/LocationManager.swift        | 64 ++++++++++++-------
ios/consumer/KosherEatsConsumer/Views/Orders/OrderDetailView.swift    |  6 +-
```

`project.pbxproj` was temporarily modified for the strict-concurrency survey and fully restored — it is **not** part of this branch's diff.

## Not done (deliberately)

- **Nothing pushed, nothing deployed**, `main` untouched — per the job brief.
- `docs/KE-TODO.md` lines 16–17 left as-is rather than struck through, to avoid a merge conflict with other worktrees editing that file tonight. Both items are now closed by this branch.
- The remaining 26 project-wide strict-concurrency warnings (9 other files) are reported, not fixed.
