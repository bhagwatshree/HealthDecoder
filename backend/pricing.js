// Estimated USD pricing for cost tracking (backend/usageTracker.js). These are published
// list prices, not real invoices — actual billing may differ (regional SMS rates, promos,
// rounding). Re-check the source links periodically; last verified 2026-07-12.
// INR->USD is only used to convert Sarvam's rupee rates into the same unit as everything else.
export const INR_PER_USD = 83;

// https://ai.google.dev/gemini-api/docs/pricing — last verified 2026-08-19. gemini-3.6-flash
// was previously listed here at 2.5-flash's rate ($0.30/$2.50); that was wrong, its real price
// is 5x higher ($1.50/$7.50) — fixed below. The app now defaults to gemini-3.7-flash (half the
// cost of 3.6, see server.js) but 3.6's entry is kept accurate for any historical/override calls.
export const GEMINI_PRICING = {
  'gemini-3.7-flash': {
    inputPerMTokens: 0.75,
    outputPerMTokens: 3.75,
  },
  'gemini-3.6-flash': {
    inputPerMTokens: 1.50,
    outputPerMTokens: 7.50,
  },
  'gemini-3.5-flash-lite': {
    inputPerMTokens: 0.30,
    outputPerMTokens: 2.50,
  },
  'gemini-2.5-flash': {
    inputPerMTokens: 0.30,
    outputPerMTokens: 2.50,
  },
  'gemini-2.5-flash-preview-tts': {
    inputPerMTokens: 0.50,
    outputPerMTokens: 10.00,
  },
};

// https://www.sarvam.ai/api-pricing (INR) — Doc digitization is per-page, translate/TTS
// are per-10k-characters, chat is per-1M-tokens.
export const SARVAM_PRICING_INR = {
  docDigitizationPerPage: 0.5,
  translatePer10kChars: 20,
  ttsPer10kChars: 22.5, // midpoint of the published 15-30 range
  chatInputPerMTokens: 4,
  chatOutputPerMTokens: 16,
};

// https://firebase.google.com/docs/phone-number-verification/pricing — India rate; first
// 10k verifications/month are free.
export const FIREBASE_PHONE_AUTH = {
  perVerificationUsd: 0.01,
  freeVerificationsPerMonth: 10000,
};

// https://aws.amazon.com/lambda/pricing/ (arm64/Graviton2, us-east-1) and
// https://aws.amazon.com/api-gateway/pricing/ (HTTP API, us-east-1)
export const AWS_PRICING = {
  lambdaArm64PerGbSecond: 0.0000133334,
  lambdaPerMRequests: 0.20,
  lambdaFreeRequestsPerMonth: 1_000_000,
  lambdaFreeGbSecondsPerMonth: 400_000,
  httpApiPerMRequests: 1.00,
};

function usd(n) {
  return Math.round(n * 1e6) / 1e6;
}

// Cached input tokens bill at a fraction of the normal input rate. 0.25 matches the published
// ratio on the Flash models (e.g. 2.5-flash at $0.075/M cached vs $0.30/M uncached) -- re-check it
// against the pricing page when the cached path is actually turned on, and note this does NOT model
// cache STORAGE, which Gemini bills per hour for as long as the cache lives.
export const GEMINI_CACHED_INPUT_MULTIPLIER = 0.25;

/**
 * Estimated USD for one Gemini call.
 *
 * `thinkingTokens` (usageMetadata.thoughtsTokenCount) is billed at the OUTPUT rate, not the input
 * rate and not free. Leaving it out understates every reasoning-model call -- and on
 * gemini-3.6-flash output costs 5x input, so the omission lands squarely on the expensive side.
 *
 * `cachedTokens` (usageMetadata.cachedContentTokenCount) is the part of promptTokenCount that came
 * from a context cache. It is a SUBSET of the prompt count rather than an addition, so it is
 * subtracted out and re-priced at the cached rate; double-counting it would overstate instead.
 */
export function costGeminiUsd(model, inputTokens, outputTokens, thinkingTokens = 0, cachedTokens = 0) {
  const rate = GEMINI_PRICING[model];
  if (!rate) return 0;
  const cached = Math.max(0, cachedTokens || 0);
  const freshInput = Math.max(0, (inputTokens || 0) - cached);
  const billedOutput = (outputTokens || 0) + (thinkingTokens || 0);
  return usd(
    (freshInput / 1_000_000) * rate.inputPerMTokens +
    (cached / 1_000_000) * rate.inputPerMTokens * GEMINI_CACHED_INPUT_MULTIPLIER +
    (billedOutput / 1_000_000) * rate.outputPerMTokens
  );
}

export function costSarvamUsd(operation, { chars = 0, pages = 0, inputTokens = 0, outputTokens = 0 } = {}) {
  const p = SARVAM_PRICING_INR;
  let inr = 0;
  if (operation === 'doc-digitization') inr = pages * p.docDigitizationPerPage;
  else if (operation === 'translate') inr = (chars / 10000) * p.translatePer10kChars;
  else if (operation === 'tts') inr = (chars / 10000) * p.ttsPer10kChars;
  else if (operation === 'chat') {
    inr = (inputTokens / 1_000_000) * p.chatInputPerMTokens + (outputTokens / 1_000_000) * p.chatOutputPerMTokens;
  }
  return usd(inr / INR_PER_USD);
}

export function costFirebaseVerifyUsd() {
  // Free-tier accounting (10k/month) happens at the dashboard aggregation layer, not per-event.
  return usd(FIREBASE_PHONE_AUTH.perVerificationUsd);
}
