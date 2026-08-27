package com.healthdecoder.app.ai

/**
 * Detects lines that are marketing/promotional filler rather than clinical content — a lab
 * chain's "download our app" banner, social-media handles, QR-code prompts, discount codes —
 * so they can be blacked out before the page is uploaded, same mechanism and same bias as
 * [PiiRedactor]. This isn't about privacy: an ad line costs real Gemini input tokens for
 * nothing, and on a cluttered page it's one more thing the model has to correctly ignore while
 * extracting lab values.
 *
 * Deliberately narrow and high-confidence, same rule as PiiRedactor: missing an ad costs nothing,
 * blacking out a real value is a wrong lab result. Every pattern here is wording/format that does
 * not occur in clinical prose — never a bare number or percentage alone.
 */
object AdRedactor {

    /** Website/social plugs and app-store prompts. */
    private val PROMO_LINK = Regex(
        """\b(download\s+(our|the)\s+app|follow\s+us\s+on|like\s+us\s+on|subscribe\s+to\s+our|""" +
            """scan\s+(the\s+)?qr\s*code|visit\s+us\s+at|book\s+(your\s+)?appointment\s+online|""" +
            """(available|download)\s+on\s+(the\s+)?(play\s*store|app\s*store))\b""",
        RegexOption.IGNORE_CASE
    )

    /** Bare URLs and social handles — never appear in a lab-value table or a prescription line. */
    private val URL_OR_HANDLE = Regex(
        """www\.[a-z0-9-]+\.[a-z]{2,}|https?://\S+|""" +
            """\b(facebook|instagram|twitter|youtube|linkedin)\.com/\S+|""" +
            """@[a-z][a-z0-9_.]{2,}\b""",
        RegexOption.IGNORE_CASE
    )

    /** Discount/offer wording — a lab's own promotional line, not a clinical figure. */
    private val OFFER = Regex(
        """\b((get|avail)\s+\d{1,3}\s*%\s*(off|discount)|discount\s+on\s+(your\s+)?next|""" +
            """terms\s+and\s+conditions\s+apply|limited\s+period\s+offer|use\s+code\s+\w+)\b""",
        RegexOption.IGNORE_CASE
    )

    internal fun shouldRedact(rawLine: String): Boolean {
        val line = rawLine.trim()
        if (line.isBlank()) return false
        return PROMO_LINK.containsMatchIn(line) || URL_OR_HANDLE.containsMatchIn(line) || OFFER.containsMatchIn(line)
    }
}
