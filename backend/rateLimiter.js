import crypto from 'crypto';
import db from './db.js';
import { count, detail } from './metrics.js';

// Postgres-backed per-IP rate limiting for sensitive, unauthenticated endpoints
// (/api/device/register, /api/auth/*). A Lambda invocation has no memory of the last one, so an
// in-process counter would reset on every cold start and cap nothing — this needs to live
// somewhere shared, same reasoning as keyPool.js's gemini_key_minute_usage. The
// INSERT ... ON CONFLICT ... WHERE pattern below is the same atomic reserve-a-slot trick used
// there, so concurrent Lambda invocations can't both "win" past the cap.
//
// req.ip is trustworthy here without any 'trust proxy' configuration: serverless-http (lambda.js)
// derives it from API Gateway/Function URL's requestContext.http.sourceIp, which AWS populates
// from the real TCP connection — not from a client-supplied header a caller could spoof.
//
// IPs are hashed before storage. This table exists purely to bound request velocity; a health
// app has no reason to accumulate a durable, reversible log of who called from where.

function ipHash(ip) {
  return crypto.createHash('sha256').update(String(ip)).digest('hex').slice(0, 32);
}

function currentHourBucket() {
  return new Date(Math.floor(Date.now() / 3600000) * 3600000);
}

// 2% opportunistic sweep, mirroring keyPool.js's sweepOldMinuteBuckets — this table is a sliding
// rate-limit counter, not a durable log, so a bucket no window can reference any more is safe to
// drop without a cron job.
function sweepOldHourBuckets() {
  if (Math.random() >= 0.02) return;
  db.query(`DELETE FROM ip_rate_limit WHERE hour_bucket < now() - interval '3 hours'`)
    .catch((err) => console.error('Failed to sweep ip_rate_limit:', err.message));
}

/** Atomically reserves one call against `scope` for `ip` this hour. Resolves false once `ip` is
 *  already at `limit` calls to `scope` this hour, true (and counted) otherwise. */
async function reserve(scope, ip, limit) {
  const hourBucket = currentHourBucket();
  const result = await db.query(
    `INSERT INTO ip_rate_limit (scope, ip_hash, hour_bucket, request_count)
     VALUES ($1, $2, $3, 1)
     ON CONFLICT (scope, ip_hash, hour_bucket) DO UPDATE
       SET request_count = ip_rate_limit.request_count + 1
       WHERE ip_rate_limit.request_count < $4
     RETURNING request_count`,
    [scope, ipHash(ip), hourBucket, limit]
  );
  sweepOldHourBuckets();
  return result.rows.length > 0;
}

/**
 * Express middleware factory: caps how many requests one source IP can make to `scope` per
 * calendar-hour bucket. `getLimit` is called per-request (not read once at import time) so the
 * env var behind it can be picked up after dotenv.config() runs, the same reason attestation.js
 * reads its flags lazily rather than freezing them into module-level consts.
 *
 * Fails OPEN on a database error — a transient Neon blip should not take the whole API down.
 * The per-device/per-user daily quota and per-key RPM cap elsewhere in keyPool.js remain the real
 * backstop on cost; this is an earlier, cheaper line of defense against scripted registration and
 * credential-stuffing bursts, not the only one.
 */
export function ipRateLimit(scope, getLimit) {
  return async (req, res, next) => {
    const limit = getLimit();
    if (!limit || limit <= 0) return next();
    try {
      const allowed = await reserve(scope, req.ip, limit);
      if (!allowed) {
        // An undimensioned total for the alarm to watch (CloudWatch alarms need a fixed dimension
        // set, and cannot aggregate across dimension values), plus a per-scope breakdown for
        // reading the graph afterwards. The two scopes mean very different things: device-register
        // blocks are someone minting installs, auth blocks are credential stuffing. Never
        // dimension by IP - see metrics.js on cardinality, and note this module deliberately
        // doesn't retain reversible IPs in the first place.
        count('IpRateLimited');
        detail('IpRateLimitedByScope', 1, { Scope: scope });
        return res.status(429).json({ error: 'Too many requests from this network. Please try again later.' });
      }
      next();
    } catch (error) {
      // Failing open is the right call (see above), but it means the cap is silently not being
      // enforced - which is exactly the state that must not go unnoticed, since it converts a
      // database blip into an open door on the endpoints this protects. The log line below
      // carries the scope, so this counter doesn't need to.
      count('IpRateLimitFailedOpen');
      console.error(`Rate limit check failed for ${scope}:`, error?.message || error);
      next();
    }
  };
}
