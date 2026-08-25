// Scheduled Lambda that publishes the numbers nothing else can see.
//
// The request-path metrics (metrics.js, wired into db.js / usageTracker.js / keyPool.js) can only
// report on traffic that happens to arrive. Two things matter that no request knows about:
//
//   1. Neon quota - storage, compute hours, data transfer. Neon is not an AWS resource, so
//      CloudWatch has no metrics for it whatsoever; the only way to see it is to ask Neon's API.
//      Running out of storage or compute hours breaks every request at once, and the free plan's
//      ceiling is low enough to reach without noticing.
//   2. Sweep health. gemini_key_minute_usage and ip_rate_limit are pruned opportunistically, on
//      2% of the calls that touch them (see keyPool.js / rateLimiter.js). That is a fine design
//      while traffic flows and a silent one when it doesn't - if the sweep ever stops working, the
//      tables grow without bound and the first symptom is a Neon storage bill, months later.
//      Measuring rows OLDER than each sweep's own horizon tests the sweep directly, rather than
//      inferring it from table size.
//
// Runs on a schedule rather than in-request because both are point-in-time gauges: they want to be
// sampled at a steady cadence, not once per user action. Failures here are logged and swallowed
// per-section, so a Neon API outage still leaves the table metrics published (and vice versa).

import dotenv from 'dotenv';
import db from './db.js';
import { gauge, flush } from './metrics.js';

dotenv.config();

const NEON_API_BASE = process.env.NEON_API_BASE || 'https://console.neon.tech/api/v2';

// Neon's project object has carried different field names across API revisions, and a plan change
// can make a field disappear entirely. Rather than hard-coding one spelling and silently reporting
// nothing when it changes, each metric lists the names it will accept, newest first. A field that
// matches none of them is skipped - gauge() drops non-finite values, so the metric shows a gap and
// its alarm goes INSUFFICIENT_DATA, which is loud in a way that a fabricated 0 would not be.
const NEON_FIELDS = {
  storageBytes: ['synthetic_storage_size', 'data_storage_bytes', 'storage_size'],
  computeSeconds: ['compute_time_seconds', 'cpu_used_sec'],
  activeSeconds: ['active_time_seconds'],
  writtenBytes: ['written_data_bytes'],
  transferBytes: ['data_transfer_bytes'],
};

function pickNumber(obj, candidates) {
  for (const name of candidates) {
    const value = Number(obj?.[name]);
    if (Number.isFinite(value)) return value;
  }
  return null;
}

function pct(used, limit) {
  if (!Number.isFinite(used) || !Number.isFinite(limit) || limit <= 0) return null;
  return (used / limit) * 100;
}

/**
 * Reads current-billing-period consumption from Neon and publishes it, both as raw amounts and
 * as a percentage of the configured plan limits.
 *
 * The percentages are what the alarms actually watch. Raw bytes would mean rewriting every
 * threshold the day the plan changes; a percentage only needs the two limit env vars updated, and
 * "82% of storage" is legible at 3am in a way that "410000000 bytes" is not.
 */
async function publishNeonUsage() {
  const apiKey = process.env.NEON_API_KEY;
  const projectId = process.env.NEON_PROJECT_ID;
  if (!apiKey || !projectId) {
    console.log('Neon monitoring skipped: NEON_API_KEY / NEON_PROJECT_ID not set.');
    return;
  }

  // Explicit timeout: this Lambda's own ceiling is generous, and an unbounded fetch against a
  // hung API would burn all of it and take the table metrics down with it.
  const response = await fetch(`${NEON_API_BASE}/projects/${encodeURIComponent(projectId)}`, {
    headers: { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    // Body deliberately not logged: Neon error responses can echo request context, and this runs
    // with an API key that administers the whole project.
    throw new Error(`Neon API returned ${response.status}`);
  }
  const project = (await response.json())?.project || {};

  const storageBytes = pickNumber(project, NEON_FIELDS.storageBytes);
  const computeSeconds = pickNumber(project, NEON_FIELDS.computeSeconds);
  const computeHours = Number.isFinite(computeSeconds) ? computeSeconds / 3600 : null;

  const storageLimit = Number(process.env.NEON_STORAGE_LIMIT_BYTES || 0);
  const computeHoursLimit = Number(process.env.NEON_COMPUTE_HOURS_LIMIT || 0);

  gauge({
    NeonStorageBytes: { value: storageBytes, unit: 'Bytes' },
    NeonStoragePercentOfLimit: { value: pct(storageBytes, storageLimit), unit: 'Percent' },
    NeonComputeHours: { value: computeHours, unit: 'Count' },
    NeonComputePercentOfLimit: { value: pct(computeHours, computeHoursLimit), unit: 'Percent' },
    NeonActiveHours: { value: (pickNumber(project, NEON_FIELDS.activeSeconds) ?? NaN) / 3600, unit: 'Count' },
    NeonWrittenBytes: { value: pickNumber(project, NEON_FIELDS.writtenBytes), unit: 'Bytes' },
    NeonDataTransferBytes: { value: pickNumber(project, NEON_FIELDS.transferBytes), unit: 'Bytes' },
  }, { Service: 'neon' });

  // One line per run naming what was and wasn't found, so a renamed Neon field is diagnosable
  // from the log instead of only visible as a metric that quietly stopped reporting.
  console.log('Neon usage published:', JSON.stringify({
    storageBytes, computeHours,
    period: project.consumption_period_start ? `${project.consumption_period_start} -> ${project.consumption_period_end}` : 'unknown',
    unmatched: Object.entries(NEON_FIELDS)
      .filter(([, names]) => pickNumber(project, names) === null)
      .map(([metric]) => metric),
  }));
}

// Tables whose size is worth watching, and why. The `staleWhere` predicate encodes each table's
// own retention rule: rows matching it are ones its sweep should already have deleted, so a
// non-zero count is a direct, unambiguous report that the sweep has stopped - not a guess derived
// from the table getting large.
const WATCHED_TABLES = [
  { table: 'gemini_key_minute_usage', staleWhere: "minute_bucket < now() - interval '10 minutes'" },
  { table: 'ip_rate_limit', staleWhere: "hour_bucket < now() - interval '3 hours'" },
  // No sweep of its own; bounded instead by how long a cached answer stays useful. A pile of old
  // rows here is a cost problem (Neon storage) rather than a correctness one.
  { table: 'ai_response_cache', staleWhere: "created_at < now() - interval '24 hours'" },
  // Append-only billing ledger - it is SUPPOSED to grow, so there is nothing stale to count. Its
  // size is still tracked below, because it is the table most likely to be what fills the plan.
  { table: 'api_usage_events', staleWhere: null },
  { table: 'devices', staleWhere: null },
];

/**
 * Publishes per-table size and, where the table has a retention rule, how many rows have outlived
 * it. Sizes come from pg_total_relation_size, a catalog lookup rather than a scan, so this stays
 * cheap no matter how large the tables get.
 */
async function publishTableUsage() {
  const totals = await db.query('SELECT pg_database_size(current_database()) AS bytes');
  gauge({ DbTotalBytes: { value: Number(totals.rows[0]?.bytes), unit: 'Bytes' } }, { Service: 'neon' });

  for (const { table, staleWhere } of WATCHED_TABLES) {
    try {
      // to_regclass returns NULL instead of raising when the table is absent, so a database that
      // hasn't been migrated yet skips the table rather than failing the whole run.
      const exists = await db.query('SELECT to_regclass($1) AS oid', [table]);
      if (!exists.rows[0]?.oid) continue;

      // Table name is from the hard-coded list above, never from input, so interpolating it into
      // the identifier position is safe - it cannot be parameterised anyway.
      const size = await db.query(`SELECT pg_total_relation_size('${table}') AS bytes`);
      const metrics = { TableBytes: { value: Number(size.rows[0]?.bytes), unit: 'Bytes' } };

      if (staleWhere) {
        // Capped: if a sweep really has been dead for months, counting every row could take
        // longer than this Lambda has. The exact number stops mattering well below the cap -
        // any value near it means the same thing, and the alarm fires either way.
        const stale = await db.query(
          `SELECT count(*) AS n FROM (SELECT 1 FROM ${table} WHERE ${staleWhere} LIMIT 100000) capped`
        );
        metrics.TableStaleRows = { value: Number(stale.rows[0]?.n), unit: 'Count' };
      }

      gauge(metrics, { Service: 'neon', Table: table });
    } catch (err) {
      console.error(`Table metrics failed for ${table}:`, err?.message || err);
    }
  }
}

/**
 * Entry point for the scheduled rule in template.yaml.
 *
 * Each section is caught separately and the handler always resolves: a Neon API outage must not
 * stop the table metrics from being published, and neither failure should mark the invocation as
 * an error, because Lambda would then retry it and double-count every gauge in the period. The
 * alarms notice a genuinely dead monitor on their own - MonitorStalled watches for the metrics
 * simply ceasing to arrive, which covers far more failure modes than a thrown exception does.
 */
export const handler = async () => {
  const results = await Promise.allSettled([publishNeonUsage(), publishTableUsage()]);
  results.forEach((r, i) => {
    if (r.status === 'rejected') {
      console.error(`Monitor section ${i === 0 ? 'neon' : 'tables'} failed:`, r.reason?.message || r.reason);
    }
  });

  // Heartbeat, emitted unconditionally - including when both sections failed. It is what
  // MonitorStalled watches: its absence means the schedule itself stopped firing or the function
  // is failing before it reaches this line, which is a different and worse problem than one
  // section erroring.
  gauge({ MonitorRuns: { value: 1, unit: 'Count' } }, { Service: 'neon' });
  flush();
  return { ok: true, failed: results.filter((r) => r.status === 'rejected').length };
};
