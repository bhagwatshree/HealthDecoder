// Focused checks on the logic that is testable without real hardware: the default-off contract,
// nonce derivation/binding, fail-closed platform handling, and token freshness.
import assert from 'node:assert';
process.env.JWT_SECRET = 'test-secret';
process.env.ANDROID_PACKAGE_NAME = 'com.healthdecoder.app';
const { verifyDeviceAttestation, attestationNonce, verifyPlayIntegrity, evaluatePlayIntegrityVerdict, isEnforced } =
  await import('./attestation.js');

function genuinePayload(deviceId, overrides = {}) {
  return {
    requestDetails: {
      requestPackageName: 'com.healthdecoder.app',
      nonce: attestationNonce(deviceId),
      timestampMillis: String(Date.now()),
    },
    appIntegrity: { appRecognitionVerdict: 'PLAY_RECOGNIZED' },
    deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_DEVICE_INTEGRITY'] },
    ...overrides,
  };
}

let pass = 0, fail = 0;
const t = async (name, fn) => {
  try { await fn(); console.log(`  ok  ${name}`); pass++; }
  catch (e) { console.log(`  FAIL ${name}\n       ${e.message}`); fail++; }
};

console.log('Default-off contract:');
await t('android unattested is allowed while not enforced', async () => {
  const r = await verifyDeviceAttestation({ platform: 'android', deviceId: 'abcdefgh' });
  assert.equal(r.ok, true); assert.equal(r.reason, 'not_enforced');
});
await t('missing platform is allowed while not enforced (existing clients send none)', async () => {
  const r = await verifyDeviceAttestation({ deviceId: 'abcdefgh' });
  assert.equal(r.ok, true);
});
await t('android is not enforced by default', () => {
  assert.equal(isEnforced('android'), false);
});

console.log('Fail-closed platform handling once enforcement is on:');
await t('unknown/missing platform is rejected once android is enforced', async () => {
  process.env.ATTESTATION_ENFORCE_ANDROID = 'true';
  try {
    const missing = await verifyDeviceAttestation({ deviceId: 'abcdefgh' });
    assert.equal(missing.ok, false);
    assert.equal(missing.reason, 'unsupported_platform');
    const ios = await verifyDeviceAttestation({ platform: 'ios', deviceId: 'abcdefgh' });
    assert.equal(ios.ok, false);
    assert.equal(ios.reason, 'unsupported_platform');
  } finally {
    delete process.env.ATTESTATION_ENFORCE_ANDROID;
  }
});
await t('android with no attestation token is rejected once enforced', async () => {
  process.env.ATTESTATION_ENFORCE_ANDROID = 'true';
  try {
    const r = await verifyDeviceAttestation({ platform: 'android', deviceId: 'abcdefgh' });
    assert.equal(r.ok, false);
    assert.equal(r.reason, 'missing_token');
  } finally {
    delete process.env.ATTESTATION_ENFORCE_ANDROID;
  }
});

console.log('Nonce:');
await t('is deterministic per device', () => {
  assert.equal(attestationNonce('device-a'), attestationNonce('device-a'));
});
await t('differs between devices, so a token cannot be replayed for another', () => {
  assert.notEqual(attestationNonce('device-a'), attestationNonce('device-b'));
});
await t('is not the raw device id', () => {
  assert.notEqual(attestationNonce('device-a'), 'device-a');
});

console.log('Play Integrity rejects bad input rather than passing it:');
await t('missing token', async () => {
  const r = await verifyPlayIntegrity(null, 'device-a');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'missing_token');
});
await t('a genuine-shaped payload for the right device passes', () => {
  const r = evaluatePlayIntegrityVerdict(genuinePayload('device-a'), 'device-a');
  assert.equal(r.ok, true);
});
await t(
  'a token minted for a different device is rejected even though requestHash (unused) would ' +
  'have matched a naive check (regression: the classic Integrity API echoes the nonce back in ' +
  '`nonce`, not `requestHash` — reading the wrong field made this a silent no-op, so a token ' +
  'minted for device A could register device B)',
  () => {
    const payload = genuinePayload('device-a');
    payload.requestDetails.requestHash = attestationNonce('device-b'); // decoy the old bug would've read
    const r = evaluatePlayIntegrityVerdict(payload, 'device-b');
    assert.equal(r.ok, false);
    assert.equal(r.reason, 'nonce_mismatch');
  }
);
await t('a stale token is rejected even if every other field is genuine', () => {
  const payload = genuinePayload('device-a', {
    requestDetails: {
      requestPackageName: 'com.healthdecoder.app',
      nonce: attestationNonce('device-a'),
      timestampMillis: String(Date.now() - 10 * 60 * 1000), // 10 minutes old
    },
  });
  const r = evaluatePlayIntegrityVerdict(payload, 'device-a');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'token_expired');
});
await t('wrong package name is rejected', () => {
  const payload = genuinePayload('device-a');
  payload.requestDetails.requestPackageName = 'com.attacker.clone';
  const r = evaluatePlayIntegrityVerdict(payload, 'device-a');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'package_mismatch');
});
await t('a device that only fails basic integrity is rejected', () => {
  const payload = genuinePayload('device-a', { deviceIntegrity: { deviceRecognitionVerdict: [] } });
  const r = evaluatePlayIntegrityVerdict(payload, 'device-a');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'device_integrity_failed');
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
