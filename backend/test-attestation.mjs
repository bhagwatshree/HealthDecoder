// Focused checks on the logic that is testable without real hardware: the default-off contract,
// nonce derivation, and that malformed input is rejected rather than accepted.
import assert from 'node:assert';
process.env.JWT_SECRET = 'test-secret';
const { verifyDeviceAttestation, attestationNonce, verifyAppAttest, isEnforced } =
  await import('./attestation.js');

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
await t('ios unattested is allowed while not enforced', async () => {
  const r = await verifyDeviceAttestation({ platform: 'ios', deviceId: 'abcdefgh' });
  assert.equal(r.ok, true);
});
await t('missing platform is allowed while not enforced (existing clients send none)', async () => {
  const r = await verifyDeviceAttestation({ deviceId: 'abcdefgh' });
  assert.equal(r.ok, true);
});
await t('neither platform is enforced by default', () => {
  assert.equal(isEnforced('android'), false);
  assert.equal(isEnforced('ios'), false);
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

console.log('App Attest rejects bad input rather than passing it:');
await t('missing attestation', () => {
  assert.equal(verifyAppAttest(null, null, 'd').ok, false);
});
await t('unconfigured team id fails closed', () => {
  const r = verifyAppAttest('AAAA', 'AAAA', 'd');
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'ios_team_id_not_configured');
});
await t('garbage CBOR fails closed, does not throw', () => {
  process.env.IOS_TEAM_ID = 'ABCDE12345';
  const r = verifyAppAttest(Buffer.from('not cbor at all').toString('base64'), 'AAAA', 'd');
  assert.equal(r.ok, false);
});

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
