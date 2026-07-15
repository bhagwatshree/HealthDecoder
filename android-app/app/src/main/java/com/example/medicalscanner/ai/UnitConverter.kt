package com.example.medicalscanner.ai

/**
 * Converts a lab value between the units different labs print for the SAME test, so a trend
 * line can be compared across reports (e.g. T3 in ng/mL at one lab vs nmol/L at another).
 *
 * Used only when building trend charts — the report detail screen always shows the value
 * exactly as the report printed it. Conversions are deterministic, on-device, and use only
 * medically-verified constants; a pair with no known factor returns null (the caller keeps the
 * reading separate and flags it rather than plotting a guessed value onto medical data).
 *
 * Each test defines a BASE unit (multiplier 1.0) and, for every alternate unit, the multiplier
 * that turns "1 of the alternate unit" into the base unit. Converting from→to is then just
 * `value * (fromMultiplier / toMultiplier)`, which can't get a direction backwards.
 *
 * Factor sources (standard clinical molar-mass conversions):
 *  - Glucose: 1 mmol/L = 18.016 mg/dL
 *  - Cholesterol/LDL/HDL: 1 mmol/L = 38.67 mg/dL
 *  - Triglycerides: 1 mmol/L = 88.57 mg/dL
 *  - Creatinine: 1 µmol/L = 0.011312 mg/dL  (÷88.4)
 *  - Calcium: 1 mmol/L = 4.008 mg/dL
 *  - Bilirubin: 1 µmol/L = 0.058479 mg/dL  (÷17.1)
 *  - Hemoglobin: 1 g/L = 0.1 g/dL
 *  - Vitamin D (25-OH): 1 nmol/L = 0.4006 ng/mL  (÷2.496)
 *  - Vitamin B12: 1 pmol/L = 1.355 pg/mL
 *  - Total T3: 1 nmol/L = 0.651 ng/mL; 1 ng/dL = 0.01 ng/mL  (MW 651)
 *  - Total T4: 1 nmol/L = 0.0777 µg/dL  (MW 776.87)
 */
object UnitConverter {

    /**
     * Normalizes a printed unit to a canonical spelling: lowercases, maps the micro sign to "u",
     * strips spaces, and collapses pure-notation synonyms that mean the exact same thing (so
     * e.g. "mg %" and "mg/dL" are recognized as identical and never trigger a conversion or a
     * "units differ" warning). Returns the cleaned unit unchanged when it has no known synonym.
     */
    fun canonicalizeUnitString(unit: String): String {
        val cleaned = unit.trim().lowercase()
            .replace('µ', 'u')
            .replace("μ", "u") // Greek mu, distinct code point from the micro sign above
            .replace(" ", "")
        return when (cleaned) {
            "mg%", "mg/100ml", "mgs/dl", "mgs%", "mgpercent" -> "mg/dl"
            "gm%", "gm/dl", "gms/dl", "g%" -> "g/dl"
            "mcg/dl" -> "ug/dl"
            "micromol/l" -> "umol/l"
            "miu/l", "uu/ml", "uiu/l" -> "uiu/ml" // TSH: µIU/mL ≡ mIU/L ≡ µU/mL (1:1)
            else -> cleaned
        }
    }

    // category (lowercased) -> (canonical unit -> multiplier to the base unit)
    private val factors: Map<String, Map<String, Double>> = mapOf(
        "blood sugar" to mapOf("mg/dl" to 1.0, "mmol/l" to 18.016),
        "total cholesterol" to mapOf("mg/dl" to 1.0, "mmol/l" to 38.67),
        "ldl" to mapOf("mg/dl" to 1.0, "mmol/l" to 38.67),
        "hdl" to mapOf("mg/dl" to 1.0, "mmol/l" to 38.67),
        "triglycerides" to mapOf("mg/dl" to 1.0, "mmol/l" to 88.57),
        "creatinine" to mapOf("mg/dl" to 1.0, "umol/l" to 0.011312),
        "hemoglobin" to mapOf("g/dl" to 1.0, "g/l" to 0.1),
        "vitamin d" to mapOf("ng/ml" to 1.0, "nmol/l" to 0.4006),
        "vitamin b12" to mapOf("pg/ml" to 1.0, "pmol/l" to 1.355),
        "t3" to mapOf("ng/ml" to 1.0, "nmol/l" to 0.651, "ng/dl" to 0.01),
        "t4" to mapOf("ug/dl" to 1.0, "nmol/l" to 0.0777),
    )

    /**
     * Converts [value] of [category] from [fromUnit] to [toUnit]. Returns the converted value,
     * or null when there is no verified factor for that pair (so the caller must NOT plot it as
     * comparable). Same-unit (after canonicalization) returns [value] unchanged.
     */
    fun convert(category: String, value: Float, fromUnit: String, toUnit: String): Float? {
        val from = canonicalizeUnitString(fromUnit)
        val to = canonicalizeUnitString(toUnit)
        if (from == to) return value
        val table = factors[category.trim().lowercase()] ?: return null
        val fromMult = table[from] ?: return null
        val toMult = table[to] ?: return null
        return (value * (fromMult / toMult)).toFloat()
    }
}
