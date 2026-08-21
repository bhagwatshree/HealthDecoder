# Making the analysis layer model-agnostic

**Branch:** `backend/model-agnostic-llm` · **Scope:** backend only · **Status:** plan, not yet implemented

Today every AI call in this backend is a Gemini call. The goal is to be able to run the same
analysis on a different provider — OpenAI, Anthropic, a self-hosted model — by changing
configuration, without touching the Android or iOS clients and without rewriting the extraction
logic each time.

---

## Why this is worth doing

Two things this month made the coupling expensive rather than merely untidy:

- `gemini-3.7-flash` returned Google's own `503 UNAVAILABLE — model currently experiencing high
  demand` on **44% of production calls** the day it was switched on. There was no way to fail over;
  the only remedy was reverting the model and redeploying.
- The same switch changed the price per token by 5x, and `pricing.js` had to be corrected by hand
  because cost is expressed as a Gemini-shaped formula rather than as a property of a provider.

Neither is an argument that Gemini is the wrong choice. Both are arguments that *which* model
serves a request should be a runtime decision, not a structural assumption.

---

## The client is already agnostic — this is contained

`BackendAiClient.generate()` posts `{ prompt, images, operation }` and reads back `{ text }`.
The app has never known which provider answers. **No Android or iOS change is in scope**, and the
public contract of `POST /api/ai/generate` does not change.

Everything below lives in `backend/`.

---

## Current coupling inventory

| File | What is Gemini-specific | Difficulty |
|---|---|---|
| `server.js:899` | `new GoogleGenAI({ apiKey })` in the `/api/ai/generate` handler | Low — one call site |
| `ocr.js` | **8** separate `new GoogleGenAI(...)` sites (scan, comparison, TTS, translation, tips) | Medium — repetitive but mechanical |
| `keyPool.js` | `GEMINI_API_KEYS` pool, `GEMINI_RPM_LIMIT`, `gemini_key_minute_usage`, `resolveKeysFor*` returning `geminiKey` | Medium — the pooling *concept* generalises, the naming does not |
| `usageTracker.js` | `trackGemini(ai, params)` takes a Gemini client and reads `response.usageMetadata` | Medium — usage shape differs per provider |
| `pricing.js` | `GEMINI_PRICING` map, `costGeminiUsd(model, in, out)` | Low |
| `migrate.js` | `api_usage_events.gemini_key_index`, `gemini_key_minute_usage` table | Low — additive rename |
| `template.yaml`, `scripts/gen-samconfig.js` | `GeminiApiKey(s)` deploy parameters | Low |

`ai_response_cache` is already provider-neutral and needs no change.

---

## Target shape

One interface, one adapter per provider, everything else provider-blind.

```
                         ┌──────────────────────────────┐
  /api/ai/generate  ───► │  llm/index.js                │
                         │  resolve provider for a call │
                         └──────────────┬───────────────┘
                                        │  LlmRequest
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
            llm/gemini.js        llm/openai.js       llm/anthropic.js
                    │                   │                   │
                    └───────────────────┼───────────────────┘
                                        ▼
                                   LlmResponse
```

### The contract

```js
// One call, one provider-neutral answer.
// A provider module exports: { id, generate, supportsVision, defaultModel }
async function generate({ prompt, images, model, maxOutputTokens }) → {
  text,                 // string
  inputTokens,          // number | null
  outputTokens,         // number | null
  model,                // the model actually used
  provider,             // 'gemini' | 'openai' | 'anthropic' | ...
}
```

`images` stays in the shape the clients already send — `[{ data: base64, mimeType }]` — and each
adapter is responsible for translating it into whatever that provider wants. That translation is
the substantive work in each adapter:

| Provider | Image encoding | Usage field |
|---|---|---|
| Gemini | `{ inlineData: { data, mimeType } }` | `usageMetadata.promptTokenCount` / `candidatesTokenCount` |
| OpenAI | `{ type: 'image_url', image_url: { url: 'data:<mime>;base64,...' } }` | `usage.prompt_tokens` / `completion_tokens` |
| Anthropic | `{ type: 'image', source: { type: 'base64', media_type, data } }` | `usage.input_tokens` / `output_tokens` |

### Error normalisation

Retry and failover decisions must not depend on a provider's error shape. Each adapter maps its
own failures onto a small set:

| Normalised | Meaning | Caller behaviour |
|---|---|---|
| `RATE_LIMITED` | this key is over its quota right now | try the next key, then the next provider |
| `UNAVAILABLE` | provider-side capacity (the 3.7-flash 503) | try the next provider |
| `INVALID_REQUEST` | our fault — payload too large, bad image | fail fast, do not retry |
| `AUTH` | key rejected | drop this key from rotation, alert |

This is the piece that would have turned that 44% failure day into a non-event.

---

## Phases

Each phase leaves the system working and deployable. Do not start the next until the previous is
in production.

### Phase 1 — Extract the interface, Gemini as the only implementation

Create `llm/` with the contract above and `llm/gemini.js` wrapping today's exact behaviour.
Rewrite the single `/api/ai/generate` call site to go through it. Change nothing else.

*Done when:* production traffic runs through `llm/gemini.js` and the usage table looks identical
to the week before. No behaviour change is the acceptance criterion.

### Phase 2 — Generalise usage, cost and pooling

- `trackGemini(ai, params)` → `trackLlm(providerId, fn)`, recording the normalised
  `{ inputTokens, outputTokens, model, provider }`. Keep `trackGemini` as a thin shim until
  `ocr.js` is migrated.
- `pricing.js`: `PRICING[provider][model] = { inputPerMTokens, outputPerMTokens }`, and
  `costUsd(provider, model, in, out)`. An unknown model returns 0 **and logs loudly** — silent 0
  is how the 5x understatement went unnoticed.
- `keyPool.js`: `GEMINI_API_KEYS` → `LLM_KEYS_<PROVIDER>`, `gemini_key_minute_usage` →
  `llm_key_minute_usage (provider, key_hash, minute_bucket)`. Rename
  `api_usage_events.gemini_key_index` → `llm_key_index`.

Migration is additive: add the new columns, dual-write, backfill, then drop. The cost dashboard
in `Health_Decoder_Admin` reads these columns and must be updated in the same window.

### Phase 3 — Second adapter, config-selected

Add `llm/openai.js` (or Anthropic). Selection by env: `LLM_PROVIDER=gemini|openai`, with
`LLM_MODEL` overriding the provider's default. Still exactly one provider live at a time.

*Done when:* flipping one environment variable and redeploying moves all traffic, with no code
change.

### Phase 4 — Failover

Ordered provider list: `LLM_PROVIDERS=gemini,openai`. On `UNAVAILABLE` or exhausted
`RATE_LIMITED`, fall through to the next provider. Record which provider actually served the call
— that column already exists.

Two rules this phase must respect:

- **Failover happens before the response is used, never after a paid call succeeded.** The
  single-flight machinery in `/api/ai/generate` already guarantees one in-flight call per request
  hash; failover must reuse it rather than open a second billable path.
- **A failover response is not automatically trustworthy.** See below.

---

## The part that is not an engineering problem

Making it *easy* to switch providers does not make a new provider *correct* for this task. The
extraction prompt is not a summarisation prompt — it asks the model to apply about fifteen
interacting rules while reading a document: a date-priority hierarchy that differs by report type,
Indian dosage notation (`1-0-1`, `1/2-0-0`), the distinction between `durationDays`, `endDate` and
`intervalDays`, whether a value is measured or derived, and handwritten corrections overriding
struck-through print.

A provider that is excellent at general reasoning can still get `endDate` wrong, and a wrong
`endDate` is a patient taking an anticoagulant for the wrong number of days.

**Prerequisite for Phase 3, not a follow-up:** a golden evaluation set — 20–50 real reports with
human-verified expected JSON, scored field-by-field with medication and dosage fields weighted
highest. Without it, "switch provider" is a coin flip performed in production. With it, each
adapter has a number attached before it serves a request.

This is the same gap that made the 3.6 vs 3.7 decision guesswork, so it earns its keep
immediately, independent of whether a second provider is ever added.

---

## Out of scope

- Any Android or iOS change — the client contract is unchanged
- Sarvam (TTS and translation) — a different kind of service; can follow the same pattern later
- Firebase auth — not an LLM
- Prompt rewriting — the prompt is the specification and stays fixed, so eval results compare
  providers rather than prompts
- Multi-provider consensus / cross-checking answers — interesting, out of scope here

---

## Risks

| Risk | Mitigation |
|---|---|
| Silent accuracy regression on provider switch | Golden eval set gates Phase 3 |
| Cost tracking breaks mid-migration | Dual-write columns; update the admin dashboard in the same window |
| Failover doubles spend on a partial failure | Reuse the existing single-flight path; never fail over after a successful billable call |
| `ocr.js`'s 8 call sites drift from the new interface | Migrate them in Phase 2 and delete the `GoogleGenAI` import so drift cannot compile |
| A new provider's data policy differs | Check training-on-content terms per provider; this is medical data — see the free-tier note in the main README |
