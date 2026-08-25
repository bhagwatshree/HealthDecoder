# Monitoring

CloudWatch alarms for Gemini key/spend, the Neon database, and Lambda health. Ships **asleep** —
costing nothing — and is woken deliberately when there's a reason to watch.

## Why this exists at all

Two of the three things most able to cost real money or break the app overnight are invisible to
CloudWatch by default:

- **Gemini usage** is accounted in Postgres (`api_usage_events`, `gemini_key_minute_usage`). AWS
  has never heard of it.
- **The database is Neon**, not RDS. It is not an AWS resource, so AWS publishes *no* metrics for
  it — not storage, not connections, not compute.

So the metrics have to be produced before anything can watch them. Two mechanisms:

| | What | How |
|---|---|---|
| Request path | Gemini + Sarvam calls/cost, OTP verifications, key-pool pressure, DB latency/errors, quota refusals, abuse signals | [`metrics.js`](metrics.js) writes CloudWatch Embedded Metric Format to stdout; flushed once per invocation in [`lambda.js`](lambda.js) |
| Scheduled | Neon storage & compute quota, table sizes, sweep health | [`monitor.js`](monitor.js), an hourly Lambda |

EMF rather than `PutMetricData` because `PutMetricData` is a network round trip on a request path
that already spends 35–60s waiting on Gemini inside a 120s ceiling. A `console.log` costs nothing
measurable, needs no IAM permission, and can't fail a scan.

## Waking it and putting it back to sleep

**Actions tab → Backend deploy → Run workflow → `monitoring_mode`.**

| Choice | Effect |
|---|---|
| `active` | Creates the 24 alarms + the monitor function; the API starts emitting metrics |
| `sleep` | Deletes the alarms and the monitor; the API stops emitting |
| `unchanged` | Leaves the stack's current mode alone — what every normal push does |

Locally: `MONITORING_MODE=active npm run gen-samconfig && sam deploy`.

A plain `git push` never changes the mode. Only an explicit dispatch does.

### What survives sleep, and why

The **SNS topic** and the **dashboard** are deliberately *not* torn down. Both are free (an idle
topic costs nothing; the dashboard is 1 of 3 free), and keeping them means:

- the email subscription stays confirmed, so waking up doesn't need another click-the-link round
- CloudWatch keeps metric data for **15 months**, so the graphs from a test run stay readable long
  after the alarms are gone

Everything billable is gated behind the `MonitoringActive` condition.

## Testing it end to end

Do this once, on `active`, then go back to `sleep`.

1. **Wake it.** Actions tab → Run workflow → `monitoring_mode: active`. Wait for the deploy.
2. **Confirm the SNS email.** AWS sends a confirmation link to `ALERT_EMAIL` the first time it's
   set. Until it's clicked, every alarm is silent. This is the step most likely to be forgotten.
3. **Check the request-path metrics.** Use the app, or just hit the API, then look in the console
   under Metrics → All metrics → `HealthDecoder` → `Service`. Within a couple of minutes
   `GeminiCalls`, `DbQueries` and `DbQueryLatencyMs` should have data. If the namespace doesn't
   appear at all, the EMF isn't being extracted — check the function log for lines starting
   `{"_aws"`.
4. **Check the monitor.** It runs hourly; to not wait, invoke it manually (Lambda console →
   `…-MonitorFunction` → Test → any empty `{}` payload). Then look for `Service=neon` metrics and,
   in its log, a line beginning `Neon usage published:`. That line names any Neon API field that
   didn't match — worth reading once, since Neon has renamed these across API revisions.
5. **Test the notification path** without waiting for a real incident:

   ```sh
   aws cloudwatch set-alarm-state \
     --alarm-name medical-scanner-gemini-hourly-spend \
     --state-value ALARM --state-reason "notification path test"
   ```

   You should get an email within a minute. (Console equivalent: the alarm's own page has no
   "test" button, so this needs the CLI or a temporarily-tiny threshold.) The alarm re-evaluates
   itself back to `OK` on its next period.
6. **Look at the dashboard.** Its URL is a stack output, `DashboardUrl`.
7. **Sleep it.** Run workflow → `monitoring_mode: sleep`.

## Cost

Billed per **metric name × dimension-value combination**, and only in months a metric receives
data. Alarms bill per alarm that *exists*, data or not.

24 alarms exist in `active`, so 14 are billable past the free 10 — a flat **$1.40/month** regardless
of traffic. Metrics vary:

| State | Cost |
|---|---|
| `sleep` | **$0** — no alarms exist, no metric receives data, dashboard and topic are free |
| `active`, quiet, Gemini only | **~$7.70** — ~21 billable metrics ($6.30) + $1.40 |
| `active`, quiet, Sarvam + phone OTP also in use | **~$9.50** — ~27 billable metrics ($8.10) + $1.40 |
| `active`, everything firing | **~$13.70** — ~41 billable metrics ($12.30) + $1.40 |

Sarvam and phone-OTP metrics only exist if those features are actually enabled (`SARVAM_API_KEY`,
`FIREBASE_SERVICE_ACCOUNT_JSON`), which is why the middle two rows differ.

Free tiers absorbed above: 10 custom metrics, 10 alarms, 3 dashboards, 5 GB log ingestion. EMF adds
roughly 600 bytes per request, so logs stay inside the free tier below a few million requests/month.
The monitor Lambda (720 runs/month, 256 MB, ~2s) is inside the Lambda free tier.

`METRICS_DETAIL=0` drops the per-key / per-reason / per-scope breakdowns for about $1.20/month in a
quiet month. Alarms only ever watch undimensioned totals, so this costs diagnostic detail and never
coverage.

**Prices are AWS list prices for us-east-1 and are not verified against the pricing page.** Same
caveat as [`pricing.js`](pricing.js), which tracks provider list prices with a "last verified" date.

## Tuning thresholds

Every threshold is a CloudFormation parameter with a default, so retuning one is a deploy input
rather than a code change. Set the matching env var for a local deploy
(`DB_LATENCY_MS_THRESHOLD=3000 npm run gen-samconfig && sam deploy`); to drive it from CI, add a
line to the workflow's env block. See `optionalParamMap` in [`scripts/gen-samconfig.js`](scripts/gen-samconfig.js)
for the full env-var-to-parameter mapping.

Four alarms keep a hardcoded threshold of `1` — throttles, key-pool saturation, rate-limiter
fail-open, and monitor-stalled. For those, "any occurrence at all" *is* the condition, so there is
nothing meaningful to tune.

**Low traffic cuts both ways.** At a few requests an hour, abuse tripwires can sit high because
organic traffic will never approach them — but error *counts* are easy to trip on a handful of
events that mean nothing, and equally easy to miss when a real outage produces too few requests to
reach the threshold. Two defaults are already raised for this: `DbLatencyMsThreshold` (2000ms) and
`DbConnectionErrorsPer5Min` (6), because Neon autosuspends when idle and almost every request here
wakes a cold compute. Lower both once steady traffic makes the average meaningful.

## The alarms

Thresholds are set tight, to catch abuse and runaway spend early rather than quietly.

### Gemini and spend
| Alarm | Fires when |
|---|---|
| `gemini-hourly-spend` | Estimated spend > `GeminiHourlySpendUsd` (default $0.50) in an hour |
| `gemini-daily-spend` | Estimated spend > `GeminiDailySpendUsd` (default $2.00) in 24h |
| `gemini-errors` | ≥5 failed Gemini calls in 5 min — usually a revoked pooled key |
| `gemini-pool-saturated` | Every pooled key hit its RPM cap; callers got 503s. Add a key to `GEMINI_API_KEYS` |
| `ai-quota-exceeded` | ≥20 daily-cap refusals in 15 min — retry loop, or a script |
| `sarvam-daily-spend` | Estimated Sarvam spend > `SarvamDailySpendUsd` (default $1.00) in 24h |

### Database (Neon)
| Alarm | Fires when |
|---|---|
| `neon-storage` | >80% of `NeonStorageLimitBytes` |
| `neon-compute-hours` | >80% of `NeonComputeHoursLimit` |
| `db-query-errors` | ≥5 query failures in 5 min |
| `db-connection-errors` | ≥3 pooled-connection errors in 5 min |
| `db-latency` | Average query latency >500ms for 15 min |
| `sweep-gemini-key-usage` | `gemini_key_minute_usage` holds rows past its 10-min horizon |
| `sweep-ip-rate-limit` | `ip_rate_limit` holds rows past its 3-hour horizon |

The two sweep alarms exist because those tables are pruned opportunistically, on 2% of the calls
that touch them. That's fine while traffic flows and silent when it doesn't — if the sweep breaks,
the tables grow unbounded and the first symptom would be a Neon storage bill months later. Counting
rows *older than each sweep's own horizon* tests the sweep directly instead of guessing from size.

### Abuse and platform
| Alarm | Fires when |
|---|---|
| `ip-rate-limited` | ≥10 per-IP blocks in 15 min |
| `ip-rate-limit-failed-open` | The limiter couldn't reach the DB and let requests through **unchecked** |
| `attestation-rejected` | ≥10 Play Integrity rejections in 15 min |
| `lambda-errors` | ≥5 function errors in 5 min |
| `lambda-throttles` | Any throttling |
| `lambda-near-timeout` | An invocation ran within 20s of the 120s ceiling |
| `apigw-5xx` | ≥5 API Gateway 5xx in 5 min (HttpApi routes only — the AI proxy uses the Function URL, which has no API Gateway metrics) |
| `apigw-4xx-flood` | >30 4xx in 5 min — endpoint probing or a replayed token. Same HttpApi-only caveat |
| `lambda-invocation-burst` | >200 invocations in 5 min. Counts **every** entry point, so unlike the 4xx alarm this does cover the AI proxy's Function URL |
| `otp-volume` | >15 phone-OTP verifications in 5 min. Each is a billed SMS, so this is a cost alarm as much as an abuse one |
| `monitor-stalled` | No monitor report for 3 hours — **the alarm that watches the watcher** |

`monitor-stalled` matters more than it looks. Every Neon and table alarm treats missing data as
`missing`, not breaching, so a dead monitor would silently disable all of them. This is what
notices, and it's the one alarm where absence *is* the failure being detected.

## Folded in from `HealthDecoderAdmin`

A standalone `aws/cloudwatch-fraud-alarms.yaml` used to live in the HealthDecoderAdmin repo,
monitoring *this* backend from a separate stack. It was retired into here because it needed this
stack's Lambda name, API id and log group passed in by hand (its defaults —
`medical-scanner-prod-backend` — never matched the real CloudFormation-generated names), duplicated
the `lambda-errors` alarm exactly, and stood up a second SNS topic competing with `AlertTopic`.

What was worth keeping, and where it went:

| Its alarm | Outcome |
|---|---|
| `Lambda-Error-Surge` | Dropped — identical to `lambda-errors` |
| `Lambda-High-Invocation-Burst` | Kept as `lambda-invocation-burst` |
| `APIGateway-4xx-Flood` | Kept as `apigw-4xx-flood`, with its metric name **corrected** from `4XXError` (REST API) to `4xx` (HTTP API) — as written it could never have fired |
| `Fraud-SMS-OTP-Flooding` | Re-implemented as `otp-volume` |
| `Fraud-Sarvam-AI-Surge` | Re-implemented as `sarvam-daily-spend` |
| `Fraud-Gemini-API-Direct-Abuse` | Already covered by `gemini-hourly-spend` + `lambda-invocation-burst` |

The last three were driven by CloudWatch Logs metric filters like
`'[time, status="OTP_SENT", ...]'` and `'[time, provider="sarvam", ...]'`. Those patterns need
space-delimited log fields, and this backend never logs those tokens at all — `provider: 'sarvam'`
only ever goes into an `api_usage_events` row. They would have matched nothing and reported zero
forever. The replacements are instrumented in [`usageTracker.js`](usageTracker.js) at the same place
the billing ledger is written, so a metric can't drift from the charge it represents.

## Required setup

- **GitHub secrets:** `ALERT_EMAIL`, `NEON_API_KEY`, `NEON_PROJECT_ID`. All optional — without the
  first, alarms notify nobody; without the last two, Neon quota metrics don't publish and the
  table-growth metrics still do.
- **Neon plan limits:** `NEON_STORAGE_LIMIT_BYTES` / `NEON_COMPUTE_HOURS_LIMIT` default to the free
  plan (0.5 GiB, 191.9 hours). These are *not* read from the API — check your plan and set them,
  because the alarms watch percentage-of-these.
- **Deploy role IAM.** `github-actions-medical-scanner-deploy` needs `cloudwatch:PutMetricAlarm`,
  `DeleteAlarms`, `DescribeAlarms`, `PutDashboard`, `sns:CreateTopic`/`Subscribe`/`GetTopicAttributes`,
  `events:PutRule`/`PutTargets`, and `iam:CreateRole` for the monitor function. **The first `active`
  deploy fails without these.**

## A note on Version2

These metrics are server-side, and Version2 calls the same Lambda — so its traffic is already
counted in every Gemini and DB number here. Harmless while it's one bill and one database. When
Version2 goes live and the two need telling apart, the cheap way is a `Client` dimension keyed off a
header the app sends, set in the AI proxy route and passed through `metrics.js`.
