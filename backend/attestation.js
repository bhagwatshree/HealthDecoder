import crypto from 'crypto';
import { GoogleAuth } from 'google-auth-library';
import { decode as cborDecode } from 'cbor-x';

/**
 * Device attestation — proves a caller is a genuine, unmodified instance of OUR app on a real
 * device, before it is handed a token that can spend Gemini quota.
 *
 * Why this exists: POST /api/device/register accepted any 8–128 character string and returned a
 * 365-day token. A device id was free and unlimited, so the per-caller daily cap
 * (FREE_TIER_DAILY_LIMIT) capped nothing in aggregate — a script could mint as many callers as it
 * wanted and drain the pooled keys real users depend on.
 *
 * ENFORCEMENT IS OFF BY DEFAULT and per-platform. Turning it on rejects every client that does not
 * yet send an attestation, which includes every already-installed app. Roll it out by shipping
 * clients that send one, waiting for adoption, then flipping the flag — never the other way round.
 *
 * NEITHER PATH CAN BE TESTED WITHOUT REAL HARDWARE. Apple's DCAppAttestService returns
 * isSupported == false on the Simulator, and Play Integrity needs Play services plus an app
 * distributed through Play. The unit tests here cover parsing, verdict logic and the failure
 * modes; the happy path must be confirmed on a physical device before enforcement is enabled.
 */

const ANDROID_ENFORCED = process.env.ATTESTATION_ENFORCE_ANDROID === 'true';
const IOS_ENFORCED = process.env.ATTESTATION_ENFORCE_IOS === 'true';

const ANDROID_PACKAGE = process.env.ANDROID_PACKAGE_NAME || 'com.healthdecoder.app';
const IOS_TEAM_ID = process.env.IOS_TEAM_ID || '';
const IOS_BUNDLE_ID = process.env.IOS_BUNDLE_ID || 'com.healthdecoder.app';

/** Apple's App Attest root, needed to anchor the certificate chain in an attestation object. */
const APPLE_APP_ATTEST_ROOT_CA = `-----BEGIN CERTIFICATE-----
MIICITCCAaegAwIBAgIQC/O+DvHN0uD7jG5yH2IXmDAKBggqhkjOPQQDAzBSMSYw
JAYDVQQDDB1BcHBsZSBBcHAgQXR0ZXN0YXRpb24gUm9vdCBDQTETMBEGA1UECgwK
QXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAeFw0yMDAzMTgxODMyNTNa
Fw00NTAzMTUwMDAwMDBaMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlv
biBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9y
bmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAERTHhmLW07ATaFQIEVwTtT4dyctdh
NbJhFs/Ii2FdCgAHGbpphY3+d8qjuDngIN3WVhQUBHAoMeQ/cLiP1sOUtgjqK9au
Yen1mMEvRq9Sk3Jm5X8U62H+xTD3FE9TgS41o0IwQDAPBgNVHRMBAf8EBTADAQH/
MB0GA1UdDgQWBBSskRBTM72+aEH/pwyp5frq5eWKoTAOBgNVHQ8BAf8EBAMCAQYw
CgYIKoZIzj0EAwMDaAAwZQIwQgFGnByvsiVbpTKwSga0kP0e8EeDS4+sQmTvb7vn
53O5+FRXgeLhpJ06ysC5PrOyAjEAp5U4xDgEgllF7En3VcE3iexZZtKeYnpqtijV
oyFraWVIyd/dganmrduC1bmTBGwD
-----END CERTIFICATE-----`;

export function attestationStatus() {
  return {
    android: { enforced: ANDROID_ENFORCED, packageName: ANDROID_PACKAGE },
    ios: { enforced: IOS_ENFORCED, bundleId: IOS_BUNDLE_ID, teamConfigured: Boolean(IOS_TEAM_ID) },
  };
}

export function isEnforced(platform) {
  if (platform === 'android') return ANDROID_ENFORCED;
  if (platform === 'ios') return IOS_ENFORCED;
  return false;
}

/**
 * The nonce a client must bind its attestation to: SHA256("attest:<deviceId>"), base64url.
 *
 * Deliberately NOT keyed with a server secret, because the client has to compute the identical
 * value and does not hold one. An earlier draft used HMAC(JWT_SECRET, ...) here, which no client
 * could ever match — the check would have rejected every genuine attestation the moment
 * enforcement was switched on.
 *
 * Secrecy is not what this nonce provides. Unforgeability comes from Google/Apple signing the
 * attestation itself; the nonce's job is to BIND that signature to one deviceId, so a token minted
 * for device A cannot be replayed to register device B. It does not defend against replaying the
 * same device's own token later — that needs a server-issued challenge and a round trip, worth
 * adding if enforcement ever becomes the only control.
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
 * Decodes a Play Integrity token via Google and checks the verdicts.
 *
 * Returns { ok, reason, verdict }. `ok` is only true for an app Play recognises, running on a
 * device that meets basic integrity — deliberately BASIC and not STRONG, because STRONG excludes
 * a lot of legitimate hardware and this is abuse control, not DRM.
 */
export async function verifyPlayIntegrity(integrityToken, deviceId) {
  if (!integrityToken) return { ok: false, reason: 'missing_token' };
  try {
    const client = await googleAuth().getClient();
    const url =
      `https://playintegrity.googleapis.com/v1/${encodeURIComponent(ANDROID_PACKAGE)}` +
      ':decodeIntegrityToken';
    const res = await client.request({
      url,
      method: 'POST',
      data: { integrity_token: integrityToken },
    });

    const payload = res?.data?.tokenPayloadExternal;
    if (!payload) return { ok: false, reason: 'no_payload' };

    const requestPackage = payload.requestDetails?.requestPackageName;
    if (requestPackage && requestPackage !== ANDROID_PACKAGE) {
      return { ok: false, reason: 'package_mismatch', verdict: payload };
    }

    // The client puts the nonce in requestHash; binding it to deviceId stops a token minted for
    // one device being replayed to register another.
    const expected = attestationNonce(deviceId);
    const got = payload.requestDetails?.requestHash;
    if (got && got !== expected) {
      return { ok: false, reason: 'nonce_mismatch', verdict: payload };
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
  } catch (error) {
    console.error('Play Integrity verification failed:', error?.message || error);
    return { ok: false, reason: 'verification_error' };
  }
}

// ─── iOS: App Attest ──────────────────────────────────────────────────────────

/**
 * Verifies an Apple App Attest attestation object, following Apple's published steps.
 *
 * `attestationBase64` is the CBOR object DCAppAttestService.attestKey produced; `keyIdBase64` is
 * the key id it was generated for.
 */
export function verifyAppAttest(attestationBase64, keyIdBase64, deviceId) {
  if (!attestationBase64 || !keyIdBase64) return { ok: false, reason: 'missing_attestation' };
  if (!IOS_TEAM_ID) return { ok: false, reason: 'ios_team_id_not_configured' };

  try {
    const attestation = cborDecode(Buffer.from(attestationBase64, 'base64'));
    if (attestation?.fmt !== 'apple-appattest') return { ok: false, reason: 'bad_format' };

    const x5c = attestation.attStmt?.x5c;
    const authData = attestation.authData;
    if (!Array.isArray(x5c) || x5c.length === 0 || !authData) {
      return { ok: false, reason: 'malformed_attestation' };
    }

    // 1. Chain the leaf up to Apple's App Attest root.
    const leaf = new crypto.X509Certificate(Buffer.from(x5c[0]));
    const root = new crypto.X509Certificate(APPLE_APP_ATTEST_ROOT_CA);
    const intermediates = x5c.slice(1).map((der) => new crypto.X509Certificate(Buffer.from(der)));
    const anchor = intermediates.length > 0 ? intermediates[0] : root;
    if (!leaf.checkIssued(anchor)) return { ok: false, reason: 'chain_broken' };
    if (intermediates.length > 0 && !intermediates[0].verify(root.publicKey)) {
      return { ok: false, reason: 'intermediate_not_apple' };
    }

    // 2. The nonce Apple embedded must equal SHA256(authData || SHA256(clientData)).
    const clientDataHash = crypto.createHash('sha256')
      .update(attestationNonce(deviceId))
      .digest();
    const expectedNonce = crypto.createHash('sha256')
      .update(Buffer.concat([Buffer.from(authData), clientDataHash]))
      .digest();
    const embeddedNonce = appleNonceExtension(leaf);
    if (!embeddedNonce) return { ok: false, reason: 'nonce_extension_missing' };
    if (!crypto.timingSafeEqual(embeddedNonce, expectedNonce)) {
      return { ok: false, reason: 'nonce_mismatch' };
    }

    // 3. The key id must be SHA256 of the attested public key.
    const publicKeyDer = leaf.publicKey.export({ type: 'spki', format: 'der' });
    // SPKI carries a header before the raw point; Apple hashes the uncompressed point itself.
    const rawPoint = publicKeyDer.subarray(publicKeyDer.length - 65);
    const computedKeyId = crypto.createHash('sha256').update(rawPoint).digest();
    if (!computedKeyId.equals(Buffer.from(keyIdBase64, 'base64'))) {
      return { ok: false, reason: 'key_id_mismatch' };
    }

    // 4. authData's first 32 bytes are SHA256("<teamId>.<bundleId>").
    const appId = `${IOS_TEAM_ID}.${IOS_BUNDLE_ID}`;
    const expectedRpId = crypto.createHash('sha256').update(appId).digest();
    const rpIdHash = Buffer.from(authData).subarray(0, 32);
    if (!rpIdHash.equals(expectedRpId)) return { ok: false, reason: 'app_id_mismatch' };

    // 5. A fresh attestation always has counter 0.
    const counter = Buffer.from(authData).readUInt32BE(33);
    if (counter !== 0) return { ok: false, reason: 'counter_not_zero' };

    // 6. aaguid distinguishes production from the development environment.
    const aaguid = Buffer.from(authData).subarray(37, 53).toString('utf8').replace(/\0+$/, '');
    if (aaguid !== 'appattest' && aaguid !== 'appattestdevelop') {
      return { ok: false, reason: 'unexpected_aaguid' };
    }
    if (aaguid === 'appattestdevelop' && process.env.NODE_ENV === 'production') {
      return { ok: false, reason: 'development_attestation_in_production' };
    }

    return { ok: true, aaguid };
  } catch (error) {
    console.error('App Attest verification failed:', error?.message || error);
    return { ok: false, reason: 'verification_error' };
  }
}

/** Apple stores the attestation nonce in a private extension, OID 1.2.840.113635.100.8.2. */
function appleNonceExtension(cert) {
  const der = cert.raw;
  // The extension wraps the 32-byte nonce; locate the OID then take the trailing digest. Parsing
  // the full DER tree would need an ASN.1 library for one fixed-shape field.
  const oid = Buffer.from([0x06, 0x0a, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0x63, 0x64, 0x08, 0x02]);
  const at = der.indexOf(oid);
  if (at < 0) return null;
  const after = der.subarray(at + oid.length);
  // The value is the last 32 bytes of the nested OCTET STRING / SEQUENCE that follows.
  const marker = after.indexOf(Buffer.from([0x04, 0x20]));
  if (marker < 0) return null;
  const nonce = after.subarray(marker + 2, marker + 34);
  return nonce.length === 32 ? nonce : null;
}

/**
 * One entry point for the register endpoint. Returns { ok, reason }.
 *
 * When the platform is not enforced this always allows, so an unattested client keeps working
 * exactly as before — the flag is the only thing that changes behaviour.
 */
export async function verifyDeviceAttestation({ platform, deviceId, attestation, keyId }) {
  if (!isEnforced(platform)) {
    return { ok: true, reason: 'not_enforced' };
  }
  if (platform === 'android') return verifyPlayIntegrity(attestation, deviceId);
  if (platform === 'ios') return verifyAppAttest(attestation, keyId, deviceId);
  return { ok: false, reason: 'unknown_platform' };
}
