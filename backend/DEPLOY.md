# Deploying the Medical Scanner Backend to AWS Lambda

This guide walks you through deploying the backend as a **serverless** AWS Lambda function
with a **free** Neon PostgreSQL database. Total cost: **$0/month**.

---

## Prerequisites

1. **AWS Account** — [Sign up free](https://aws.amazon.com/free/) (no credit card needed for Lambda free tier)
2. **AWS CLI** — [Install](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
3. **AWS SAM CLI** — [Install](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
4. **Node.js 20+** — Already installed if you've been running the backend

---

## Step 1: Set Up Neon PostgreSQL (Free)

1. Go to [neon.tech](https://neon.tech) and sign up (free, no credit card)
2. Create a new project:
   - **Name**: `medical-scanner`
   - **Region**: Pick the one closest to your users
   - **Postgres version**: 16 (default)
3. Copy the **connection string** (looks like):
   ```
   postgres://username:password@ep-cool-name-123456.us-east-2.aws.neon.tech/neondb?sslmode=require
   ```
4. Run the database init script. In Neon's **SQL Editor** (web console), paste the contents of `db_init.sql` and run it.

---

## Step 2: Configure AWS Credentials

```bash
aws configure
# Enter your Access Key ID, Secret Access Key, region (e.g. us-east-1), output format (json)
```

---

## Step 3: Deploy with SAM

First-time deployment (interactive — asks for parameter values):

```bash
cd backend
npm install
npm run deploy:guided
```

SAM will prompt you for:

| Parameter | What to Enter |
|---|---|
| **Stack Name** | `medical-scanner` |
| **Region** | `us-east-1` (or your preferred region) |
| **DatabaseUrl** | Your Neon connection string from Step 1 |
| **GeminiApiKey** | Your Gemini API key |
| **GeminiApiKeys** | Comma-separated pool of Gemini keys |
| **SarvamApiKey** | Your Sarvam AI key (or leave empty) |
| **JwtSecret** | A long random string (e.g. `openssl rand -hex 32`) |
| **EncryptionKey** | A different long random string |
| **FreeTierDailyLimit** | `50` (default) |

After deployment, SAM prints your **API URL**:
```
Outputs:
  ApiUrl: https://abc123xyz.execute-api.us-east-1.amazonaws.com
```

**Save this URL** — you'll need it for the Android app.

---

## Step 4: Update the Android App

In `android-app/app/src/main/java/com/example/medicalscanner/network/NetworkModule.kt`,
update the `DEFAULT_SERVER_URL`:

```kotlin
private const val DEFAULT_SERVER_URL = "https://abc123xyz.execute-api.us-east-1.amazonaws.com"
```

Rebuild and install the APK.

---

## Step 5: Verify

```bash
# Test the health endpoint
curl https://abc123xyz.execute-api.us-east-1.amazonaws.com/api/health

# Expected response:
# {"status":"healthy","timestamp":"2025-..."}
```

---

## Subsequent Deployments

After the initial setup, future deployments are one command:

```bash
npm run deploy
```

---

## Architecture

```
Android App → API Gateway → Lambda (Express) → Neon PostgreSQL
                                   ↓
                              Gemini API / Sarvam API

Images: stay on device → backed up to user's cloud drive (Drive/OneDrive/Dropbox)
```

### Free Tier Limits

| Service | Free Allowance | Your Likely Usage |
|---|---|---|
| **Lambda** | 1M requests + 400K GB-seconds/month | Well under |
| **API Gateway** | 1M requests/month (first 12 months) | Well under |
| **Neon PostgreSQL** | 0.5 GB storage, auto-suspend after 5 min | Fits perfectly |

---

## Troubleshooting

### Cold Starts
Lambda "cold starts" take ~1-2 seconds on the first request after inactivity.
Subsequent requests are fast (~50-200ms). This is normal for serverless.

### Logs
```bash
# View Lambda logs
sam logs -n MedicalScannerFunction --stack-name medical-scanner --tail
```

### Environment Variables
To update environment variables without redeploying code:
```bash
sam deploy --parameter-overrides GeminiApiKey=NEW_KEY_HERE
```
