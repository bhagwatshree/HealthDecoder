// CloudWatch metrics, emitted as Embedded Metric Format (EMF).
//
// Almost nothing worth alarming on in this backend is visible to CloudWatch by default. The
// database is Neon, not RDS, so AWS publishes no metrics for it at all; and every Gemini key,
// cost and quota number lives in Postgres tables (api_usage_events, gemini_key_minute_usage)
// that CloudWatch has never heard of. This module is how those numbers get out: EMF is a JSON
// envelope written to stdout that CloudWatch Logs recognises and extracts as real metrics, which
// the alarms in template.yaml can then watch.
//
// EMF rather than a PutMetricData API call, deliberately. PutMetricData is a network round trip
// on the request path, and /api/ai/generate already spends 35-60s waiting on Gemini inside a
// 120s ceiling - it has no budget to spare and no reason to fail a scan because a metrics call
// timed out. A console.log costs nothing measurable, needs no IAM permission, and cannot throw
// into the response. The tradeoff is that metrics land a few seconds late (log ingestion), which
// no alarm here cares about - the fastest evaluates over 5 minutes.
//
// COST: each distinct metric-name + dimension-value combination is a separate billable custom
// metric (~$0.30/month beyond the 10 free). Dimension only on bounded, low-cardinality things -
// Operation and KeyIndex are safe; a user id, device id or IP would be ruinous, both in money
// and in leaking identifiers into a metric name.

const NAMESPACE = process.env.METRICS_NAMESPACE || 'HealthDecoder';
const SERVICE = process.env.METRICS_SERVICE || 'backend';

// Off outside Lambda, so local dev and one-shot scripts (migrate.js, check-recent-events.js)
// don't spray EMF JSON across the terminal. METRICS_ENABLED=1 forces it on to test the emitter
// itself; METRICS_ENABLED=0 forces it off in Lambda if it ever needs silencing fast without a
// code change.
const ENABLED = process.env.METRICS_ENABLED === '1'
  || (!!process.env.AWS_LAMBDA_FUNCTION_NAME && process.env.METRICS_ENABLED !== '0');

// EMF accepts at most 100 raw values for one metric in one line. Past that we stop collecting
// samples and keep only the count, so a pathological invocation degrades the resolution of the
// latency distribution rather than dropping the line (or the request) on the floor.
const MAX_SAMPLES = 100;

// Buffered between flushes. Emitting a line per event would be correct but wildly chatty - a
// single request runs several DB queries, and CloudWatch Logs bills per GB ingested. One
// rolled-up line per invocation carries the same numbers for a fraction of the volume.
const buffer = new Map();

function bufferKey(name, dims) {
  return `${name} ${JSON.stringify(dims)}`;
}

/** Adds to a running total that is flushed as a Sum, which is what every total-based alarm
 *  watches. `unit` is CloudWatch's own vocabulary (Count, Bytes, Milliseconds, None...) and is
 *  only a display/axis label - it does not convert anything. */
export function sum(name, value, unit = 'None', dims = {}) {
  if (!ENABLED || !Number.isFinite(value)) return;
  const key = bufferKey(name, dims);
  const entry = buffer.get(key);
  if (entry) entry.sum += value;
  else buffer.set(key, { name, dims, unit, sum: value, samples: null });
}

/** Increments an event counter. Thin wrapper over `sum` for the common Count case. */
export function count(name, n = 1, dims = {}) {
  sum(name, n, 'Count', dims);
}

// Breakdown metrics (per Gemini key, per attestation-failure reason, per rate-limit scope) are
// where the bill actually goes: one dimension with six values is six billable metrics, not one.
// They earn that when something is wrong and you need to know WHICH key or WHICH reason, and earn
// nothing the rest of the time - so they are separable. Setting METRICS_DETAIL=0 drops every
// breakdown while leaving the undimensioned totals (and therefore every alarm) untouched.
const DETAIL = process.env.METRICS_DETAIL !== '0';

/** A dimensioned breakdown counter. Suppressed by METRICS_DETAIL=0; alarms never watch these,
 *  so turning them off costs diagnosis detail and never coverage. */
export function detail(name, n = 1, dims = {}) {
  if (!DETAIL) return;
  sum(name, n, 'Count', dims);
}

/** Records one observation of a distribution (latency, size). Flushed as raw values so an alarm
 *  can pick Average, Maximum or a percentile without that being decided here. */
export function observe(name, value, unit = 'Milliseconds', dims = {}) {
  if (!ENABLED || !Number.isFinite(value)) return;
  const key = bufferKey(name, dims);
  const entry = buffer.get(key);
  if (entry) {
    if (entry.samples.length < MAX_SAMPLES) entry.samples.push(value);
  } else {
    buffer.set(key, { name, dims, unit, sum: null, samples: [value] });
  }
}

function emfLine(dims, metrics, values) {
  // One dimension set. Service is always present so a metric stays addressable even when the
  // caller passes no dimensions of its own - but deduped, because a caller overriding Service
  // (monitor.js publishes under Service=neon) would otherwise produce Dimensions: [[Service,
  // Service]]. A repeated dimension name is invalid EMF and CloudWatch drops the whole metric
  // rather than complaining, so this silently publishes nothing if it is got wrong.
  const names = [...new Set(['Service', ...Object.keys(dims)])];
  return JSON.stringify({
    _aws: {
      Timestamp: Date.now(),
      CloudWatchMetrics: [{
        Namespace: NAMESPACE,
        Dimensions: [names],
        Metrics: metrics,
      }],
    },
    Service: SERVICE,
    ...dims,
    ...values,
  });
}

/**
 * Writes everything buffered so far as EMF and clears the buffer. Called once per Lambda
 * invocation from lambda.js, after the response has been handed back - so the cost of logging
 * never sits between the user and their answer.
 *
 * Never throws: a metrics failure must not become a request failure. That is the whole reason
 * this is a log line and not an API call, and the try/catch keeps it true even if something
 * unserialisable ever reaches a dimension value.
 */
export function flush() {
  if (buffer.size === 0) return;
  const entries = [...buffer.values()];
  buffer.clear();
  try {
    // Group by dimension set so one line carries every metric sharing those dimensions,
    // instead of one line per metric.
    const byDims = new Map();
    for (const e of entries) {
      const k = JSON.stringify(e.dims);
      if (!byDims.has(k)) byDims.set(k, { dims: e.dims, entries: [] });
      byDims.get(k).entries.push(e);
    }
    for (const { dims, entries: group } of byDims.values()) {
      const metrics = group.map((e) => ({ Name: e.name, Unit: e.unit }));
      const values = {};
      for (const e of group) {
        values[e.name] = e.samples ? e.samples : e.sum;
      }
      console.log(emfLine(dims, metrics, values));
    }
  } catch (err) {
    console.error('Metrics flush failed (metrics dropped, request unaffected):', err?.message || err);
  }
}

/**
 * Emits one EMF line immediately, bypassing the buffer. For gauges read at a point in time -
 * Neon storage, table row counts - where there is exactly one value per run and no invocation
 * boundary worth batching to. `metrics` is { name: value } or { name: { value, unit } }.
 */
export function gauge(metrics, dims = {}) {
  if (!ENABLED) return;
  try {
    const names = [];
    const values = {};
    for (const [name, raw] of Object.entries(metrics)) {
      const value = typeof raw === 'object' && raw !== null ? raw.value : raw;
      // Skip rather than emit a bogus zero: a field the Neon API didn't return should leave a
      // gap in the graph (and, with treatMissingData, a visibly stalled alarm), not a flat line
      // that reads as "storage is fine, it's zero".
      if (!Number.isFinite(value)) continue;
      const unit = (typeof raw === 'object' && raw !== null && raw.unit) || 'None';
      names.push({ Name: name, Unit: unit });
      values[name] = value;
    }
    if (names.length === 0) return;
    console.log(emfLine(dims, names, values));
  } catch (err) {
    console.error('Metrics gauge failed:', err?.message || err);
  }
}

export const metricsEnabled = ENABLED;
