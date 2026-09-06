package com.healthdecoder.app.util

import com.healthdecoder.app.ai.UnitConverter
import com.healthdecoder.app.model.VitalCatalog

/**
 * Curated, factual, plain-language descriptions and Normal/High/Low classification for the
 * patient-logged home readings (see [VitalCatalog]) — the [VitalReference] counterpart to
 * [TestReference], and held to the same discipline: a fixed reference, NOT AI-generated, and
 * educational rather than diagnostic (see docs/IMPLEMENTATION_PLAN_MANUAL_VITALS.md §8).
 *
 * These are HOME-measurement thresholds, which are deliberately stricter than clinic thresholds
 * for BP (home/ambulatory target ~135/85, vs. the clinic's ~140/90) — using the clinic figure here
 * would systematically under-flag a home reading. A critical-value "seek care" prompt is a
 * separate, later phase (plan §9 Phase 2) — this object only classifies, it never alerts.
 */
object VitalReference {

    private val entries: Map<String, TestInfo> = mapOf(
        VitalCatalog.TREND_BLOOD_SUGAR_HOME to TestInfo(
            "Blood Sugar (Home)",
            "Measured with a glucometer at home. A capillary (fingerstick) reading, which is a " +
                "different measurement from a lab's venous plasma glucose — the two are tracked " +
                "separately rather than on one chart."
        ),
        VitalCatalog.TREND_SYSTOLIC_HOME to TestInfo(
            "Systolic (Home)",
            "The top number of a blood pressure reading — the pressure in your arteries when your " +
                "heart beats. Home targets are lower than clinic targets, since readings taken at " +
                "home without \"white coat\" anxiety tend to run lower."
        ),
        VitalCatalog.TREND_DIASTOLIC_HOME to TestInfo(
            "Diastolic (Home)",
            "The bottom number of a blood pressure reading — the pressure in your arteries between " +
                "heartbeats, while the heart rests."
        ),
        VitalCatalog.TREND_PULSE_HOME to TestInfo(
            "Pulse (Home)",
            "Your heart rate, in beats per minute. Combines pulse logged on its own with pulse " +
                "recorded alongside a blood pressure or oxygen reading, since most home devices " +
                "report it as part of that same measurement."
        ),
        VitalCatalog.TREND_SPO2_HOME to TestInfo(
            "Oxygen (SpO2, Home)",
            "The percentage of oxygen your blood is carrying, measured with a fingertip pulse " +
                "oximeter."
        )
    )

    fun describe(trendName: String): TestInfo? = entries[trendName]

    /** Normal / High / Low for a glucometer reading, given its printed unit and logging context.
     *  Educational classification only — never a diagnosis. */
    fun glucoseStatus(value: Float, unit: String, context: String): String {
        val mgDl = if (UnitConverter.canonicalizeUnitString(unit) == "mmol/l")
            UnitConverter.convert("blood sugar", value, unit, "mg/dL") ?: value
        else value
        val postMeal = context.contains("after meal", ignoreCase = true)
        val high = if (postMeal) 180f else 130f
        return when {
            mgDl < 70f -> "Low"
            mgDl > high -> "High"
            else -> "Normal"
        }
    }

    // Home BP target (~135/85), not the higher clinic threshold — see this object's doc comment.
    //
    // Systolic and diastolic are classified INDEPENDENTLY, because they are independently
    // meaningful numbers: 150/70 is a high systolic sitting next to a perfectly normal diastolic.
    // A trend line showing only one of the two must use its own component's status, or it reports
    // a normal number as abnormal (see DashboardEngine.buildVitalsSummary).

    fun systolicStatus(systolic: Float): String = when {
        systolic < 90f -> "Low"
        systolic >= 135f -> "High"
        else -> "Normal"
    }

    fun diastolicStatus(diastolic: Float): String = when {
        diastolic < 60f -> "Low"
        diastolic >= 85f -> "High"
        else -> "Normal"
    }

    /**
     * The status of a BP reading taken AS A WHOLE — high if either number is high, which is how a
     * blood pressure reading is classified clinically. Use this for a single badge describing one
     * complete reading; use [systolicStatus]/[diastolicStatus] when showing either number alone.
     * Derived from the two component functions so the thresholds can never drift apart.
     */
    fun bpStatus(systolic: Float, diastolic: Float): String {
        val s = systolicStatus(systolic)
        val d = diastolicStatus(diastolic)
        return when {
            s == "Low" || d == "Low" -> "Low"
            s == "High" || d == "High" -> "High"
            else -> "Normal"
        }
    }

    fun pulseStatus(value: Float): String = when {
        value < 60f -> "Low"
        value > 100f -> "High"
        else -> "Normal"
    }

    fun spo2Status(value: Float): String = when {
        value < 95f -> "Low"
        else -> "Normal"
    }
}
