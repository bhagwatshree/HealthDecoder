package com.example.medicalscanner.ai

import com.example.medicalscanner.model.*

/**
 * On-device port of the backend's /api/dashboard and /api/health-summary aggregation:
 * builds the medication tracker (current vs previous dosage, status), recent clinical
 * inferences, parameter trends, and the medication timeline.
 */
object DashboardEngine {

    // 80/20: the vital-few tests that cover most health signals, in display order.
    val KEY_PARAMETER_ORDER = listOf(
        "Blood Sugar (Fasting)", "Blood Sugar (PP)", "Blood Sugar (Random)", "Blood Sugar",
        "HbA1c", "TSH", "T3", "T4", "Hemoglobin", "WBC", "Platelets",
        "Total Cholesterol", "LDL", "HDL", "Triglycerides", "Creatinine",
        "Oxygen (SpO2)", "Ejection Fraction", "Vitamin D", "Vitamin B12"
    )

    fun isKeyParameter(name: String): Boolean = KEY_PARAMETER_ORDER.contains(name)

    /** Groups differently-worded lab names into one canonical trend name. */
    fun canonicalParamName(raw: String): String {
        val n = raw.lowercase()
        return when {
            n.contains("hba1c") || n.contains("glycated") || n.contains("glycosylated") -> "HbA1c"
            // Blood sugar: keep the test condition (fasting / post-meal / random) in the
            // canonical name so readings taken under different conditions don't plot as
            // one misleading line — a fasting 90 and a post-meal 160 are not comparable.
            n.contains("fbs") || n.contains("bsf") ||
                (n.contains("fasting") && (n.contains("sugar") || n.contains("glucose"))) -> "Blood Sugar (Fasting)"
            n.contains("ppbs") || n.contains("post prandial") || n.contains("postprandial") ||
                n.contains("post-meal") || n.contains("post meal") || n.contains("pp2") ||
                (n.contains("pp") && (n.contains("sugar") || n.contains("glucose"))) -> "Blood Sugar (PP)"
            n.contains("rbs") || n.contains("grbs") ||
                (n.contains("random") && (n.contains("sugar") || n.contains("glucose"))) -> "Blood Sugar (Random)"
            n.contains("glucose") || n.contains("sugar") -> "Blood Sugar"
            n.contains("tsh") || (n.contains("thyroid") && n.contains("stimulating")) -> "TSH"
            n == "t3" || n.contains("triiodo") -> "T3"
            n == "t4" || n.contains("thyroxine") -> "T4"
            // Exclude MCH / MCHC ("mean corpuscular hemoglobin…") which are separate CBC indices.
            (n.contains("hemoglobin") || n.contains("haemoglobin") || n == "hb" || n == "hgb") &&
                !n.contains("corpuscular") && !n.contains("mch") -> "Hemoglobin"
            n.contains("wbc") || n.contains("white blood") || n.contains("leucocyte") || n.contains("leukocyte") -> "WBC"
            // Exclude MPV / platelet distribution width, which aren't the platelet count.
            n.contains("platelet") && !n.contains("volume") && !n.contains("mpv") && !n.contains("distribution") -> "Platelets"
            n.contains("ldl") && !n.contains("vldl") -> "LDL"
            n.contains("hdl") -> "HDL"
            n.contains("triglyceride") -> "Triglycerides"
            n.contains("cholesterol") -> "Total Cholesterol"
            n.contains("creatinine") -> "Creatinine"
            n.contains("spo2") || n.contains("oxygen") || n.contains("saturation") -> "Oxygen (SpO2)"
            n.contains("ejection") || n == "ef" || n.contains("lvef") -> "Ejection Fraction"
            n.contains("vitamin d") || n.contains("25-oh") || n.contains("25 oh") -> "Vitamin D"
            n.contains("b12") || n.contains("cobalamin") -> "Vitamin B12"
            else -> raw.trim()
        }
    }

    private data class MedPoint(
        val reportId: String, val dosage: String, val frequency: String, val duration: String,
        val isOptional: Boolean, val weeklySchedule: List<String>, val notes: String, val date: String
    )

    fun buildDashboard(reports: List<MedicalReport>, pendingTests: List<PendingTest>): DashboardData {
        val medicationHistory = buildMedicationHistory(reports)

        val testInferences = reports
            .filter { it.comparisonResult?.hasComparison == true }
            .map {
                TestInference(
                    reportId = it.id,
                    patientName = it.patientName ?: "Unknown Patient",
                    reportDate = it.reportDate ?: "",
                    reportCategory = it.reportCategory ?: "",
                    summary = it.comparisonResult?.comparisonSummary ?: "",
                    status = it.comparisonResult?.status ?: ""
                )
            }
            .take(5)

        return DashboardData(
            reports = reports,
            pendingTests = pendingTests,
            medicationHistory = medicationHistory,
            testInferences = testInferences
        )
    }

    private fun buildMedicationHistory(reports: List<MedicalReport>): List<MedicationHistory> {
        // Chronological (oldest first) so we can detect dosage changes over time.
        val chrono = reports.sortedBy { it.reportDate ?: it.createdAt }
        val patientMed = mutableMapOf<String, MutableMap<String, MutableList<MedPoint>>>()
        val latestDate = mutableMapOf<String, String>()

        for (r in chrono) {
            // Only reports that carry medicines (prescriptions) can change medication
            // status. Lab/scan reports say nothing about medicines — without this guard,
            // scanning a newer blood report marked every medicine "Discontinued".
            if (r.medications.none { it.name.isNotBlank() }) continue
            val patient = r.patientName ?: "Unknown Patient"
            val date = r.reportDate ?: r.createdAt
            if ((latestDate[patient] ?: "") <= date) latestDate[patient] = date
            val medMap = patientMed.getOrPut(patient) { mutableMapOf() }
            for (m in r.medications) {
                if (m.name.isBlank()) continue
                medMap.getOrPut(m.name.trim()) { mutableListOf() }.add(
                    MedPoint(r.id, m.dosage.ifEmpty { "1 tablet" }, m.frequency, m.duration ?: "",
                        m.isOptional, m.weeklySchedule, m.notes ?: "", date)
                )
            }
        }

        val out = mutableListOf<MedicationHistory>()
        for ((patient, medMap) in patientMed) {
            val latest = latestDate[patient] ?: ""
            for ((medName, list) in medMap) {
                if (list.isEmpty()) continue
                val current = list.last()
                var previous: MedPoint? = null
                if (list.size > 1) {
                    for (k in list.size - 2 downTo 0) {
                        if (list[k].dosage != current.dosage || list[k].frequency != current.frequency) { previous = list[k]; break }
                    }
                    if (previous == null) previous = list[list.size - 2]
                }
                val isOmitted = current.date < latest
                val status = when {
                    isOmitted -> "Discontinued"
                    previous != null && (previous.dosage != current.dosage || previous.frequency != current.frequency) -> "Changed"
                    else -> "Active"
                }
                out.add(
                    MedicationHistory(
                        patientName = patient,
                        medicineName = medName,
                        currentDosage = current.dosage,
                        currentFrequency = current.frequency,
                        currentDuration = current.duration,
                        previousDosage = previous?.dosage ?: "",
                        previousFrequency = previous?.frequency ?: "",
                        status = status,
                        lastUpdated = current.date,
                        reportId = current.reportId,
                        isOptional = current.isOptional,
                        weeklySchedule = current.weeklySchedule,
                        notes = current.notes
                    )
                )
            }
        }
        return out
    }

    fun buildHealthSummary(patientName: String, reports: List<MedicalReport>): HealthSummary {
        if (reports.isEmpty()) {
            return HealthSummary("No reports found for this patient in the selected period.", emptyList(), emptyList(), emptyList())
        }
        val chrono = reports.sortedBy { it.reportDate ?: it.createdAt }

        // Parameter trends (canonicalised so the same test groups across reports).
        // Guard: at most ONE value per test per report, so a single report can't produce
        // multiple points on the same line (e.g. Hemoglobin vs MCH collapsing together).
        val paramMap = linkedMapOf<String, MutableList<TrendDataPoint>>()
        val seenPerReport = HashSet<String>()
        for (r in chrono) {
            val date = (r.reportDate ?: r.createdAt).split("T")[0]
            for (p in r.testResults?.parameters ?: emptyList()) {
                if (p.name.isNullOrBlank()) continue
                val canon = canonicalParamName(p.name)
                if (!seenPerReport.add("$canon|${r.id}")) continue // already have this test for this report
                paramMap.getOrPut(canon) { mutableListOf() }.add(
                    TrendDataPoint(date, p.value, p.unit, p.status ?: "", r.id)
                )
            }
        }
        val trends = paramMap.map { (name, points) ->
            var trend = "stable"
            val numeric = points.filter { it.value.toFloatOrNull() != null }
            if (numeric.size >= 2) {
                val first = numeric.first().value.toFloat(); val last = numeric.last().value.toFloat()
                val ls = numeric.last().status.lowercase(); val ps = numeric.first().status.lowercase()
                trend = when {
                    ls == "normal" && ps != "normal" -> "improving"
                    ls != "normal" && ps == "normal" -> "worsening"
                    Math.abs(last - first) / (Math.abs(first).takeIf { it != 0f } ?: 1f) < 0.05f -> "stable"
                    last < first -> "decreasing"
                    else -> "increasing"
                }
            }
            ParameterTrend(name, points, trend)
        }.sortedWith(compareBy(
            { if (isKeyParameter(it.name)) 0 else 1 },               // key tests first
            { KEY_PARAMETER_ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { it.name }
        ))

        // Medication timeline — built only from reports that carry medicines, so a lab
        // report between two prescriptions doesn't show every medicine as "removed".
        val timeline = mutableListOf<MedicationTimelineEntry>()
        var prev = mapOf<String, Medication>()
        for (r in chrono.filter { rep -> rep.medications.any { it.name.isNotBlank() } }) {
            val date = (r.reportDate ?: r.createdAt).split("T")[0]
            val cur = r.medications.associateBy { it.name }
            val added = r.medications.filter { !prev.containsKey(it.name) }.map { it.name }
            val removed = prev.keys.filter { !cur.containsKey(it) }
            val changed = r.medications.filter { prev[it.name]?.let { p -> p.dosage != it.dosage || p.frequency != it.frequency } == true }
                .map { "${it.name} (${prev[it.name]?.dosage} → ${it.dosage})" }
            if (added.isNotEmpty() || removed.isNotEmpty() || changed.isNotEmpty() || timeline.isEmpty()) {
                timeline.add(MedicationTimelineEntry(date, r.id, r.reportCategory, added, removed, changed,
                    r.medications.map { Medication(it.name, it.dosage, it.frequency) }))
            }
            prev = cur
        }

        // Active flags
        val flags = mutableListOf<String>()
        for (r in reports.reversed()) {
            r.healthInsights?.prescriptionAlignment?.flags?.forEach { if (it !in flags) flags.add(it) }
            if (flags.size >= 5) break
        }

        val worsening = trends.filter { it.trend == "worsening" }
        val improving = trends.filter { it.trend == "improving" }
        val activeMeds = timeline.lastOrNull()?.activeMedicines?.joinToString { it.name } ?: ""
        val narrative = buildString {
            append("${reports.size} report(s) recorded for $patientName. ")
            if (improving.isNotEmpty()) append("${improving.joinToString { it.name }} improving. ")
            if (worsening.isNotEmpty()) append("${worsening.joinToString { it.name }} worsening — consult your doctor. ")
            if (improving.isEmpty() && worsening.isEmpty() && trends.isNotEmpty()) append("Parameters remain stable. ")
            if (activeMeds.isNotEmpty()) append("Active medicines: $activeMeds.")
        }

        return HealthSummary(narrative, trends, timeline, flags)
    }
}
