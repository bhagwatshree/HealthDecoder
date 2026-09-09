package com.healthdecoder.app.ai

import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extracted medicines are turned into medication reminders — alarms telling a patient to take a
 * specific dose. If the extractor could not read a dose and supplied its best guess instead, that
 * guess becomes the alarm, and nothing downstream can tell it from a correctly read value.
 *
 * These tests pin the two halves of the defence: an unconfidently-read medicine must stay marked
 * as such all the way to the reminder layer, and no default may be substituted into the field that
 * could not be read.
 */
class UncertainMedicationTest {

    private fun report(id: String, date: String, meds: List<Medication>) = MedicalReport(
        id = id,
        patientName = "Ramesh",
        reportDate = date,
        reportType = "Prescription",
        extractedText = "",
        comments = "",
        medications = meds,
        imagePath = "",
        createdAt = "${date}T10:00:00.000Z",
        reportCategory = "prescription"
    )

    private fun historyFor(meds: List<Medication>) =
        DashboardEngine.buildDashboard(
            listOf(report("r1", "2026-09-01", meds)), emptyList()
        ).medicationHistory

    @Test
    fun `an uncertain medicine reaches the reminder layer still marked uncertain`() {
        val history = historyFor(listOf(
            Medication(name = "Concor", dosage = "", frequency = "Once daily",
                uncertain = true, uncertainReason = "dosage illegible — handwriting unclear")
        ))

        val med = history.single { it.medicineName.contains("Concor", ignoreCase = true) }
        assertTrue("the uncertainty flag was lost between extraction and the reminder layer", med.uncertain)
        assertTrue(med.uncertainReason.isNotBlank())
    }

    @Test
    fun `an illegible dose is NOT backfilled with the default dose`() {
        // The "1 tablet" default exists for a prescription that never stated a dose at all. Using
        // it for a dose that was printed but unreadable would invent a clinical instruction —
        // turning "we could not read this" into "take 1 tablet".
        val history = historyFor(listOf(
            Medication(name = "Warfarin", dosage = "", frequency = "Once daily",
                uncertain = true, uncertainReason = "dosage illegible")
        ))

        assertEquals("", history.single { it.medicineName.contains("Warfarin", true) }.currentDosage)
    }

    @Test
    fun `a medicine that simply never stated a dose still gets the ordinary default`() {
        // The guard above must not regress the normal case it sits next to.
        val history = historyFor(listOf(
            Medication(name = "Vitamin D3", dosage = "", frequency = "Once weekly")
        ))

        assertEquals("1 tablet", history.single { it.medicineName.contains("Vitamin D3", true) }.currentDosage)
    }

    @Test
    fun `a confidently read medicine is not flagged`() {
        val history = historyFor(listOf(
            Medication(name = "Metformin", dosage = "500mg", frequency = "Twice daily")
        ))

        val med = history.single { it.medicineName.contains("Metformin", true) }
        assertFalse(med.uncertain)
        assertEquals("500mg", med.currentDosage)
    }

    @Test
    fun `reports saved before the flag existed are treated as confidently read`() {
        // Gson leaves absent fields null regardless of Kotlin defaults, so an older stored report
        // arrives with uncertain = null. Null must mean "not flagged", never "unknown, so block it"
        // — otherwise upgrading the app would silently stop every existing reminder.
        val legacy = Medication(name = "Amlodipine", dosage = "5mg", frequency = "Once daily",
            uncertain = null)

        val med = historyFor(listOf(legacy)).single { it.medicineName.contains("Amlodipine", true) }
        assertFalse("a pre-existing medicine must not become uncertain on upgrade", med.uncertain)
    }

    @Test
    fun `the extraction prompt forbids guessing a clinical value`() {
        // The model-facing contract is the first line of this defence, and it is a plain string —
        // easy to soften or lose in an unrelated prompt edit.
        val prompt = java.io.File(
            "src/main/java/com/healthdecoder/app/ai/OcrEngine.kt"
        ).readText()

        assertTrue(
            "the prompt no longer tells the model never to guess a clinical value",
            prompt.contains("NEVER GUESS A CLINICAL VALUE")
        )
        assertFalse(
            "the prompt still asks for a best interpretation of illegible text",
            prompt.contains("transcribe your best interpretation")
        )
    }
}
