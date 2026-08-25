import crypto from 'crypto';
import { GoogleAuth } from 'google-auth-library';

/**
 * Device attestation — proves a caller is a genuine, unmodified instance of OUR app on a real
 * Android device, before it is handed a token that can spend Gemini quota. Android only: this
 * codebase ships no iOS client, so there is nothing to verify on that platform.
 *
 * Why this exists: POST /api/device/register accepted any 8–128 character string and returned a
 * 365-day token. A device id was free and unlimited, so the per-caller daily cap
 * (FREE_TIER_DAILY_LIMIT) capped nothing in aggregate — a script could mint as many callers as it
 * wanted and drain the pooled keys real users depend on.
 *
 * ENFORCEMENT IS OFF BY DEFAULT, via ATTESTATION_ENFORCE_ANDROID. An unattested client is allowed
 * through unchanged, so merging this does not affect the app in production. Enabling it is a
 * deliberate later step, only after attesting clients have shipped and been adopted — flipping it
 * first would strand every installed app.
 *
 * Every flag below is read lazily (inside a function), not captured into a module-level const at
 * import time. attestation.js is imported by server.js BEFORE server.js calls dotenv.config(), so
 * a top-level `const X = process.env.X` would freeze at "unset" even when the .env value is
 * correct — silently leaving enforcement off regardless of configuration.
 */

function androidEnforced() {
  return process.env.ATTESTATION_ENFORCE_ANDROID === 'true';
}

function androidPackage() {
  return process.env.ANDROID_PACKAGE_NAME || 'com.healthdecoder.app';
}

// A Play Integrity token older than this is rejected even if every other check passes, so a
// captured token has a bounded window of use rather than working forever.
const MAX_TOKEN_AGE_MS = 5 * 60 * 1000;

export function attestationStatus() {
  return {
    android: { enforced: androidEnforced(), packageName: androidPackage() },
  };
}

export function isEnforced(platform) {
  return platform === 'android' && androidEnforced();
}

/**
 * The nonce a client must bind its attestation to: SHA256("attest:<deviceId>"), base64url.
 *
 * Deliberately NOT keyed with a server secret, because the client has to compute the identical
 * value and does not hold one. An earlier draft used HMAC(JWT_SECRET, ...) here, which no client
 * could ever match — the check would have rejected every genuine attestation the moment
 * enforcement was switched on.
 *
 * Secrecy is not what this nonce provides. Unforgeability comes from Google signing the
 * attestation itself; the nonce's job is to BIND that signature to one deviceId, so a token minted
 * for device A cannot be replayed to register device B. It does not defend against replaying the
 * same device's own token later — MAX_TOKEN_AGE_MS below is what bounds that.
 */
export function attestationNonce(deviceId) {
  return crypto
    .createHash('sha256')
    .update(`attest:${deviceId}`)
    .digest('base64url');
}

// ─── Android: Play Integrity ──────────────────────────────────────────────────

let cachedAuth = null;
function googleAuth() {
  if (!cachedAuth) {
    // Reuses the same service-account JSON Firebase phone auth already needs, so no new secret is
    // introduced. The account must additionally be granted the Play Integrity API role.
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    const credentials = raw ? JSON.parse(raw) : undefined;
    cachedAuth = new GoogleAuth({
      credentials,
      scopes: ['https://www.googleapis.com/auth/playintegrity'],
    });
  }
  return cachedAuth;
}

/**
 * Checks an already-decoded Play Integrity payload's verdicts. Pure and synchronous — separated
 * from verifyPlayIntegrity below purely so this logic is unit-testable without a live call to
 * Google, which is what let the nonce-field bug (see the comment inline) ship untested: the
 * previous test suite asserted things about attestationNonce() in isolation but never fed a
 * payload through the actual comparison.
 *
 * Returns { ok, reason, verdict }. `ok` is only true for an app Play recognises, running on a
 * device that meets basic integrity — deliberately BASIC and not STRONG, because STRONG excludes
 * a lot of legitimate hardware and this is abuse control, not DRM.
 */
export function evaluatePlayIntegrityVerdict(payload, deviceId) {
  if (!payload) return { ok: false, reason: 'no_payload' };

  const requestPackage = payload.requestDetails?.requestPackageName;
  if (requestPackage !== androidPackage()) {
    return { ok: false, reason: 'package_mismatch', verdict: payload };
  }

  // The Android client uses the CLASSIC Integrity API (IntegrityManagerFactory +
  // IntegrityTokenRequest.setNonce), which echoes the nonce back in requestDetails.nonce — NOT
  // requestDetails.requestHash, which only the separate Standard API populates. Reading the
  // wrong field here made this check a no-op: `got` was always undefined, so `got && ...`
  // short-circuited and every token passed regardless of which device it was minted for. Fail
  // CLOSED on a missing/mismatched nonce — this is the whole point of binding to deviceId.
  const expected = attestationNonce(deviceId);
  const got = payload.requestDetails?.nonce;
  if (got !== expected) {
    return { ok: false, reason: 'nonce_mismatch', verdict: payload };
  }

  // Bounds how long a captured token can be replayed. timestampMillis is when Play minted the
  // token, not when the client sent it, so this only needs to tolerate verification latency —
  // not client clock skew.
  const timestampMillis = Number(payload.requestDetails?.timestampMillis);
  if (!Number.isFinite(timestampMillis) || Date.now() - timestampMillis > MAX_TOKEN_AGE_MS) {
    return { ok: false, reason: 'token_expired', verdict: payload };
  }

  const appVerdict = payload.appIntegrity?.appRecognitionVerdict;
  if (appVerdict !== 'PLAY_RECOGNIZED') {
    return { ok: false, reason: `app_${appVerdict || 'unknown'}`, verdict: payload };
  }

  const deviceVerdicts = payload.deviceIntegrity?.deviceRecognitionVerdict || [];
  if (!deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY') &&
      !deviceVerdicts.includes('MEETS_BASIC_INTEGRITY')) {
    return { ok: false, reason: 'device_integrity_failed', verdict: payload };
  }

  return { ok: true, verdict: payload };
}

/** Decodes a Play Integrity token via Google, then hands the payload to evaluatePlayIntegrityVerdict. */
export async function verifyPlayIntegrity(integrityToken, deviceId) {
  if (!integrityToken) return { ok: false, reason: 'missing_token' };
  try {
    const client = await googleAuth().getClient();
    const url =
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(androidPackage())}` +
      ':decodeIntegrityToken';
    const res = await client.request({
      url,
      method: 'POST',
      data: { integrity_token: integrityToken },
    });
    return evaluatePlayIntegrityVerdict(res?.data?.tokenPayloadExternal, deviceId);
  } catch (error) {
    console.error('Play Integrity verification failed:', error?.message || error);
    return { ok: false, reason: 'verification_error' };
  }
}

/**
 * One entry point for the register endpoint. Returns { ok, reason }.
 *
 * When Android enforcement is off this always allows, so an unattested client keeps working
 * exactly as before — the flag is the only thing that changes behaviour. A `platform` other than
 * 'android' (missing, misspelled, or an iOS value from a client we don't ship) FAILS CLOSED once
 * enforcement is on: only an explicit, matching platform can take the "not enforced" path.
 */
export async function verifyDeviceAttestation({ platform, deviceId, attestation }) {
  if (platform !== 'android') {
    return androidEnforced()
      ? { ok: false, reason: 'unsupported_platform' }
      : { ok: true, reason: 'not_enforced' };
  }
  if (!androidEnforced()) return { ok: true, reason: 'not_enforced' };
  return verifyPlayIntegrity(attestation, deviceId);
}
