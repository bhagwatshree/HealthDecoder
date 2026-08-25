// Generates backend/samconfig.toml from environment variables so `sam deploy` never needs
// secrets typed on a command line (fragile to escape, visible in shell history/process list).
// Used by both local deploys (env vars sourced from a developer's shell) and CI (sourced from
// GitHub Actions secrets) — see .github/workflows/backend-deploy.yml.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const paramMap = {
  DATABASE_URL: 'DatabaseUrl',
  GEMINI_API_KEY: 'GeminiApiKey',
  GEMINI_API_KEYS: 'GeminiApiKeys',
  SARVAM_API_KEY: 'SarvamApiKey',
  JWT_SECRET: 'JwtSecret',
  ENCRYPTION_KEY: 'EncryptionKey',
  FIREBASE_SERVICE_ACCOUNT_JSON: 'FirebaseServiceAccountJson',
  GOOGLE_CLIENT_ID: 'GoogleClientId',
  GOOGLE_CLIENT_SECRET: 'GoogleClientSecret',
};

// Monitoring parameters (see the Monitoring section of template.yaml). Optional, unlike the
// block above: an unset one is simply omitted from parameter_overrides, so CloudFormation keeps
// whatever the stack already has - which for a first deploy is the template's own default. That
// distinction matters: writing an empty string for an absent NEON_STORAGE_LIMIT_BYTES would
// override the sensible default with nothing and break the percentage the alarm watches.
const optionalParamMap = {
  MONITORING_MODE: 'MonitoringMode',
  ALERT_EMAIL: 'AlertEmail',
  NEON_API_KEY: 'NeonApiKey',
  NEON_PROJECT_ID: 'NeonProjectId',
  NEON_STORAGE_LIMIT_BYTES: 'NeonStorageLimitBytes',
  NEON_COMPUTE_HOURS_LIMIT: 'NeonComputeHoursLimit',
  GEMINI_HOURLY_SPEND_USD: 'GeminiHourlySpendUsd',
  GEMINI_DAILY_SPEND_USD: 'GeminiDailySpendUsd',
  SARVAM_DAILY_SPEND_USD: 'SarvamDailySpendUsd',
  INVOCATION_BURST_PER_5MIN: 'InvocationBurstPer5Min',
  APIGW_4XX_PER_5MIN: 'ApiGateway4xxPer5Min',
  OTP_VERIFICATIONS_PER_5MIN: 'OtpVerificationsPer5Min',
  METRICS_DETAIL: 'MetricsDetail',
};

const missing = Object.keys(paramMap).filter((k) => process.env[k] === undefined);
if (missing.length) {
  console.error(`Missing required env vars: ${missing.join(', ')}`);
  process.exit(1);
}

// Warn rather than fail on missing monitoring config: the API deploys and runs perfectly well
// without alerting, and blocking a deploy on a missing alert address would be a worse outcome
// than a quiet stack. But an unconfigured address means the alarms fire into a topic nobody reads,
// which is worth one line of output. None of it applies while monitoring is deliberately asleep.
const asleep = process.env.MONITORING_MODE === 'sleep';
if (asleep) {
  // Not a warning: this is a deliberate, supported state. Said out loud anyway, because a deploy
  // that silently ships with no alarms is worth noticing in the log if it wasn't intended.
  console.log('MONITORING_MODE=sleep - no alarms or monitor function will be created, and the API will not emit metrics.');
} else {
  if (!process.env.ALERT_EMAIL) {
    console.warn('WARNING: ALERT_EMAIL is not set - alarms will have no email subscriber.');
  }
  if (!process.env.NEON_API_KEY || !process.env.NEON_PROJECT_ID) {
    console.warn('WARNING: NEON_API_KEY / NEON_PROJECT_ID not set - Neon quota metrics will not be published.');
  }
}

// CloudFormation parameter_overrides is a single space-separated `Key="Value"` string.
// Escape backslashes and double quotes so multi-line JSON (FirebaseServiceAccountJson)
// survives intact inside the TOML string.
function esc(v) {
  return String(v ?? '').split('\\').join('\\\\').split('"').join('\\"');
}

const overrides = [
  ...Object.entries(paramMap)
    .map(([envKey, paramName]) => `${paramName}=\\"${esc(process.env[envKey])}\\"`),
  ...Object.entries(optionalParamMap)
    .filter(([envKey]) => process.env[envKey])
    .map(([envKey, paramName]) => `${paramName}=\\"${esc(process.env[envKey])}\\"`),
].join(' ');
const freeTierLimit = process.env.FREE_TIER_DAILY_LIMIT || '50';

const toml = [
  'version = 0.1',
  '[default.deploy.parameters]',
  'stack_name = "medical-scanner"',
  'region = "us-east-1"',
  'resolve_s3 = true',
  'capabilities = "CAPABILITY_IAM"',
  'confirm_changeset = false',
  `parameter_overrides = "${overrides} FreeTierDailyLimit=\\"${freeTierLimit}\\""`,
  '',
].join('\n');

const outPath = path.join(__dirname, '..', 'samconfig.toml');
fs.writeFileSync(outPath, toml, { mode: 0o600 });
// Counts what was actually written (required + whichever optional ones were set + FreeTierDailyLimit),
// rather than the size of paramMap - the optional block means those two numbers now differ.
const writtenCount = Object.keys(paramMap).length
  + Object.keys(optionalParamMap).filter((k) => process.env[k]).length
  + 1;
console.log(`wrote ${outPath} (${fs.statSync(outPath).size} bytes, ${writtenCount} parameters)`);
