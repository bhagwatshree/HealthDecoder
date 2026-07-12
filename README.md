# Medical Scanner

A medical report/prescription scanner: an Android app that photographs reports and prescriptions,
a serverless backend that extracts structured data via Gemini vision (with optional Sarvam AI for
Indic OCR/translation), and account-based access with phone-OTP or email/password login.

## Repo structure

```
backend/        Express API, deployed as a single AWS Lambda (see backend/DEPLOY.md)
android-app/    Kotlin/Compose Android app
```

The local-only cost dashboard (AWS/Gemini/Sarvam/Firebase spend) is a separate project at
`D:\Medical_Admin_Dashboard`, not part of this repo.

## Prerequisites

- Node.js 20+
- AWS CLI + AWS SAM CLI, with credentials configured (`aws configure`) — for deploying the backend
- A [Neon](https://neon.tech) Postgres database (free tier)
- Android Studio (or just the JDK 17 + `gradlew`) for the Android app
- A Firebase project with Phone Authentication enabled, if you want phone-OTP login/signup

## Backend

### Local development

```bash
cd backend
npm install
cp .env.example .env   # fill in DATABASE_URL (or PG* vars), API keys, JWT_SECRET, etc.
node migrate.js        # creates/updates tables on your Postgres instance
npm run dev            # starts the Express server on http://localhost:3000
```

Required `.env` values are documented inline in `backend/.env.example`. At minimum you need:
- `DATABASE_URL` (or the `PG*` variables) — Postgres connection
- `GEMINI_API_KEY` / `GEMINI_API_KEYS` — from [Google AI Studio](https://aistudio.google.com/apikey)
- `SARVAM_API_KEY` — from the [Sarvam AI dashboard](https://dashboard.sarvam.ai) (optional; Sarvam features degrade gracefully without it)
- `JWT_SECRET`, `ENCRYPTION_KEY` — any long random strings
- `FIREBASE_SERVICE_ACCOUNT_JSON` — only if you want phone-OTP auth (see below)

### Deploying to AWS Lambda

The backend runs as a single Lambda behind API Gateway via AWS SAM. Full first-time setup
(Neon project, AWS credentials, guided deploy) is in **[backend/DEPLOY.md](backend/DEPLOY.md)**.

Once configured, redeploying after code changes is:

```bash
cd backend
npm run deploy      # sam build && sam deploy
```

After deploying, run `node migrate.js` (pointed at the same `DATABASE_URL`) whenever the schema
changes, and copy the printed `ApiUrl` into the Android app's `NetworkModule.kt` if it changed
(it stays stable across ordinary redeploys — only changes if the stack is torn down and recreated).

### Phone-OTP login (optional)

Phone-based signup/login verifies a Firebase Phone Auth ID token server-side. To enable it:

1. Firebase Console → Project Settings → Service Accounts → Generate new private key.
2. Paste the downloaded JSON as one line into `FIREBASE_SERVICE_ACCOUNT_JSON` in `.env` (local)
   and into the `FirebaseServiceAccountJson` deploy parameter (see `backend/samconfig.toml`,
   which is gitignored since it holds real secrets — set it via `sam deploy --guided` once).
3. In the Firebase Console, check **Authentication → Settings → SMS region policy** — by default
   new projects allow no regions, which silently blocks all OTP SMS. Allow the countries you expect
   users in.

Leave it blank to skip phone auth entirely — email/password signup and login work regardless.

## Android app

1. Place a `google-services.json` (from your Firebase project) at `android-app/app/google-services.json`
   — required for phone-OTP login to compile/work; the app degrades to email/password-only if it's
   a placeholder. This file is gitignored (per-developer, not committed).
2. Set the deployed API URL in
   `android-app/app/src/main/java/com/example/medicalscanner/network/NetworkModule.kt`
   (`DEFAULT_SERVER_URL`).
3. Build:

   ```bash
   cd android-app
   ./gradlew assembleDebug     # debug APK: app/build/outputs/apk/debug/app-debug.apk
   ```

   Open the folder in Android Studio instead if you'd rather run/debug on a device directly.

## Cost tracking

Every Gemini/Sarvam/Firebase call the backend makes is logged (provider, operation, tokens,
estimated cost) to the `api_usage_events` table — see `backend/usageTracker.js` and
`backend/pricing.js`. Point the separate dashboard project at the same `DATABASE_URL` to see
spend broken down by provider/operation/model, plus a CloudWatch-based AWS cost estimate.
