package com.healthdecoder.app.model

/**
 * Declares everything a manual-entry form needs for one metric — fields, units, context chips,
 * a typo-guard range, and which Home Readings trend line it feeds — so adding a new metric later
 * (Tier 2/3 of docs/IMPLEMENTATION_PLAN_MANUAL_VITALS.md) is one entry here, not a new screen.
 */
data class VitalMetric(
    val key: String,
    val displayName: String,
    val emoji: String,
    val group: String,
    val units: List<String> = emptyList(),       // first = default; empty = unitless
    val contextOptions: List<String> = emptyList(),
    val secondValueLabel: String = "",            // non-blank => a second numeric field (BP diastolic)
    val hasPulseField: Boolean = false,           // optional third numeric field (BP/SpO2 pulse)
    // Data-entry sanity check ONLY (catches a fat-fingered "1200"), never a clinical/normal
    // range — see docs/IMPLEMENTATION_PLAN_MANUAL_VITALS.md §8 rule 4.
    val plausibleRange: ClosedFloatingPointRange<Float>,
    val plausibleRange2: ClosedFloatingPointRange<Float>? = null,
    // The Home Readings trend line(s) this metric's readings are plotted on. Always suffixed
    // "(Home)" so it can never collide with a lab canonical name from DashboardEngine —
    // lab and home data are never displayed on the same line (see plan §7.1).
    val trendNames: List<String>
)

object VitalCatalog {
    const val KEY_GLUCOSE = "glucose"
    const val KEY_BP = "bp"
    const val KEY_PULSE = "pulse"
    const val KEY_SPO2 = "spo2"

    const val GROUP_DIABETES = "Diabetes"
    const val GROUP_VITALS = "Vitals"

    const val TREND_BLOOD_SUGAR_HOME = "Blood Sugar (Home)"
    const val TREND_SYSTOLIC_HOME = "Systolic (Home)"
    const val TREND_DIASTOLIC_HOME = "Diastolic (Home)"
    const val TREND_PULSE_HOME = "Pulse (Home)"
    const val TREND_SPO2_HOME = "SpO2 (Home)"

    /** Phase 1 (MVP): sugar, BP, heart rate, oxygen — see plan §2 Tier 1a. */
    val METRICS: List<VitalMetric> = listOf(
        VitalMetric(
            key = KEY_GLUCOSE,
            displayName = "Blood Sugar",
            emoji = "🩸",
            group = GROUP_DIABETES,
            units = listOf("mg/dL", "mmol/L"),
            contextOptions = listOf("Fasting", "Before meal", "2h after meal", "Random", "Bedtime", "Low-sugar symptoms"),
            plausibleRange = 20f..800f,
            trendNames = listOf(TREND_BLOOD_SUGAR_HOME)
        ),
        VitalMetric(
            key = KEY_BP,
            displayName = "Blood Pressure",
            emoji = "💗",
            group = GROUP_VITALS,
            units = listOf("mmHg"),
            contextOptions = listOf("Sitting", "Standing", "Lying", "Left arm", "Right arm", "Before medicine", "After medicine"),
            secondValueLabel = "Diastolic",
            hasPulseField = true,
            plausibleRange = 60f..260f,
            plausibleRange2 = 30f..180f,
            trendNames = listOf(TREND_SYSTOLIC_HOME, TREND_DIASTOLIC_HOME)
        ),
        VitalMetric(
            key = KEY_PULSE,
            displayName = "Heart Rate",
            emoji = "❤️",
            group = GROUP_VITALS,
            units = listOf("bpm"),
            contextOptions = listOf("At rest", "After activity"),
            plausibleRange = 30f..220f,
            trendNames = listOf(TREND_PULSE_HOME)
        ),
        VitalMetric(
            key = KEY_SPO2,
            displayName = "Oxygen (SpO2)",
            emoji = "🫁",
            group = GROUP_VITALS,
            units = listOf("%"),
            contextOptions = listOf("At rest", "After walking", "On oxygen"),
            hasPulseField = true,
            plausibleRange = 50f..100f,
            trendNames = listOf(TREND_SPO2_HOME)
        )
    )

    fun byKey(key: String): VitalMetric? = METRICS.firstOrNull { it.key == key }

    /** Which metric produced a given Home Readings trend line, for click-through navigation. */
    fun metricKeyForTrendName(trendName: String): String? =
        METRICS.firstOrNull { trendName in it.trendNames }?.key
}
