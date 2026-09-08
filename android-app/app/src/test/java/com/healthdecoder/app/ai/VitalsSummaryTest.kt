package com.healthdecoder.app.ai

import com.healthdecoder.app.model.VitalCatalog
import com.healthdecoder.app.model.VitalReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [DashboardEngine.buildVitalsSummary] — the Home Readings trend builder for
 * patient-logged vitals. The cases here are the ones where a defect would be silent and
 * clinically misleading rather than obviously broken.
 */
class VitalsSummaryTest {

    private fun reading(
        metric: String,
        value: String,
        value2: String = "",
        value3: String = "",
        unit: String = "",
        recordedAt: String = "2026-09-06T09:00",
        context: String = ""
    ) = VitalReading(
        id = "$metric-$recordedAt-$value",
        patientName = "Ramesh",
        metric = metric,
        value = value,
        value2 = value2,
        value3 = value3,
        unit = unit,
        context = context,
        note = "",
        recordedAt = recordedAt,
        createdAt = "${recordedAt}:00.000Z"
    )

    private fun linesOf(vararg readings: VitalReading) =
        DashboardEngine.buildVitalsSummary("Ramesh", readings.toList())
            .parameterTrends.associateBy { it.name }

    /** As [linesOf], but with the chart's standard unit resolved, as the repository supplies it. */
    private fun linesInMgDl(vararg readings: VitalReading) =
        DashboardEngine.buildVitalsSummary(
            "Ramesh", readings.toList(),
            mapOf(VitalCatalog.TREND_BLOOD_SUGAR_HOME to "mg/dL")
        ).parameterTrends.associateBy { it.name }

    // ── Blood pressure ──────────────────────────────────────────────────────

    @Test
    fun `systolic and diastolic are classified independently, not by a combined status`() {
        // 150/70 is a high upper number sitting next to a perfectly normal lower one. Applying one
        // combined status to both lines reported the normal 70 as High — a number the patient is
        // being told is abnormal when it is not.
        val lines = linesOf(reading(VitalCatalog.KEY_BP, "150", value2 = "70", unit = "mmHg"))

        assertEquals("High", lines.getValue(VitalCatalog.TREND_SYSTOLIC_HOME).dataPoints.single().status)
        assertEquals("Normal", lines.getValue(VitalCatalog.TREND_DIASTOLIC_HOME).dataPoints.single().status)
    }

    @Test
    fun `a low upper number with a normal lower number reports only the upper as low`() {
        val lines = linesOf(reading(VitalCatalog.KEY_BP, "85", value2 = "70", unit = "mmHg"))

        assertEquals("Low", lines.getValue(VitalCatalog.TREND_SYSTOLIC_HOME).dataPoints.single().status)
        assertEquals("Normal", lines.getValue(VitalCatalog.TREND_DIASTOLIC_HOME).dataPoints.single().status)
    }

    @Test
    fun `one BP reading produces one point on each of the two lines, carrying each own number`() {
        val lines = linesOf(reading(VitalCatalog.KEY_BP, "128", value2 = "82", unit = "mmHg"))

        assertEquals("128", lines.getValue(VitalCatalog.TREND_SYSTOLIC_HOME).dataPoints.single().value)
        assertEquals("82", lines.getValue(VitalCatalog.TREND_DIASTOLIC_HOME).dataPoints.single().value)
    }

    // ── Pulse: unioned from three different sources ─────────────────────────

    @Test
    fun `pulse logged alongside BP and SpO2 joins the same line as standalone pulse`() {
        // Most home devices report pulse as part of the BP or SpO2 measurement. If the Pulse line
        // read only the standalone metric, a user who always takes their pulse off the cuff would
        // open an empty chart despite having logged it every day.
        val lines = linesOf(
            reading(VitalCatalog.KEY_BP, "120", value2 = "80", value3 = "72", unit = "mmHg", recordedAt = "2026-09-06T08:00"),
            reading(VitalCatalog.KEY_SPO2, "97", value3 = "75", unit = "%", recordedAt = "2026-09-06T12:00"),
            reading(VitalCatalog.KEY_PULSE, "68", unit = "bpm", recordedAt = "2026-09-06T20:00")
        )

        val pulse = lines.getValue(VitalCatalog.TREND_PULSE_HOME)
        assertEquals(listOf("72", "75", "68"), pulse.dataPoints.map { it.value })
    }

    @Test
    fun `the unioned pulse line stays in chronological order regardless of source order`() {
        // The three sources are appended per-metric, so interleaved timestamps can arrive out of
        // order; an unsorted line would draw the chart zig-zagging backwards through time.
        val lines = linesOf(
            reading(VitalCatalog.KEY_PULSE, "60", unit = "bpm", recordedAt = "2026-09-06T21:00"),
            reading(VitalCatalog.KEY_BP, "120", value2 = "80", value3 = "70", unit = "mmHg", recordedAt = "2026-09-06T07:00"),
            reading(VitalCatalog.KEY_SPO2, "98", value3 = "65", unit = "%", recordedAt = "2026-09-06T14:00")
        )

        val dates = lines.getValue(VitalCatalog.TREND_PULSE_HOME).dataPoints.map { it.date }
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `a BP reading with no pulse entered contributes no pulse point`() {
        val lines = linesOf(reading(VitalCatalog.KEY_BP, "120", value2 = "80", unit = "mmHg"))
        assertNull(lines[VitalCatalog.TREND_PULSE_HOME])
    }

    // ── Blood sugar: units ──────────────────────────────────────────────────

    @Test
    fun `an mmol per L sugar reading is converted onto the same scale as mg per dL readings`() {
        // Switching glucometers must not put two different scales on one line: 6.0 mmol/L is the
        // same reading as ~108 mg/dL, and plotting 6.0 next to 108 would look like a crash in
        // blood sugar rather than a change of device.
        val lines = linesInMgDl(
            reading(VitalCatalog.KEY_GLUCOSE, "108", unit = "mg/dL", recordedAt = "2026-09-05T09:00"),
            reading(VitalCatalog.KEY_GLUCOSE, "6.0", unit = "mmol/L", recordedAt = "2026-09-06T09:00")
        )

        val points = lines.getValue(VitalCatalog.TREND_BLOOD_SUGAR_HOME).dataPoints
        assertEquals(2, points.size)
        // Both now expressed in one unit, and the converted point records what was actually typed.
        assertEquals(points[0].unit, points[1].unit)
        assertEquals(108f, points[1].value.toFloat(), 1.0f)
        assertTrue(points[1].converted)
        assertEquals("6.0", points[1].originalValue)
    }

    @Test
    fun `sugar status uses the fasting threshold when the reading is marked fasting`() {
        // 150 mg/dL is high fasting but unremarkable two hours after a meal — the same number
        // means different things, so the logged context has to drive the classification.
        val fasting = linesOf(reading(VitalCatalog.KEY_GLUCOSE, "150", unit = "mg/dL", context = "Fasting"))
        val postMeal = linesOf(reading(VitalCatalog.KEY_GLUCOSE, "150", unit = "mg/dL", context = "2h after meal"))

        assertEquals("High", fasting.getValue(VitalCatalog.TREND_BLOOD_SUGAR_HOME).dataPoints.single().status)
        assertEquals("Normal", postMeal.getValue(VitalCatalog.TREND_BLOOD_SUGAR_HOME).dataPoints.single().status)
    }

    // ── Separation from lab data ────────────────────────────────────────────

    @Test
    fun `every home trend name is distinct from the lab canonical name for the same quantity`() {
        // The whole separation rests on these never colliding: if a home line were ever named
        // "Blood Sugar", it would merge into the lab chart and present a fingerstick reading as a
        // venous plasma result.
        val homeNames = VitalCatalog.METRICS.flatMap { it.trendNames }
        for (name in homeNames) {
            assertTrue("$name must be marked as a home reading", name.endsWith("(Home)"))
            // A lab trend is only ever named by canonicalParamName / KEY_PARAMETER_ORDER, so a
            // home name landing in either would mean the two charts could merge.
            assertTrue("$name is also a lab key parameter", !DashboardEngine.isKeyParameter(name))
            assertEquals(
                "$name must not be claimed by a lab test panel",
                DashboardEngine.CATEGORY_OTHER, DashboardEngine.categoryOf(name)
            )
        }
        // And the bare quantity a home line measures IS a lab name — which is exactly why the
        // suffix has to be there. "Blood Sugar" is a real lab trend; "Blood Sugar (Home)" is not.
        assertTrue(DashboardEngine.isKeyParameter("Blood Sugar"))
    }

    @Test
    fun `every home point is marked as manually sourced and carries no report to open`() {
        val lines = linesOf(
            reading(VitalCatalog.KEY_BP, "120", value2 = "80", value3 = "70", unit = "mmHg"),
            reading(VitalCatalog.KEY_GLUCOSE, "99", unit = "mg/dL")
        )
        val allPoints = lines.values.flatMap { it.dataPoints }
        assertTrue(allPoints.isNotEmpty())
        assertTrue(allPoints.all { it.source == "manual" })
        assertTrue(allPoints.all { it.reportId.isEmpty() })
    }

    @Test
    fun `every home trend name maps back to the metric that produced it`() {
        for (metric in VitalCatalog.METRICS) {
            for (trendName in metric.trendNames) {
                assertEquals(metric.key, VitalCatalog.metricKeyForTrendName(trendName))
            }
        }
        assertNull(VitalCatalog.metricKeyForTrendName("Hemoglobin"))
    }

    @Test
    fun `no readings yields an explanatory summary rather than an empty crash`() {
        val summary = DashboardEngine.buildVitalsSummary("Ramesh", emptyList())
        assertTrue(summary.parameterTrends.isEmpty())
        assertTrue(summary.overallNarrative.isNotBlank())
    }

    @Test
    fun `the optional pulse guard reuses the standalone heart rate range`() {
        // Duplicating the range would let the two drift apart, so a pulse typed beside a BP
        // reading could be accepted where the same number typed on its own was rejected.
        assertNotNull(VitalCatalog.byKey(VitalCatalog.KEY_PULSE))
        assertEquals(VitalCatalog.byKey(VitalCatalog.KEY_PULSE)!!.plausibleRange, VitalCatalog.pulseRange)
    }
}
