package com.example.medicalscanner.model

/**
 * Medicine-name normalization so the SAME drug written differently across scans is treated as one.
 * The AI (and different prompt versions) emit a drug as "Tab. Concor", "Concor 5mg" or "concor 5 mg";
 * without this they became separate reminders/medications. [canonicalKey] collapses them to one key;
 * [cleanDisplay] gives a tidy label (form prefix dropped).
 */
object MedName {
    private val FORM_PREFIXES = listOf(
        "tablet", "tab.", "tab", "capsule", "cap.", "cap", "syrup", "syp.", "syp",
        "suspension", "susp.", "susp", "injection", "inj.", "inj", "ointment", "oint.",
        "solution", "sol.", "cream", "gel", "drops", "drop", "lotion", "powder", "sachet", "spray"
    )

    /** Strips a leading form word ("Tab.", "Syp." …) so "Tab. Concor 5mg" -> "Concor 5mg". */
    fun cleanDisplay(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (p in FORM_PREFIXES) {
                if (s.length > p.length && s.lowercase().startsWith("$p ")) {
                    s = s.substring(p.length).trim(); changed = true; break
                }
            }
        }
        return s.ifBlank { raw.trim() }
    }

    /**
     * Canonical match key: lowercase, form word dropped, strength/number tokens removed, punctuation
     * flattened. "Tab. Concor", "Concor 5mg", "concor 5 mg" all -> "concor"; "Tayo 60 K" -> "tayo".
     */
    fun canonicalKey(raw: String): String {
        var s = cleanDisplay(raw).lowercase()
        // Drop strength tokens: a number (optional decimal) with an optional unit.
        s = s.replace(Regex("""\b\d+(\.\d+)?\s*(mg|mcg|ml|g|gm|iu|k|units?|%)?\b"""), " ")
        s = s.replace(Regex("""[^a-z0-9]+"""), " ").trim().replace(Regex("""\s+"""), " ")
        return s.ifBlank { raw.trim().lowercase() }
    }
}
