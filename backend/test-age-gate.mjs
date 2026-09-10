// The 18+ signup gate.
//
// Both the Terms and the Privacy Policy state "You must be 18 or older". Until this check
// existed that was aspirational — date of birth was collected and stored but never compared to
// anything. Under India's DPDP Act a child is anyone under 18 and processing their data needs
// verifiable parental consent, which the app cannot obtain.
//
// The boundary cases are what matter here: someone signing up on their 18th birthday must be
// allowed in, and someone one day short must not. A milliseconds-divided-by-365 age calculation
// gets exactly those wrong, which is why the implementation uses calendar arithmetic.
import assert from 'node:assert';

// Mirrors the block in server.js /api/auth/signup.
function ageCheck(dateOfBirth, nowIso) {
  if (!dateOfBirth || Number.isNaN(Date.parse(dateOfBirth))) return 'invalid';
  const dob = new Date(dateOfBirth);
  const now = new Date(nowIso);
  if (dob.getTime() > now.getTime()) return 'future';
  let age = now.getUTCFullYear() - dob.getUTCFullYear();
  const monthDelta = now.getUTCMonth() - dob.getUTCMonth();
  if (monthDelta < 0 || (monthDelta === 0 && now.getUTCDate() < dob.getUTCDate())) age--;
  return age < 18 ? 'under18' : 'ok';
}

const NOW = '2026-09-10T12:00:00Z';

// Exactly 18 today — must be allowed. Turning someone away on their birthday is a real bug.
assert.strictEqual(ageCheck('2008-09-10', NOW), 'ok', '18th birthday must be accepted');
// One day short — must be refused.
assert.strictEqual(ageCheck('2008-09-11', NOW), 'under18', 'a day under 18 must be refused');
// Comfortably over and comfortably under.
assert.strictEqual(ageCheck('1985-03-04', NOW), 'ok');
assert.strictEqual(ageCheck('2015-01-01', NOW), 'under18');
// Month boundary either side.
assert.strictEqual(ageCheck('2008-08-31', NOW), 'ok', 'birthday last month = 18');
assert.strictEqual(ageCheck('2008-10-01', NOW), 'under18', 'birthday next month = still 17');

// A leap-day birthday, checked in a non-leap year — the case an average-year divisor drifts on.
assert.strictEqual(ageCheck('2008-02-29', NOW), 'ok');

// Future and malformed dates are rejected rather than silently treated as age 0 (which would
// read as "under 18" and give a misleading error).
assert.strictEqual(ageCheck('2030-01-01', NOW), 'future');
assert.strictEqual(ageCheck('not-a-date', NOW), 'invalid');
assert.strictEqual(ageCheck('', NOW), 'invalid');

console.log('age gate: all checks passed');
