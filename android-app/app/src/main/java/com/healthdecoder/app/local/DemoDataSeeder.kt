package com.healthdecoder.app.local

import android.content.Context
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.Medication
import com.healthdecoder.app.model.PendingTest
import com.healthdecoder.app.model.TestParameter
import com.healthdecoder.app.model.TestResults
import com.healthdecoder.app.model.VitalCatalog
import com.healthdecoder.app.model.VitalReading
import com.healthdecoder.app.reminder.AppointmentReminderManager
import com.healthdecoder.app.reminder.AppointmentSchedule
import com.healthdecoder.app.reminder.AppointmentStore
import com.healthdecoder.app.reminder.MedicineReminderManager
import com.healthdecoder.app.reminder.MedicineScheduleStore
import com.healthdecoder.app.ui.parseRoutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Seeds (and cleans up) a fake "Aisha (Demo)" patient with a couple of lab reports, a pending
 * test, a medicine reminder and a doctor appointment — so a brand-new install (a Play Store
 * tester, most likely) can see what a populated Records/Trends/Reminders/Doctor Brief actually
 * looks like without scanning a real document. See OnboardingScreen ("Try Demo") and
 * SettingsScreen ("Try Demo Data" card) for the two entry points.
 *
 * Everything here rides through the exact same write paths a real scan uses (LocalRepository /
 * LocalStore / MedicineScheduleStore / AppointmentStore) so it stays consistent with encryption,
 * indexing and reminder scheduling — nothing is hand-rolled.
 */
object DemoDataSeeder {

    /** Unmistakably fake, and can't collide with a real scanned patient's name. */
    const val DEMO_PATIENT_NAME = "Aisha (Demo)"
    private const val DEMO_RELATION = "Demo"
    private const val DEMO_EMOJI = "🧪"

    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun daysAgo(n: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -n)
        return isoDate.format(cal.time)
    }

    private fun daysFromNow(n: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, n)
        return isoDate.format(cal.time)
    }

    /** "created" a report at 9am on [dateIso] — only the relative ORDER matters (older report's
     *  createdAt must sort before the newer one's) so DashboardEngine treats the newer report's
     *  medicines as the current prescription. */
    private fun createdAtFor(dateIso: String): String = "${dateIso}T09:00:00.000Z"

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

    /** Quick check for whether the demo patient currently exists (drives the Account screen's
     *  "Add Demo Data" / "Remove Demo Data" button state). */
    suspend fun isDemoDataPresent(context: Context): Boolean = withContext(Dispatchers.IO) {
        AppSettings.getFamilyProfilesRaw(context)
            .any { it.name.trim().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
    }

    suspend fun seedDemoData(context: Context) = withContext(Dispatchers.IO) {
        // Family member — via the same path real "add a family member" uses, so name-uniqueness
        // etc. stays correct. No-ops harmlessly if the demo patient already exists.
        LocalRepository.addFamilyMember(
            context, DEMO_PATIENT_NAME, DEMO_RELATION, /* sex = */ "Female", /* dob = */ "", DEMO_EMOJI
        )

        val olderDate = daysAgo(60)
        val newerDate = daysAgo(5)

        // Report 1 (older): out-of-range Hemoglobin/Cholesterol, no medicines yet.
        val olderReport = MedicalReport(
            id = LocalStore.newId(),
            patientName = DEMO_PATIENT_NAME,
            reportDate = olderDate,
            reportType = "Lab Report",
            extractedText = "Sample demo report (Complete Blood Count + Lipid Profile + Fasting Blood Sugar) — for exploring the app before scanning a real document.",
            comments = "Annual health checkup — routine bloodwork.",
            medications = emptyList(),
            imagePath = "",
            imagePaths = emptyList(),
            sourceFiles = emptyList(),
            createdAt = createdAtFor(olderDate),
            testResults = TestResults(
                parameters = listOf(
                    TestParameter("Hemoglobin", "11.2", "g/dL", "13.0-17.0", "Low", "Hemoglobin", ""),
                    TestParameter("Total Cholesterol", "210", "mg/dL", "<200", "High", "Total Cholesterol", ""),
                    TestParameter("Fasting Blood Sugar", "98", "mg/dL", "70-100", "Normal", "Blood Sugar", "Fasting")
                ),
                findings = listOf(
                    "Mild anemia noted — hemoglobin below the reference range.",
                    "Cholesterol slightly elevated — dietary review suggested."
                )
            ),
            reportCategory = "blood_test",
            analyzed = true
        )

        // Report 2 (newer): same three parameters, all improved — plus the two medicines that
        // explain the improvement, telling a "getting better" demo story.
        val newerReport = MedicalReport(
            id = LocalStore.newId(),
            patientName = DEMO_PATIENT_NAME,
            reportDate = newerDate,
            reportType = "Lab Report",
            extractedText = "Sample demo report (Complete Blood Count + Lipid Profile + Fasting Blood Sugar) — for exploring the app before scanning a real document.",
            comments = "Follow-up bloodwork after two months on treatment.",
            medications = listOf(
                Medication(name = "Metformin", dosage = "500mg", frequency = "Twice daily"),
                Medication(name = "Atorvastatin", dosage = "10mg", frequency = "Once at night")
            ),
            imagePath = "",
            imagePaths = emptyList(),
            sourceFiles = emptyList(),
            createdAt = createdAtFor(newerDate),
            testResults = TestResults(
                parameters = listOf(
                    TestParameter("Hemoglobin", "12.8", "g/dL", "13.0-17.0", "Normal", "Hemoglobin", ""),
                    TestParameter("Total Cholesterol", "185", "mg/dL", "<200", "Normal", "Total Cholesterol", ""),
                    TestParameter("Fasting Blood Sugar", "94", "mg/dL", "70-100", "Normal", "Blood Sugar", "Fasting")
                ),
                findings = listOf(
                    "Hemoglobin back within the normal range.",
                    "Cholesterol improved with diet and medication."
                )
            ),
            reportCategory = "blood_test",
            analyzed = true
        )

        LocalStore.upsertReport(context, olderReport)
        LocalStore.upsertReport(context, newerReport)

        // Pending test
        LocalStore.upsertPendingTest(
            context,
            PendingTest(
                id = LocalStore.newId(),
                patientName = DEMO_PATIENT_NAME,
                testName = "HbA1c",
                dueDate = daysFromNow(21),
                status = "Pending",
                resolvedReportId = null,
                createdAt = nowIso()
            )
        )

        // Medicine reminders — mirrors TodaysMedicinesTab's auto-seed-on-scan behavior (frequency
        // text -> active time slots) so the reminder exists immediately, without needing the user
        // to open the Reminders tab first for the auto-seed LaunchedEffect to run.
        for (m in newerReport.medications) {
            val activeSlots = parseRoutine(m.frequency, m.dosage).filter { it.second }.map { it.first }
            MedicineScheduleStore.autoSeedIfAbsent(
                context, m.name, DEMO_PATIENT_NAME, m.dosage, m.frequency, activeSlots, emptyList()
            )
        }
        MedicineReminderManager.scheduleAll(context)

        // Doctor appointment
        AppointmentStore.upsert(
            context,
            AppointmentSchedule(
                doctorName = "Dr. Sample",
                date = daysFromNow(10),
                time = "10:30",
                place = "General Medicine OPD",
                isRecurring = false,
                recurrence = "None",
                hour = 10,
                minute = 30,
                patientName = DEMO_PATIENT_NAME
            )
        )
        AppointmentReminderManager.scheduleAll(context)

        // Home readings — without these, "Try Demo" showed the Add Reading tile and the Trends
        // screen's Home Readings mode completely empty, so the newest feature was the one part of
        // the app a prospective user could not actually see working.
        seedHomeReadings(context)

        // Focus Home on the demo patient right away — the whole point of "Try Demo" is seeing a
        // populated screen immediately, not making the user find the new profile in the picker.
        AppSettings.setActivePatient(context, DEMO_PATIENT_NAME)
    }

    /**
     * Three weeks of patient-logged home readings, written through the same [LocalStore] path the
     * Add Reading screen uses.
     *
     * Shaped to demonstrate what this feature does that the lab-report side structurally cannot:
     *  - BP is logged morning AND evening on the same days, so the chart shows two distinct points
     *    per day rather than one — the case that only works because a reading carries a clock time.
     *  - Every BP reading carries its pulse, as a real home cuff reports it, so the Pulse line is
     *    populated from BP entries rather than from standalone pulse logs.
     *  - Sugar alternates Fasting and post-meal, so the context filter chips have something to
     *    filter and the two thresholds visibly classify the same kind of number differently.
     *  - The BP trend drifts gently down over the three weeks, consistent with the demo patient
     *    having started on the medication in the newer prescription — the numbers tell the same
     *    story the rest of the demo data does, rather than being noise.
     *
     * Values are ordinary and unalarming on purpose: this is sample data a stranger will read as
     * if it were real, so it should not depict a medical emergency.
     */
    private fun seedHomeReadings(context: Context) {
        fun vital(
            metric: String, day: Int, time: String, value: String,
            value2: String = "", value3: String = "", unit: String, context_: String = ""
        ) = VitalReading(
            id = LocalStore.newId(),
            patientName = DEMO_PATIENT_NAME,
            metric = metric,
            value = value,
            value2 = value2,
            value3 = value3,
            unit = unit,
            context = context_,
            note = "",
            recordedAt = "${daysAgo(day)}T$time",
            createdAt = createdAtFor(daysAgo(day))
        )

        val readings = mutableListOf<VitalReading>()

        // BP twice a day, easing from ~142/88 down toward ~126/80 across three weeks.
        val bpDays = listOf(20, 18, 16, 14, 12, 10, 8, 6, 4, 2, 0)
        for ((i, day) in bpDays.withIndex()) {
            val ease = i.toFloat() / (bpDays.size - 1)          // 0.0 at the oldest, 1.0 today
            val morningSys = (142 - 16 * ease).toInt()
            val morningDia = (88 - 8 * ease).toInt()
            readings += vital(
                VitalCatalog.KEY_BP, day, "08:15",
                morningSys.toString(), morningDia.toString(), (78 - 6 * ease).toInt().toString(),
                unit = "mmHg", context_ = "Sitting"
            )
            // Evening runs a little lower, as it typically does.
            readings += vital(
                VitalCatalog.KEY_BP, day, "20:30",
                (morningSys - 5).toString(), (morningDia - 3).toString(), (74 - 5 * ease).toInt().toString(),
                unit = "mmHg", context_ = "After medicine"
            )
        }

        // Sugar: fasting most mornings, a post-meal reading on some afternoons.
        val sugarDays = listOf(19, 15, 11, 7, 3, 1)
        for ((i, day) in sugarDays.withIndex()) {
            val ease = i.toFloat() / (sugarDays.size - 1)
            readings += vital(
                VitalCatalog.KEY_GLUCOSE, day, "07:40",
                (124 - 14 * ease).toInt().toString(), unit = "mg/dL", context_ = "Fasting"
            )
            if (day % 2 == 1) {
                readings += vital(
                    VitalCatalog.KEY_GLUCOSE, day, "14:20",
                    (162 - 18 * ease).toInt().toString(), unit = "mg/dL", context_ = "2h after meal"
                )
            }
        }

        // A few oximeter readings, each with the pulse the same device reported.
        for (day in listOf(12, 5, 0)) {
            readings += vital(
                VitalCatalog.KEY_SPO2, day, "21:00", "97", value3 = "72",
                unit = "%", context_ = "At rest"
            )
        }

        // One standalone pulse, so the unioned Pulse line visibly draws from all three sources.
        readings += vital(VitalCatalog.KEY_PULSE, 9, "18:00", "76", unit = "bpm", context_ = "After activity")

        for (r in readings) LocalStore.upsertVital(context, r)
    }

    /** Removes everything scoped to the demo patient, and ONLY the demo patient — reports (via
     *  the same per-report delete real deletion uses, so files clean up too), the pending test,
     *  the medicine reminder (alarms cancelled first), the appointment (alarm cancelled first),
     *  and the family profile entry. Never touches any other patient's data. */
    suspend fun removeDemoData(context: Context) = withContext(Dispatchers.IO) {
        val reports = LocalStore.getReports(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (r in reports) LocalRepository.deleteReport(context, r.id)

        val pending = LocalStore.getPendingTests(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (p in pending) LocalRepository.deletePendingTest(context, p.id)

        // Home readings. Without this, removing the demo left its vitals behind: the profile and
        // reports would vanish while the Trends screen still charted three weeks of "Aisha (Demo)"
        // blood pressure, and familyMembers() does not re-seed from vitals, so the readings would
        // be stranded under a patient no longer in the picker.
        for (v in LocalStore.getVitals(context, DEMO_PATIENT_NAME)) LocalStore.deleteVital(context, v.id)

        val schedules = MedicineScheduleStore.loadAll(context)
            .filter { it.patientName.equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (s in schedules) MedicineScheduleStore.delete(context, s.medicineName, s.patientName)
        MedicineReminderManager.scheduleAll(context) // re-sync grouped alarms now that these are gone

        val appointments = AppointmentStore.loadAll(context)
            .filter { it.patientName.orEmpty().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        for (a in appointments) {
            AppointmentReminderManager.cancel(context, a.id)
            AppointmentStore.delete(context, a.id)
        }

        val profile = AppSettings.getFamilyProfilesRaw(context)
            .firstOrNull { it.name.trim().equals(DEMO_PATIENT_NAME, ignoreCase = true) }
        if (profile != null) LocalRepository.removeFamilyMember(context, profile.id)

        if (AppSettings.getActivePatient(context).equals(DEMO_PATIENT_NAME, ignoreCase = true)) {
            AppSettings.setActivePatient(context, null)
        }
    }
}
