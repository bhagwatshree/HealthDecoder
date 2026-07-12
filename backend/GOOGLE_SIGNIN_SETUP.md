# Google Sign-In & Gmail Scanning Setup

This app has two independent Google integrations:

1. **"Continue with Google" login** — native, on-device account picker (Android Credential
   Manager), no browser. Only proves identity (email); works for any user immediately, no Google
   review needed.
2. **"Link Google Account" (Gmail inbox scanning)** — a browser-based OAuth consent flow that
   reads the linked Gmail inbox for medical report PDF attachments. Requires the `gmail.readonly`
   scope, which Google classifies as **restricted** — see [Publishing beyond test users](#publishing-beyond-test-users)
   below before you plan on this working for the general public.

Both share the same Google Cloud project and the same **Web application** OAuth client. You only
need to do this setup once per project.

---

## 1. Enable Google Sign-In in Firebase

1. Firebase Console → your project → **Authentication → Sign-in method → Add new provider → Google → Enable**.
2. This auto-creates a **Web application** OAuth client in the same GCP project — Firebase shows
   it right there as "Web SDK configuration → Web client ID". Copy it; you'll need it twice below.

(If you haven't set up a Firebase project yet — e.g. because you skipped phone-OTP login — do that
first; see the main [README.md](../README.md#phone-otp-login-optional).)

## 2. Register the Android app's signing certificate

Google's native sign-in verifies the calling app against a registered **Android**-type OAuth
client (package name + SHA-1 fingerprint) — separate from the Web client above, and it has no
secret.

1. Get your signing certificate's SHA-1:
   ```bash
   cd android-app
   ./gradlew signingReport
   ```
   Use the `debug` variant's SHA1 for local testing; repeat this for your release keystore before
   shipping a signed release build.
2. Cloud Console → **APIs & Services → Credentials → Create Credentials → OAuth client ID**.
3. Application type: **Android**. Package name: `com.example.medicalscanner`. Paste the SHA-1.

## 3. Configure the Android app

Add the **Web client ID** from step 1 to `android-app/local.properties` (gitignored, per-machine —
copy from `local.properties.example` if you haven't already):

```
GOOGLE_WEB_CLIENT_ID=<web-client-id>.apps.googleusercontent.com
```

Rebuild the app. No secret goes on the Android side — Credential Manager only needs the client ID.

## 4. Configure the backend (for Gmail linking only)

The native login flow doesn't need this section — it only needs `FIREBASE_SERVICE_ACCOUNT_JSON`
(see the phone-OTP section in the main README), which verifies the Firebase ID token the app
already produced on-device.

"Link Google Account" does need real backend OAuth credentials, since it exchanges an
authorization code server-side:

1. Cloud Console → **Credentials** → click into the **Web application** client from step 1.
2. Under **Client secrets**, click **Add secret** and copy the value *immediately* — Google shows
   it in full only once, right after creation; if you navigate away before copying it, you must
   generate a new one.
3. Under **Authorized redirect URIs**, add:
   ```
   https://<your-api-id>.execute-api.<region>.amazonaws.com/api/auth/google/callback
   ```
   (the same host as your deployed `ApiUrl` — see `backend/DEPLOY.md`).
4. Set locally in `backend/.env` (for `npm run dev`):
   ```
   GOOGLE_CLIENT_ID=<web-client-id>.apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=<the secret from step 2>
   ```
5. Add the same two values to your SAM deploy parameters (`backend/samconfig.toml`'s
   `parameter_overrides` — gitignored, holds real secrets) as `GoogleClientId` /
   `GoogleClientSecret`, then redeploy:
   ```bash
   cd backend
   npm run deploy
   ```

## 5. Enable the Gmail API and declare its scope

1. Cloud Console → **APIs & Services → Library** → search **Gmail API** → **Enable**.
2. Cloud Console → **Google Auth Platform → Data Access** → **Add or remove scopes** → add
   `.../auth/gmail.readonly` → Save. (Google rejects any scope your code requests that isn't
   explicitly declared here.)

## 6. Run the database migration

Adds the `google_email` / `google_refresh_token` columns the linking flow needs:

```bash
cd backend
node migrate.js
```

## 7. Add yourself (and any early testers) as a test user

While the OAuth consent screen's publishing status is **Testing** (the default for a new
project), only accounts explicitly listed here can complete the Gmail-linking consent flow —
anyone else sees "Access blocked... has not completed the Google verification process".

Cloud Console → **Google Auth Platform → Audience → Test users → Add users**.

---

## Publishing beyond test users

`gmail.readonly` (and the older, broader `https://mail.google.com/` IMAP scope) are both
classified by Google as **restricted scopes** — check under **Data Access**, they're listed
under "Your restricted scopes", not "sensitive". This means simply clicking **Publish app** does
**not** open Gmail-linking to the public. Google requires, before restricted-scope traffic from
non-test users is allowed:

- Standard verification (a privacy policy page, a demo video of the consent flow), **and**
- A third-party **CASA security assessment** — this has a real cost and can take weeks.

Practical options if you don't want to go through that:

- Keep Gmail-linking as a testers-only feature (cap: 100 test users, no cost) and rely on it for
  yourself/a known group.
- Point most users at the **"Other (IMAP)"** option in the app's email settings instead — a plain
  IMAP host/port/app-password, which needs zero Google review (a Gmail user can generate an
  "App Password" from their own Google Account security settings and use that here).
- Or complete the verification + CASA assessment if Gmail-linking at scale matters enough to justify it.

None of this affects "Continue with Google" login, which only needs the non-sensitive
`openid`/`email`/`profile` scopes and works for any user regardless of publishing status.
