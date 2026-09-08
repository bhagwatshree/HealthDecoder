package com.healthdecoder.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the curated (NOT AI-generated) Normal/High/Low thresholds for patient-logged readings.
 * These decide what colour a number is shown in, so a wrong boundary tells someone their reading
 * is fine when it is not — the reason the values are pinned here rather than left implicit.
 */
class VitalReferenceTest {

    // ── Blood pressure ──────────────────────────────────────────────────────

    @Test
    fun `home BP uses the home target, which is lower than the clinic threshold`() {
        // Home/ambulatory hypertension threshold is ~135/85, not the clinic's 140/90. Using the
        // clinic figure would pass a 138/86 home reading as Normal when it is not.
        assertEquals("Normal", VitalReference.systolicStatus(134f))
        assertEquals("High", VitalReference.systolicStatus(135f))
        assertEquals("Normal", VitalReference.diastolicStatus(84f))
        assertEquals("High", VitalReference.diastolicStatus(85f))
    }

    @Test
    fun `unusually low readings are flagged low, not silently normal`() {
        assertEquals("Low", VitalReference.systolicStatus(89f))
        assertEquals("Normal", VitalReference.systolicStatus(90f))
        assertEquals("Low", VitalReference.diastolicStatus(59f))
        assertEquals("Normal", VitalReference.diastolicStatus(60f))
    }

    @Test
    fun `a whole BP reading is high when either number is high`() {
        assertEquals("High", VitalReference.bpStatus(150f, 70f))  // upper only
        assertEquals("High", VitalReference.bpStatus(120f, 95f))  // lower only
        assertEquals("Normal", VitalReference.bpStatus(120f, 80f))
    }

    @Test
    fun `the combined BP status is derived from the two component thresholds`() {
        // Guards against the thresholds drifting apart if someone edits one and not the other.
        for (s in listOf(85f, 95f, 134f, 136f, 180f)) {
            for (d in listOf(55f, 70f, 84f, 86f, 120f)) {
                val expected = when {
                    VitalReference.systolicStatus(s) == "Low" || VitalReference.diastolicStatus(d) == "Low" -> "Low"
                    VitalReference.systolicStatus(s) == "High" || VitalReference.diastolicStatus(d) == "High" -> "High"
                    else -> "Normal"
                }
                assertEquals("$s/$d", expected, VitalReference.bpStatus(s, d))
            }
        }
    }

    // ── Blood sugar ─────────────────────────────────────────────────────────

    @Test
    fun `the same sugar number means different things fasting and after a meal`() {
        assertEquals("High", VitalReference.glucoseStatus(150f, "mg/dL", "Fasting"))
        assertEquals("Normal", VitalReference.glucoseStatus(150f, "mg/dL", "2h after meal"))
    }

    @Test
    fun `a low sugar is flagged regardless of when it was taken`() {
        assertEquals("Low", VitalReference.glucoseStatus(65f, "mg/dL", "Fasting"))
        assertEquals("Low", VitalReference.glucoseStatus(65f, "mg/dL", "2h after meal"))
    }

    @Test
    fun `an mmol per L reading is classified on the same scale as mg per dL`() {
        // 5.0 mmol/L is ~90 mg/dL (normal); 9.0 mmol/L is ~162 mg/dL (high, fasting). Classifying
        // the raw mmol number against mg/dL thresholds would call every SI reading dangerously low.
        assertEquals("Normal", VitalReference.glucoseStatus(5.0f, "mmol/L", "Fasting"))
        assertEquals("High", VitalReference.glucoseStatus(9.0f, "mmol/L", "Fasting"))
    }

    // ── Pulse and oxygen ────────────────────────────────────────────────────

    @Test
    fun `resting pulse boundaries`() {
        assertEquals("Low", VitalReference.pulseStatus(59f))
        assertEquals("Normal", VitalReference.pulseStatus(60f))
        assertEquals("Normal", VitalReference.pulseStatus(100f))
        assertEquals("High", VitalReference.pulseStatus(101f))
    }

    @Test
    fun `oxygen saturation below 95 percent is flagged`() {
        assertEquals("Normal", VitalReference.spo2Status(98f))
        assertEquals("Normal", VitalReference.spo2Status(95f))
        assertEquals("Low", VitalReference.spo2Status(94f))
    }

    @Test
    fun `every home trend line has a plain-language description`() {
        // A chart of an unexplained number is not much use to a non-medical reader.
        for (name in com.healthdecoder.app.model.VitalCatalog.METRICS.flatMap { it.trendNames }) {
            val info = VitalReference.describe(name)
            org.junit.Assert.assertNotNull("no description for $name", info)
            org.junit.Assert.assertTrue(info!!.description.isNotBlank())
        }
    }
}
