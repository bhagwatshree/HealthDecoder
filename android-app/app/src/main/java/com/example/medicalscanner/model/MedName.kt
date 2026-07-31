package com.example.medicalscanner.model

/**
 * Medicine-name normalization so the SAME drug written differently across scans is treated as one.
 * The AI (and different prompt versions) emit a drug as "Tab. Concor", "Concor 5mg", "Syp. Alex SF"
 * or "Alex SF Syrup"; without this they became separate reminders/medications. [canonicalKey]
 * collapses them to one key; [cleanDisplay] gives a tidy label (form words dropped).
 */
object MedName {
    // Dosage-form words (without dots) that describe HOW a medicine is taken, not which drug it is.
    // Stripped wherever they appear (prefix or suffix) so "Syp. Cremaffin" == "Cremaffin Syrup".
    private val FORM_TOKENS = setOf(
        "tab", "tablet", "tablets", "cap", "caps", "capsule", "capsules", "syp", "syrup", "syrp",
        "susp", "suspension", "inj", "injection", "oint", "ointment", "sol", "soln", "solution",
        "cream", "gel", "drops", "drop", "lotion", "powder", "sachet", "spray", "soap", "tube"
    )

    private fun isFormWord(token: String) = token.trimEnd('.').lowercase() in FORM_TOKENS

    /** Drops leading/trailing form words: "Tab. Pan D" -> "Pan D", "Alex SF Syrup" -> "Alex SF". */
    fun cleanDisplay(raw: String): String {
        val tokens = raw.trim().split(Regex("""\s+""")).toMutableList()
        while (tokens.isNotEmpty() && isFormWord(tokens.first())) tokens.removeAt(0)
        while (tokens.isNotEmpty() && isFormWord(tokens.last())) tokens.removeAt(tokens.size - 1)
        return tokens.joinToString(" ").ifBlank { raw.trim() }
    }

    /**
     * Canonical match key: lowercase, ALL form words removed, strength/number tokens removed,
     * punctuation flattened. "Tab. Concor", "Concor 5mg", "Syp. Alex SF", "Alex SF Syrup" collapse
     * to "concor" / "alex sf"; "Tayo 60 K" -> "tayo".
     */
    fun canonicalKey(raw: String): String {
        var s = raw.lowercase()
        // Drop strength tokens: a number (optional decimal) with an optional unit.
        s = s.replace(Regex("""\b\d+(\.\d+)?\s*(mg|mcg|ml|g|gm|iu|k|units?|%)?\b"""), " ")
        val tokens = s.split(Regex("""[^a-z0-9]+"""))
            .filter { it.isNotBlank() && it !in FORM_TOKENS }
        return tokens.joinToString(" ").ifBlank { raw.trim().lowercase() }
    }
}
