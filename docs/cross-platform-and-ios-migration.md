# Cross-platform & iOS migration notes

Reference notes for taking Medical Assist (currently an Android Kotlin/Compose app with
on-device AI) to other platforms. Captured for later review.

## Where the app is today (why this matters)

- **Android app**: Kotlin/Compose. OCR + analysis run **on-device** (calls Gemini directly from
  the phone), data stored locally in encrypted Room/SQLite, on-device engines (`OcrEngine`,
  `DashboardEngine`, `MedicalEngine`, `UnitConverter`, `DateResolver`).
- **Backend**: thin AWS Lambda — auth (JWT, Firebase phone-OTP, Google OAuth), accounts, key
  assignment, translations, usage tracking. The heavy AI is NOT on the backend.
- Heavy use of native APIs: CameraX, ML Kit local OCR, Android Keystore/SQLCipher, AlarmManager +
  WorkManager (reminders, daily email scan), BiometricPrompt, SAF/FileProvider, deep links.

## Option A — Pure PWA (web app installable everywhere)

Gives "one codebase, installable on Android/desktop, responsive". **Two blockers make it a poor
fit for this app:**

1. **API keys can't live safely in the browser.** Today the app calls Gemini directly with the
   pooled/BYOK key; JS keys are trivially readable. A web app forces AI calls to the **backend**
   (server pays for + sees all AI; the on-device/offline/private model breaks) — unless strict BYOK
   where each user pastes their own key.
2. **Reminders barely work as a PWA, and on iOS effectively don't.** Scheduled local notifications,
   the full-screen lock-screen alarm, boot-persistence, background email scan — a browser can't
   reliably fire an 8 AM reminder when closed, and **iOS PWAs cannot do scheduled local
   notifications at all**. For a medicine-reminder app for elderly users, that's a dealbreaker on iOS.

## Option B — Compose Multiplatform / Kotlin Multiplatform (recommended)

The app is already Kotlin + Compose, so the heavy logic ports with little change. Share ~60–80% of
the code; write platform-specific bits only for camera/storage/notifications.

```
:shared        (commonMain)  → models, engines, all business logic  ← reused on iOS
   ├── androidMain            → Android impls (Keystore, CameraX, WorkManager…)
   └── iosMain                → iOS impls (Keychain, AVFoundation, UNNotifications…)
:androidApp    → thin Android shell (mostly exists already)
:iosApp        → new Xcode/SwiftUI (or Compose) shell
```

Android/iOS/Desktop are production-ready; **Web/Wasm** is the least mature leg — a native **Windows
desktop** app from the same code is more solid than "runs in a browser".

## Option C — Capacitor + web rewrite (React/TS)

One web codebase wrapped as native iOS/Android (plugins give local notifications, camera, secure
storage) and the same build runs as a PWA on desktop. Good iOS notification support, but requires a
full UI rewrite in web tech and porting the engines to TypeScript.

## Effort summary

Re-platform, not a feature — **weeks to a few months** for one developer.
- Compose Multiplatform reuses the most (engines port in days–weeks; the long tail is iOS-native
  services + signing).
- Capacitor/React adds a full UI rewrite on top.
- Pure PWA is cheapest but loses iOS reminders and takes on key-exposure/storage problems.
- The `mockup/` HTML/CSS/JS is a design reference only — not functional, doesn't shortcut the work.

---

# Channeling the change for iOS (Kotlin Multiplatform)

Don't rewrite — restructure so Kotlin *logic* becomes shared and every OS call sits behind an
`expect/actual` boundary.

## Step 1 — swap JVM-only libraries (the real work)

| Today (JVM/Android-only) | Multiplatform replacement | Touches |
|---|---|---|
| Gson (`@SerializedName`) | **kotlinx.serialization** (`@Serializable`) | `Models.kt` + every parse in `OcrEngine`, `MedicalEngine`, `ExportManager` |
| OkHttp / Retrofit | **Ktor client** | `GeminiClient`, `NetworkModule` |
| Room + SQLCipher | **SQLDelight** (or Room-KMP) + platform crypto | data layer |
| `java.util.Date` / `SimpleDateFormat` | **kotlinx-datetime** | `DateResolver`, date logic |
| `java.security` SHA-256 / `Base64` | expect/actual or KMP crypto lib | hashing/keys |
| **`Context` threaded everywhere** | inject a small `Platform`/`AppDirs`/`Database` abstraction | `LocalRepository`, `LocalStore` (big but mechanical) |

Portable already (move to `commonMain` almost unchanged): `DashboardEngine`, `UnitConverter`,
`DateResolver`, trend/comparison/ref-range logic, and the Gemini prompt strings.

## Step 2 — `expect/actual` platform services

| Service | Android (have) | iOS (write) |
|---|---|---|
| Secure key store | Android Keystore | **Keychain** |
| DB encryption | SQLCipher | SQLCipher iOS / encrypted store |
| Camera | CameraX | **AVFoundation** |
| Reminders/alarms | AlarmManager + WorkManager | **UNUserNotificationCenter** (+ `BGTaskScheduler` for email scan) |
| Local OCR (script detect) | ML Kit | **Apple Vision** (or drop; let Gemini handle it) |
| Biometric | BiometricPrompt | **LocalAuthentication** (Face ID/Touch ID) |
| Files / share | SAF + FileProvider | Document picker + Share sheet |
| PDF render on import | `PdfRenderer` | **PDFKit** |
| Auth (OTP, Google) | Firebase Android SDK | **Firebase iOS SDK** |

## Step 3 — UI strategy

- **Shared logic + native SwiftUI** (conservative, recommended to start): reuse the whole `shared`
  module, build iOS screens in SwiftUI. Lower risk; UI built twice.
- **Shared logic + shared Compose UI** (max reuse): Compose Multiplatform renders existing screens
  on iOS; newer, budget for polish.

## Step 4 — pieces needing fresh iOS thinking

- **Reminders**: iOS scheduled local notifications work, but the Android full-screen lock-screen
  alarm doesn't exist — use iOS **time-sensitive/critical notifications** and redesign that flow.
- **Background email scan**: `BGTaskScheduler` is far more restricted than WorkManager — likely move
  to a **server-side scheduled job** (better on both platforms).
- **Gemini key**: fine on-device in a signed app; don't ship it trivially extractable.

## Recommended order (keep Android shipping throughout)

1. **Extract a `shared` KMP module inside the current repo**; move pure engines + models (with
   kotlinx.serialization). Android keeps using it — nothing user-visible changes. *Biggest, most
   reusable step.*
2. Swap networking → Ktor, DB → SQLDelight, dates → kotlinx-datetime in `shared`; verify Android.
3. Add the **iOS target + `iosApp`**, wire `expect/actual` stubs, get it building/launching with a
   couple of read-only screens.
4. Implement iOS platform services one by one (Keychain → storage → camera → notifications → Firebase).
5. TestFlight → App Store.

**Bottom line:** Step 1 makes 60–70% of the logic iOS-ready and is mostly mechanical. The long tail
is the iOS-native services (notifications, camera, auth, signing). Do it incrementally *inside the
existing repo* (not a fork) so Android stays shippable.
