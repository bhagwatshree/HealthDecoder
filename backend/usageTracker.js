import { AsyncLocalStorage } from 'async_hooks';
import db from './db.js';
import { costGeminiUsd, costSarvamUsd, costFirebaseVerifyUsd } from './pricing.js';
import { count, detail, observe, sum } from './metrics.js';

const als = new AsyncLocalStorage();

/** Wraps a request handler so trackGemini/trackSarvam/trackFirebaseVerify calls inside it
 *  are attributed to the given operation/user (or anonymous device — [deviceId] is the
 *  `devices.id` UUID row, from keyPool.getOrCreateDevice, not the client's own device_id
 *  string), then flushes them to api_usage_events once the handler settles (success or
 *  failure) — logging failures never affect the response. */
export async function runWithUsageContext({ userId, deviceId, operation, keyIndex }, fn) {
  const events = [];
  try {
    return await als.run({ userId, deviceId, operation, keyIndex, events }, fn);
  } finally {
    if (events.length > 0) {
      await flushEvents(events).catch((err) => console.error('Failed to write usage events:', err.message));
    }
  }
}

function record(event) {
  const store = als.getStore();
  if (!store) return; // Not inside a tracked request (e.g. called from a script) — skip silently.
  store.events.push({ userId: store.userId, deviceId: store.deviceId, operation: store.operation, keyIndex: store.keyIndex, ...event });
}

async function flushEvents(events) {
  for (const e of events) {
    await db.query(
      `INSERT INTO api_usage_events
         (user_id, device_id, provider, operation, model, input_tokens, output_tokens,
          thinking_tokens, cached_tokens, units, latency_ms, cost_usd, success, gemini_key_index)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
      [e.userId || null, e.deviceId || null, e.provider, e.operation, e.model || null, e.inputTokens ?? null,
       e.outputTokens ?? null, e.thinkingTokens ?? null, e.cachedTokens ?? null,
       e.units ?? null, e.latencyMs ?? null, e.costUsd || 0, e.success !== false,
       e.provider === 'gemini' ? (e.keyIndex ?? null) : null]
    );
  }
}

/** Wraps `ai.models.generateContent(params)`, timing it and logging tokens + estimated cost.
 *
 *  The api_usage_events row written by `record` is the durable, per-caller billing ledger and
 *  stays the source of truth for reconciliation. The CloudWatch metrics alongside it serve the
 *  other need: something has to notice a runaway spend WHILE it is happening, and a Postgres
 *  table nobody is querying at 3am cannot page anyone. Same numbers, different job. */
export async function trackGemini(ai, params) {
  const start = Date.now();
  try {
    const response = await ai.models.generateContent(params);
    const usage = response.usageMetadata || {};
    const latencyMs = Date.now() - start;
    // thoughtsTokenCount is a SEPARATE field from candidatesTokenCount and is billed at the output
    // rate. Reading only candidatesTokenCount -- as this did originally -- understates the cost of
    // every call the model thinks on, and since output is 5x input on gemini-3.6-flash the miss
    // lands on the expensive side. It also silently weakened the spend alarms, which can only be as
    // accurate as this number.
    const thinkingTokens = usage.thoughtsTokenCount || 0;
    // Subset of promptTokenCount served from a context cache, priced lower. Zero until caching is
    // actually enabled, but read now so switching it on doesn't quietly overstate the bill.
    const cachedTokens = usage.cachedContentTokenCount || 0;
    const costUsd = costGeminiUsd(
      params.model, usage.promptTokenCount, usage.candidatesTokenCount, thinkingTokens, cachedTokens
    );
    record({
      provider: 'gemini',
      model: params.model,
      inputTokens: usage.promptTokenCount,
      outputTokens: usage.candidatesTokenCount,
      thinkingTokens,
      cachedTokens,
      latencyMs,
      costUsd,
      success: true,
    });
    count('GeminiCalls');
    sum('GeminiCostUsd', costUsd, 'None');
    count('GeminiInputTokens', usage.promptTokenCount || 0);
    count('GeminiOutputTokens', usage.candidatesTokenCount || 0);
    // Tracked separately from GeminiOutputTokens rather than folded into it: they bill the same but
    // mean different things, and "how much am I paying the model to think?" is exactly the question
    // you need answered before deciding whether to cap thinkingBudget.
    count('GeminiThinkingTokens', thinkingTokens);
    count('GeminiCachedTokens', cachedTokens);
    observe('GeminiLatencyMs', latencyMs);
    return response;
  } catch (error) {
    const latencyMs = Date.now() - start;
    record({ provider: 'gemini', model: params.model, latencyMs, costUsd: 0, success: false });
    count('GeminiCalls');
    count('GeminiErrors');
    observe('GeminiLatencyMs', latencyMs);
    throw error;
  }
}

/** Wraps a Sarvam API call. `sarvamOp` ('translate' | 'tts' | 'doc-digitization' | 'chat') is
 *  stored in the `model` column so the request-level `operation` (ocr/chat/compare/...) set by
 *  runWithUsageContext is preserved for feature-level grouping. `meta` describes what to bill
 *  it as: { chars } for translate/tts, { pages } for doc-digitization, {inputTokens,outputTokens}
 *  for chat. `fn` is the actual fetch/network call to run and time. */
export async function trackSarvam(sarvamOp, meta, fn) {
  const start = Date.now();
  try {
    const result = await fn();
    const latencyMs = Date.now() - start;
    const costUsd = costSarvamUsd(sarvamOp, meta);
    record({
      provider: 'sarvam',
      model: sarvamOp,
      units: meta.chars ?? meta.pages ?? null,
      latencyMs,
      costUsd,
      success: true,
    });
    // Sarvam is a billable provider (per page for doc digitization, per 10k characters for
    // translate/TTS - see pricing.js) and had no live monitoring at all, only the api_usage_events
    // ledger. Mirrors trackGemini exactly, for the same reason: a table nobody queries at 3am
    // cannot notice a runaway spend while it is still running.
    count('SarvamCalls');
    sum('SarvamCostUsd', costUsd, 'None');
    observe('SarvamLatencyMs', latencyMs);
    // Which operation is driving the bill. Bounded vocabulary ('translate' | 'tts' |
    // 'doc-digitization' | 'chat'), so it is safe as a dimension.
    detail('SarvamCallsByOp', 1, { Operation: String(sarvamOp) });
    return result;
  } catch (error) {
    const latencyMs = Date.now() - start;
    record({ provider: 'sarvam', model: sarvamOp, latencyMs, costUsd: 0, success: false });
    count('SarvamCalls');
    count('SarvamErrors');
    observe('SarvamLatencyMs', latencyMs);
    throw error;
  }
}

/** Logs one Firebase phone-auth ID token verification. Writes directly (fire-and-forget) rather
 *  than through the request-scoped `record`/`runWithUsageContext` machinery, since signup/login
 *  routes call this standalone rather than around an AI operation. */
export function trackFirebaseVerify(userId, success) {
  db.query(
    `INSERT INTO api_usage_events (user_id, provider, operation, units, cost_usd, success)
     VALUES ($1, 'firebase', 'otp-verify', 1, $2, $3)`,
    [userId || null, success ? costFirebaseVerifyUsd() : 0, success]
  ).catch((err) => console.error('Failed to write usage event:', err.message));

  // Every verification is a billed SMS ($0.01 at the India rate, 10k/month free) - so unlike most
  // counters here, volume IS the cost. Worth alarming on in its own right rather than inferring it
  // from the auth-scope IP limiter: that limiter caps a single network, while OTP flooding spread
  // across many IPs would slip under it entirely and still run up a real bill.
  count('OtpVerifications');
  if (!success) count('OtpVerifyFailures');
}
