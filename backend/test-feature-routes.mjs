// Checks the default-off contract for routes serving features the app has switched off.
//
// These endpoints were reachable by anyone with a token even though nothing in the app pointed at
// them any more — which is not a security boundary, just an absence of callers. Two carried real
// flaws: the browser OAuth callback returns the session JWT and Google access token as URL query
// parameters, and GET /api/discovery/results looks a session up by id without checking it belongs
// to the caller. Closing the routes removes the exposure; these tests pin that they stay closed
// unless someone deliberately opens them.
import assert from 'node:assert';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('./server.js', import.meta.url), 'utf8');

// ── The guard itself ─────────────────────────────────────────────────────────
// Re-created here rather than imported: server.js starts a listener and needs a live database on
// import, so the contract is asserted against a copy of the same expression.
const featureRoute = (envVar) => (req, res, next) => {
  if (process.env[envVar] === 'true') return next();
  return res.status(404).json({ error: 'Not found.' });
};

function callRoute(envVar, envValue) {
  if (envValue === undefined) delete process.env[envVar];
  else process.env[envVar] = envValue;

  let status = null;
  let nexted = false;
  const res = { status(c) { status = c; return this; }, json() { return this; } };
  featureRoute(envVar)({ method: 'GET', originalUrl: '/x' }, res, () => { nexted = true; });
  return { status, nexted };
}

// Unset must mean closed — a feature must never come back because a deploy forgot a variable.
assert.deepStrictEqual(callRoute('TEST_FLAG', undefined), { status: 404, nexted: false });
assert.deepStrictEqual(callRoute('TEST_FLAG', 'false'), { status: 404, nexted: false });
assert.deepStrictEqual(callRoute('TEST_FLAG', ''), { status: 404, nexted: false });
// Only the exact string 'true' opens it — not '1', not 'yes', not 'TRUE'.
assert.deepStrictEqual(callRoute('TEST_FLAG', '1'), { status: 404, nexted: false });
assert.deepStrictEqual(callRoute('TEST_FLAG', 'TRUE'), { status: 404, nexted: false });
assert.deepStrictEqual(callRoute('TEST_FLAG', 'true'), { status: null, nexted: true });

// 404 rather than 403: a disabled route should not advertise that it exists.
{
  process.env.TEST_FLAG = 'false';
  let body = null;
  const res = { status() { return this; }, json(b) { body = b; return this; } };
  featureRoute('TEST_FLAG')({ method: 'GET', originalUrl: '/x' }, res, () => {});
  assert.deepStrictEqual(body, { error: 'Not found.' });
}

// ── The routes are actually wired to it ──────────────────────────────────────
const mustBeGuarded = [
  ["app.get('/api/auth/google',", 'webOAuthRoute', 'browser OAuth start (embeds the app JWT in state)'],
  ["app.get('/api/auth/google/callback',", 'webOAuthRoute', 'OAuth callback (returns tokens in a URL)'],
  ["app.post('/api/discovery/search',", 'discoveryRoute', 'discovery search (simulated providers)'],
  ["app.get('/api/discovery/results',", 'discoveryRoute', 'discovery results (no ownership check)'],
  ["app.post('/api/discovery/uhi/on_search',", 'discoveryRoute', 'discovery webhook (unauthenticated)'],
];

for (const [decl, guard, why] of mustBeGuarded) {
  const at = source.indexOf(decl);
  assert.ok(at !== -1, `route declaration moved or was renamed: ${decl}`);
  const line = source.slice(at, source.indexOf('\n', at));
  assert.ok(line.includes(guard), `${decl} is no longer guarded by ${guard} — ${why}`);
}

// ── Routes that must NOT be closed ───────────────────────────────────────────
// The native sign-in path is the one the app actually uses (Credential Manager -> ID token in the
// request body, no URL credentials). Guarding it would break login for every user.
{
  const at = source.indexOf("app.post('/api/auth/google-signin',");
  assert.ok(at !== -1, 'native google-signin route is missing');
  const line = source.slice(at, source.indexOf('\n', at));
  assert.ok(
    !line.includes('webOAuthRoute') && !line.includes('discoveryRoute'),
    'native google-signin must NOT be behind a feature route — it is the live login path'
  );
}
// EmailScanWorker and ScanScreen still call this one; closing it would break them.
{
  const at = source.indexOf("app.get('/api/auth/google/token',");
  assert.ok(at !== -1, 'google/token route is missing');
  const line = source.slice(at, source.indexOf('\n', at));
  assert.ok(!line.includes('webOAuthRoute'), 'google/token is still called by the app — must stay open');
}

console.log('feature-route gating: all checks passed');
