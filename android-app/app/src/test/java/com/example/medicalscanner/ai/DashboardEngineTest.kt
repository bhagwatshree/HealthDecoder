package com.example.medicalscanner.ai

import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardEngineTest {

    private fun report(
        id: String,
        date: String,
        meds: List<Medication> = emptyList(),
        category: String = if (meds.isEmpty()) "blood_test" else "prescription"
    ) = MedicalReport(
        id = id,
        patientName = "Ramesh",
        reportDate = date,
        reportType = if (meds.isEmpty()) "Lab Report" else "Prescription",
        extractedText = "",
        comments = "",
        medications = meds,
        imagePath = "",
        createdAt = "${date}T10:00:00.000Z",
        reportCategory = category
    )

    private fun statusOf(reports: List<MedicalReport>, medicine: String): String =
        DashboardEngine.buildDashboard(reports, emptyList())
            .medicationHistory.first { it.medicineName == medicine }.status

    @Test
    fun `newer lab report does not discontinue medicines`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("lab1", "2026-06-01") // blood report, no medicines
        )
        assertEquals("Active", statusOf(reports, "Metformin"))
    }

    @Test
    fun `medicine omitted from a newer prescription is discontinued`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(
                Medication("Metformin", "500mg", "Twice daily"),
                Medication("Atorvastatin", "10mg", "At night")
            )),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily")))
        )
        assertEquals("Discontinued", statusOf(reports, "Atorvastatin"))
        assertEquals("Active", statusOf(reports, "Metformin"))
    }

    @Test
    fun `dosage change in a newer prescription shows changed`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "1000mg", "Twice daily")))
        )
        assertEquals("Changed", statusOf(reports, "Metformin"))
    }

    @Test
    fun `lab report between prescriptions does not mark medicines removed in timeline`() {
        val reports = listOf(
            report("rx1", "2026-05-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily"))),
            report("lab1", "2026-05-15"),
            report("rx2", "2026-06-01", meds = listOf(Medication("Metformin", "500mg", "Twice daily")))
        )
        val summary = DashboardEngine.buildHealthSummary("Ramesh", reports)
        val allRemoved = summary.medicationTimeline.flatMap { it.removed }
        assertEquals(emptyList<String>(), allRemoved)
        assertEquals(listOf("Metformin"), summary.medicationTimeline.last().activeMedicines.map { it.name })
    }
}
