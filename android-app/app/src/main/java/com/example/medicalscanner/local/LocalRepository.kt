package com.example.medicalscanner.local

import android.content.Context
import com.example.medicalscanner.ai.DashboardEngine
import com.example.medicalscanner.ai.DateResolver
import com.example.medicalscanner.ai.MedicalEngine
import com.example.medicalscanner.ai.OcrEngine
import com.example.medicalscanner.ai.ScanExtraction
import com.example.medicalscanner.ai.UnitConverter
import com.example.medicalscanner.backup.BackupManager
import com.example.medicalscanner.backup.BackupSync
import com.example.medicalscanner.backup.ExportManager
import com.example.medicalscanner.reminder.MedicineReminderManager
import com.example.medicalscanner.reminder.MedicineScheduleStore
import com.example.medicalscanner.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thrown when a scan is recognized as a report that is already saved, so it is not
 * added again. [existing] is the report it duplicates.
 */
class DuplicateReportException(val existing: MedicalReport) : Exception(
    "Duplicate of report ${existing.id} (${existing.patientName}, ${existing.reportDate})"
)

/**
 * The single on-device data source the UI talks to. Replaces the Retrofit/PC-server API:
 * it stores records locally, runs the AI/aggregation engines on-device, and makes a local
 * backup after every change (which BackupSync later pushes to the cloud when online).
 * All methods are suspend + run on Dispatchers.IO.
 */
object LocalRepository {

    private val gson = Gson()
    private val isoDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun today() = isoDate.format(Date())
    private fun nowIso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())

    private fun afterWrite(context: Context) {
        runCatching { BackupManager.createLocalBackup(context) }
        runCatching { BackupSync.syncPending(context) }
    }

    /** Wipes ALL on-device data (records, images, pending tests, logs, cached analysis). */
    suspend fun clearAllData(context: Context) = withContext(Dispatchers.IO) {
        LocalStore.closeDatabase() // release the SQLite file before deleting it
        val dir = LocalStore.recordsDir(context)
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // ── Reports ───────────────────────────────────────────────────────────────
    suspend fun getReports(context: Context): List<MedicalReport> = withContext(Dispatchers.IO) {
        LocalStore.getReports(context)
    }

    suspend fun getReport(context: Context, id: String): MedicalReport? = withContext(Dispatchers.IO) {
        LocalStore.getReport(context, id)
    }

    /** IDs of reports matching the query via full-text search (patient, type, comments, OCR text). */
    suspend fun searchReportIds(context: Context, query: String): Set<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptySet()
        else LocalStore.searchReports(context, query).map { it.id }.toSet()
    }

    suspend fun deleteReport(context: Context, id: String) = withContext(Dispatchers.IO) {
        LocalStore.deleteReport(context, id)
        detailedCacheFile(context, id).delete()
        afterWrite(context)
    }

    /** Already-stored duplicate reports (newer copies of an earlier report). */
    suspend fun findDuplicateReports(context: Context): List<MedicalReport> = withContext(Dispatchers.IO) {
        LocalStore.findStoredDuplicates(context)
    }

    /** Deletes all stored duplicate reports, keeping the original of each group. Returns how many were removed. */
    suspend fun deleteDuplicateReports(context: Context): Int = withContext(Dispatchers.IO) {
        val duplicates = LocalStore.findStoredDuplicates(context)
        for (dup in duplicates) {
            LocalStore.deleteReport(context, dup.id)
            detailedCacheFile(context, dup.id).delete()
        }
        if (duplicates.isNotEmpty()) afterWrite(context)
        duplicates.size
    }

    /**
     * Scans one or more page images and saves them on-device. The pages may bundle
     * SEVERAL distinct reports (e.g. CBC + lipid profile + 2D Echo): each becomes its
     * own record with its own name and its own date, resolved by [DateResolver] from the
     * labeled dates on the page (Reported date for sample labs, Procedure date for
     * scans/echo, visit date for prescriptions — never the printed date).
     *
     * Duplicate protection: an exact re-import of the same file/photo is rejected before
     * the AI extraction runs; a re-scan of the same paper (same patient, date, category
     * with near-identical text) is skipped per section. If EVERYTHING was already saved,
     * [DuplicateReportException] is thrown and nothing is stored.
     *
     * @param sources original imported files (bytes, name, mime) preserved so the user can download them.
     * @return the saved reports, one per distinct report found in the scan.
     */
    suspend fun saveScan(
        context: Context,
        pages: List<Pair<ByteArray, String>>,
        sources: List<Triple<ByteArray, String, String>>,
        localOcrText: String,
        scanType: String,
        reportCategory: String,
        patientNameOverride: String = ""
    ): List<MedicalReport> = withContext(Dispatchers.IO) {
        // Stage 1: exact duplicate — the same photo/file bytes were saved before.
        val incomingHashes = (pages.map { it.first } + sources.map { it.first })
            .map { LocalStore.sha256(it) }
            .distinct()
        LocalStore.findReportByAnyHash(context, incomingHashes)?.let { throw DuplicateReportException(it) }

        val extraction = OcrEngine.scan(context, pages, localOcrText, scanType, reportCategory)
        val sections = extraction.reports.ifEmpty { listOf(extraction.merged()) }
        val patientName = patientNameOverride.trim()
            .ifBlank { extraction.patientName ?: sections.firstOrNull()?.patientName ?: "Unknown Patient" }

        // The scanned page images and original files are stored ONCE and shared by every
        // report in the bundle (deletion is reference-aware, see LocalStore.deleteReport).
        val bundleId = LocalStore.newId()
        val imagePaths = pages.mapIndexed { index, (bytes, _) ->
            LocalStore.saveImage(context, if (index == 0) bundleId else "${bundleId}_$index", bytes)
        }
        val imagePath = imagePaths.firstOrNull() ?: ""
        val sourceFiles = sources.mapIndexed { index, (bytes, name, mime) ->
            SourceFile(
                path = LocalStore.saveSourceFile(context, bundleId, index, name, bytes),
                name = name,
                mimeType = mime
            )
        }

        val saved = mutableListOf<MedicalReport>()
        var firstDuplicate: MedicalReport? = null
        // Bulk imports: run comparison/insights locally instead of per-report AI calls,
        // otherwise a many-report scan bursts past the free tier's requests-per-minute cap.
        val allowPerReportAi = sections.size <= 2

        for ((index, section) in sections.withIndex()) {
            // The date on THIS report's page, chosen by label priority and sanity checked.
            val reportDate = DateResolver.resolve(section, reportCategory) ?: today()
            val sectionType = section.reportName?.takeIf { it.isNotBlank() }
                ?: section.reportType
                ?: if (scanType == "prescription") "Prescription" else "Other"
            val category = if (reportCategory.isBlank())
                (if (sectionType == "Prescription") "prescription" else "other") else reportCategory
            val sectionText = section.rawText?.takeIf { it.isNotBlank() } ?: extraction.rawText ?: ""

            // Stage 2: this individual report was already saved from an earlier scan.
            val dup = LocalStore.findContentDuplicate(context, patientName, reportDate, category, sectionText)
            if (dup != null) {
                if (firstDuplicate == null) firstDuplicate = dup
                continue
            }

            var report = MedicalReport(
                id = if (index == 0) bundleId else LocalStore.newId(),
                patientName = patientName,
                reportDate = reportDate,
                reportType = sectionType,
                extractedText = sectionText,
                comments = section.comments ?: "",
                medications = dedupeMedications(section.medications),
                imagePath = imagePath,
                imagePaths = imagePaths,
                sourceFiles = sourceFiles,
                createdAt = nowIso(),
                testResults = section.testResults ?: TestResults(),
                comparisonResult = null,
                reportCategory = category,
                healthInsights = null,
                pageHashes = incomingHashes
            )

            val previous = findPrevious(context, patientName, category, reportDate, excludeId = report.id)
            val comparison = MedicalEngine.compareReports(context, report, previous, allowAi = allowPerReportAi)
            val insights = MedicalEngine.healthInsights(context, report, allowAi = allowPerReportAi)
            report = report.copy(comparisonResult = comparison, healthInsights = insights)

            LocalStore.upsertReport(context, report)

            // Auto-add recommended tests, then auto-resolve matching pending tests.
            for (t in section.recommendedTests) {
                if (t.testName.isNotBlank()) {
                    LocalStore.upsertPendingTest(context, PendingTest(
                        id = LocalStore.newId(), patientName = patientName,
                        testName = t.testName, dueDate = t.dueDate, status = "Pending",
                        resolvedReportId = null, createdAt = nowIso()
                    ))
                }
            }
            autoResolvePending(context, report)
            saved.add(report)
        }

        if (saved.isEmpty()) {
            // Every report in this scan already exists — clean up the files we stored.
            (imagePaths + sourceFiles.map { it.path }).forEach { runCatching { File(it).delete() } }
            throw DuplicateReportException(firstDuplicate ?: LocalStore.getReports(context).first())
        }

        afterWrite(context)
        saved
    }

    suspend fun updateReport(context: Context, id: String, req: ReportUpdateRequest): MedicalReport? = withContext(Dispatchers.IO) {
        val existing = LocalStore.getReport(context, id) ?: return@withContext null
        val category = req.reportCategory ?: if (req.reportType == "Prescription") "prescription" else (existing.reportCategory ?: "other")
        var updated = existing.copy(
            patientName = req.patientName,
            reportDate = req.reportDate,
            reportType = req.reportType,
            comments = req.comments,
            medications = dedupeMedications(req.medications),
            extractedText = req.extractedText,
            testResults = req.testResults ?: existing.testResults,
            reportCategory = category
        )
        val previous = findPrevious(context, updated.patientName, category, updated.reportDate ?: today(), excludeId = id)
        val comparison = MedicalEngine.compareReports(context, updated, previous)
        val insights = MedicalEngine.healthInsights(context, updated)
        updated = updated.copy(comparisonResult = comparison, healthInsights = insights)
        LocalStore.upsertReport(context, updated)
        detailedCacheFile(context, id).delete() // invalidate cached detailed analysis
        afterWrite(context)
        updated
    }

    /**
     * Stores a report's file(s) WITHOUT running any AI analysis — the "upload only" path, for
     * archiving old records without spending API calls. Creates one report per upload, flagged
     * [MedicalReport.analyzed] = false so the detail screen can offer to analyze it later (via
     * [reprocessReport], which reads these same stored images). Exact-duplicate uploads (same
     * bytes) are rejected. Patient name / date / category come from the user since nothing is
     * extracted; a blank name falls back to "Unknown Patient".
     */
    suspend fun saveUploadOnly(
        context: Context,
        pages: List<Pair<ByteArray, String>>,
        sources: List<Triple<ByteArray, String, String>>,
        reportCategory: String,
        patientNameOverride: String = "",
        reportDate: String? = null
    ): MedicalReport = withContext(Dispatchers.IO) {
        val incomingHashes = (pages.map { it.first } + sources.map { it.first })
            .map { LocalStore.sha256(it) }.distinct()
        LocalStore.findReportByAnyHash(context, incomingHashes)?.let { throw DuplicateReportException(it) }

        val bundleId = LocalStore.newId()
        val imagePaths = pages.mapIndexed { index, (bytes, _) ->
            LocalStore.saveImage(context, if (index == 0) bundleId else "${bundleId}_$index", bytes)
        }
        val sourceFiles = sources.mapIndexed { index, (bytes, name, mime) ->
            SourceFile(path = LocalStore.saveSourceFile(context, bundleId, index, name, bytes), name = name, mimeType = mime)
        }
        val category = reportCategory.ifBlank { "other" }
        val report = MedicalReport(
            id = bundleId,
            patientName = patientNameOverride.trim().ifBlank { "Unknown Patient" },
            reportDate = reportDate?.takeIf { it.isNotBlank() } ?: today(),
            reportType = if (category == "prescription") "Prescription" else "Uploaded",
            extractedText = "",
            comments = "",
            medications = emptyList(),
            imagePath = imagePaths.firstOrNull() ?: "",
            imagePaths = imagePaths,
            sourceFiles = sourceFiles,
            createdAt = nowIso(),
            testResults = TestResults(),
            comparisonResult = null,
            reportCategory = category,
            healthInsights = null,
            pageHashes = incomingHashes,
            analyzed = false
        )
        LocalStore.upsertReport(context, report)
        afterWrite(context)
        report
    }

    /**
     * Re-runs OCR/AI extraction on a report's originally scanned image(s) and refreshes its
     * test parameters, medications and insights. Serves two cases: a first scan that came back
     * incomplete (e.g. the AI API was briefly unavailable), and analyzing an "upload only" report
     * on demand — either way it marks the report [MedicalReport.analyzed] = true.
     */
    suspend fun reprocessReport(context: Context, id: String): MedicalReport? = withContext(Dispatchers.IO) {
        val existing = LocalStore.getReport(context, id) ?: return@withContext null
        val pages = existing.imagePaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            file.readBytes() to mimeForPath(path)
        }
        if (pages.isEmpty()) return@withContext existing

        val category = existing.reportCategory ?: "other"
        val scanType = if (category == "prescription" || existing.reportType == "Prescription") "prescription" else "report"
        val extraction = OcrEngine.scan(context, pages, "", scanType, category)
        val sections = extraction.reports.ifEmpty { listOf(extraction.merged()) }
        val section = sections.firstOrNull { DateResolver.resolve(it, category) == existing.reportDate } ?: sections.first()

        // Parameters/medications aren't user-editable today, so overwriting them is safe;
        // comments/raw text ARE user-editable, so only fill those in if still blank.
        var updated = existing.copy(
            testResults = section.testResults ?: existing.testResults,
            medications = dedupeMedications(section.medications.ifEmpty { existing.medications }),
            comments = existing.comments?.takeIf { it.isNotBlank() } ?: section.comments,
            extractedText = existing.extractedText?.takeIf { it.isNotBlank() } ?: section.rawText,
            // An upload-only report becomes a full, analyzed report once this succeeds. For a
            // report the AI detected a type/date for, adopt those too if the upload had placeholders.
            analyzed = true,
            reportType = if (existing.reportType == "Uploaded") (section.reportName?.takeIf { it.isNotBlank() } ?: section.reportType ?: existing.reportType) else existing.reportType
        )
        val previous = findPrevious(context, updated.patientName, category, updated.reportDate ?: today(), excludeId = id)
        val comparison = MedicalEngine.compareReports(context, updated, previous)
        val insights = MedicalEngine.healthInsights(context, updated)
        updated = updated.copy(comparisonResult = comparison, healthInsights = insights)
        LocalStore.upsertReport(context, updated)
        detailedCacheFile(context, id).delete() // invalidate cached detailed analysis
        afterWrite(context)
        updated
    }

    private fun mimeForPath(path: String): String = when {
        path.endsWith(".png", true) -> "image/png"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".pdf", true) -> "application/pdf"
        else -> "image/jpeg"
    }

    private fun findPrevious(context: Context, patient: String?, category: String, date: String, excludeId: String): MedicalReport? =
        LocalStore.findPreviousReport(context, patient, category, date, excludeId)

    /** Drops repeated medicines (same name ignoring case/extra spaces), keeping the first. */
    private fun dedupeMedications(meds: List<Medication>): List<Medication> {
        val seen = HashSet<String>()
        return meds.filter { m ->
            val key = m.name.trim().lowercase().replace(Regex("\\s+"), " ")
            key.isNotBlank() && seen.add(key)
        }
    }

    private fun autoResolvePending(context: Context, report: MedicalReport) {
        val raw = (report.extractedText ?: "").lowercase()
        val comments = (report.comments ?: "").lowercase()
        val type = (report.reportType ?: "").lowercase()
        for (pt in LocalStore.getPendingTests(context)) {
            if (pt.patientName != report.patientName || pt.status != "Pending") continue
            val clean = pt.testName.lowercase().replace(Regex("test|profile|check"), "").trim()
            if (clean.length > 2 && (raw.contains(clean) || comments.contains(clean) || type.contains(clean))) {
                LocalStore.upsertPendingTest(context, pt.copy(status = "Completed", resolvedReportId = report.id))
            }
        }
    }

    // ── Portable export / import ────────────────────────────────────────────
    /** The distinct patient names in the current account, most-reports-first (for export UI). */
    suspend fun listPatients(context: Context): List<String> = withContext(Dispatchers.IO) {
        LocalStore.getReports(context).mapNotNull { it.patientName?.takeIf { n -> n.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * Writes a portable export zip. [patientName] null = all patients. [onlySinceLastExport] true =
     * only reports created after the last export (delta). Returns the file to share, or null if the
     * selection is empty. On success advances the delta marker to the newest report exported.
     */
    suspend fun exportData(
        context: Context,
        patientName: String?,
        onlySinceLastExport: Boolean
    ): java.io.File? = withContext(Dispatchers.IO) {
        val since = if (onlySinceLastExport) AppSettings.getLastExportAt(context) else null
        val selected = LocalStore.getReports(context).filter { r ->
            (patientName == null || r.patientName.equals(patientName, ignoreCase = true)) &&
                (since == null || r.createdAt > since)
        }
        if (selected.isEmpty()) return@withContext null
        val file = ExportManager.export(context, selected, since, patientName)
        // Advance the delta marker only for a full (all-patients) export — a single-patient export
        // must not move the global "since last time" cutoff and cause other patients to be skipped.
        if (patientName == null) {
            selected.maxByOrNull { it.createdAt }?.createdAt?.let { AppSettings.setLastExportAt(context, it) }
        }
        file
    }

    /** Merges a portable export into this device. No AI is run — analysis rides inside the file. */
    suspend fun importData(context: Context, uri: android.net.Uri): ExportManager.ImportResult =
        withContext(Dispatchers.IO) {
            val result = ExportManager.import(context, uri)
            if (result.imported > 0) afterWrite(context)
            result
        }

    // ── Dashboard / summary ─────────────────────────────────────────────────
    suspend fun getDashboard(context: Context, period: String?): DashboardData = withContext(Dispatchers.IO) {
        val reports = filterByPeriod(LocalStore.getReports(context), period)
        DashboardEngine.buildDashboard(reports, LocalStore.getPendingTests(context))
    }

    suspend fun getHealthSummary(context: Context, patientName: String, period: String?): HealthSummary = withContext(Dispatchers.IO) {
        // Standard unit per test: for any test UnitConverter knows, it's fixed by the user's
        // unit-system setting (Conventional/Indian by default, or SI) so it's consistent across
        // patients and independent of which report was scanned first. For tests it doesn't know
        // (no conversion factor anyway), fall back to the first-seen unit — locked so it survives
        // deleting/filtering the report it first came from.
        val all = LocalStore.getReports(context).filter { it.patientName.equals(patientName, true) }
        val system = AppSettings.getUnitSystemEnum(context)
        val derived = DashboardEngine.resolveStandardUnits(all)
        derived.forEach { (canon, unit) ->
            AppSettings.lockTrendStandardUnitIfAbsent(context, "$patientName|$canon", unit)
        }
        val locked = AppSettings.getTrendStandardUnits(context)
        val standardUnits = derived.keys.associateWith { canon ->
            UnitConverter.standardUnitFor(canon, system)
                ?: locked["$patientName|$canon"]
                ?: derived.getValue(canon)
        }
        val reports = filterByPeriod(all, period)
        DashboardEngine.buildHealthSummary(patientName, reports, standardUnits)
    }

    private fun filterByPeriod(reports: List<MedicalReport>, period: String?): List<MedicalReport> {
        if (period == null || period == "all") return reports
        val months = mapOf("1m" to 1, "3m" to 3, "6m" to 6, "1y" to 12, "2y" to 24)[period] ?: return reports
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -months) }
        val cutoff = isoDate.format(cal.time)
        return reports.filter { (it.reportDate ?: it.createdAt) >= cutoff }
    }

    // ── Pending tests ─────────────────────────────────────────────────────────
    suspend fun createPendingTest(context: Context, patientName: String, testName: String, dueDate: String?): PendingTest = withContext(Dispatchers.IO) {
        val pt = PendingTest(LocalStore.newId(), patientName, testName, dueDate?.ifBlank { null }, "Pending", null, nowIso())
        LocalStore.upsertPendingTest(context, pt); afterWrite(context); pt
    }

    suspend fun deletePendingTest(context: Context, id: String) = withContext(Dispatchers.IO) {
        LocalStore.deletePendingTest(context, id); afterWrite(context)
    }

    // ── Medication logs & edits ────────────────────────────────────────────────
    suspend fun logMedicationIntake(context: Context, patientName: String, medicineName: String, actionType: String, frequency: String?) = withContext(Dispatchers.IO) {
        LocalStore.addMedLog(context, MedLogEntry(LocalStore.newId(), patientName, medicineName, actionType, frequency, null, nowIso()))
        afterWrite(context)
    }

    suspend fun getMedicationLogs(context: Context, patientName: String, medicineName: String): List<MedLogEntry> = withContext(Dispatchers.IO) {
        LocalStore.getMedLogs(context, patientName, medicineName)
    }

    suspend fun updateMedicationDetails(context: Context, reportId: String, medicineName: String, patientName: String,
                                        dosage: String?, frequency: String?, duration: String?, isOptional: Boolean?, weeklySchedule: List<String>?, notes: String?) = withContext(Dispatchers.IO) {
        val report = LocalStore.getReport(context, reportId) ?: return@withContext
        val meds = report.medications.toMutableList()
        var found = false
        for (i in meds.indices) {
            if (meds[i].name.trim().equals(medicineName.trim(), true)) {
                meds[i] = meds[i].copy(
                    dosage = dosage ?: meds[i].dosage, frequency = frequency ?: meds[i].frequency,
                    duration = duration ?: meds[i].duration, isOptional = isOptional ?: meds[i].isOptional,
                    weeklySchedule = weeklySchedule ?: meds[i].weeklySchedule, notes = notes ?: meds[i].notes)
                found = true
            }
        }
        if (!found) meds.add(Medication(medicineName, dosage ?: "", frequency ?: "", duration ?: "", isOptional ?: false, weeklySchedule ?: listOf("Everyday"), notes ?: ""))
        LocalStore.upsertReport(context, report.copy(medications = meds))
        LocalStore.addMedLog(context, MedLogEntry(LocalStore.newId(), patientName, medicineName, "UPDATE_DETAILS", frequency, "Dosage: ${dosage ?: ""}", nowIso()))
        afterWrite(context)
    }

    /**
     * Corrects a medicine (e.g. a name mis-read from a handwritten prescription) and propagates
     * the fix everywhere it lives for that patient, so the user only edits it once:
     *
     *  - **Name**: renamed in EVERY report of the patient that carries the old name (a repeated
     *    mis-scan is fixed in one action), and the reminder schedule + intake logs are re-keyed to
     *    the new name so neither orphans.
     *  - **Dosage / frequency / duration / schedule / notes**: applied to the specific [reportId]
     *    instance only, so the per-report medication timeline (dosage changes over time) stays
     *    intact; the reminder schedule's shown dosage/frequency is refreshed to match.
     *
     * The medication tracker/history is derived from reports, so it updates automatically.
     */
    suspend fun updateMedicineEverywhere(
        context: Context, reportId: String, patientName: String, oldName: String, newName: String,
        dosage: String?, frequency: String?, duration: String?, isOptional: Boolean?,
        weeklySchedule: List<String>?, notes: String?
    ) = withContext(Dispatchers.IO) {
        val from = oldName.trim()
        val to = newName.trim().ifBlank { from }
        val nameChanged = !to.equals(from, ignoreCase = true)

        val patientReports = LocalStore.getReports(context)
            .filter { it.patientName.equals(patientName, ignoreCase = true) }
        for (r in patientReports) {
            var changed = false
            val meds = r.medications.map { m ->
                if (!m.name.trim().equals(from, ignoreCase = true)) return@map m
                changed = true
                if (r.id == reportId) {
                    // The edited occurrence: apply the new name AND the field edits.
                    m.copy(
                        name = to,
                        dosage = dosage ?: m.dosage, frequency = frequency ?: m.frequency,
                        duration = duration ?: m.duration, isOptional = isOptional ?: m.isOptional,
                        weeklySchedule = weeklySchedule ?: m.weeklySchedule, notes = notes ?: m.notes
                    )
                } else if (nameChanged) {
                    // Other reports: only the identity (name) propagates; their own dosage/history stays.
                    m.copy(name = to)
                } else m
            }
            if (changed) LocalStore.upsertReport(context, r.copy(medications = meds))
        }

        // Re-key the reminder schedule to the new name and refresh its dosage/frequency.
        MedicineScheduleStore.rename(context, patientName, from, to, dosage, frequency)
        MedicineReminderManager.scheduleAll(context)

        // Move intake history to the new name so it isn't orphaned.
        if (nameChanged) LocalStore.renameMedLogs(context, patientName, from, to)
        LocalStore.addMedLog(context, MedLogEntry(
            LocalStore.newId(), patientName, to, "UPDATE_DETAILS", frequency,
            if (nameChanged) "Renamed from \"$from\"" else "Dosage: ${dosage ?: ""}", nowIso()
        ))
        afterWrite(context)
    }

    suspend fun bulkDeleteMedications(context: Context, items: List<MedicationBulkItem>) = withContext(Dispatchers.IO) {
        items.groupBy { it.reportId }.forEach { (reportId, list) ->
            val report = LocalStore.getReport(context, reportId) ?: return@forEach
            val names = list.map { it.medicineName.trim().lowercase() }
            LocalStore.upsertReport(context, report.copy(medications = report.medications.filterNot { it.name.trim().lowercase() in names }))
        }
        afterWrite(context)
    }

    suspend fun bulkUpdateMedications(context: Context, items: List<MedicationBulkItem>) = withContext(Dispatchers.IO) {
        items.groupBy { it.reportId }.forEach { (reportId, list) ->
            val report = LocalStore.getReport(context, reportId) ?: return@forEach
            val meds = report.medications.map { m ->
                val match = list.firstOrNull { it.medicineName.trim().equals(m.name.trim(), true) }
                if (match != null) m.copy(
                    frequency = match.frequency ?: m.frequency,
                    weeklySchedule = match.weeklySchedule ?: m.weeklySchedule,
                    isOptional = match.isOptional ?: m.isOptional
                ) else m
            }
            LocalStore.upsertReport(context, report.copy(medications = meds))
        }
        afterWrite(context)
    }

    // ── Chat ────────────────────────────────────────────────────────────────
    suspend fun chat(context: Context, request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val all = LocalStore.getReports(context)
        val reports = when {
            !request.reportId.isNullOrBlank() -> all.filter { it.id == request.reportId }
            !request.patientName.isNullOrBlank() -> all.filter { it.patientName.equals(request.patientName, true) }.take(15)
            else -> all.take(15)
        }
        val (answer, source) = MedicalEngine.chat(context, request.question, reports, request.history)
        ChatResponse(answer, source)
    }

    // ── Detailed analysis (cached on device) ──────────────────────────────────
    private fun detailedCacheFile(context: Context, id: String): File {
        val dir = File(LocalStore.recordsDir(context), "detailed_analysis").apply { if (!exists()) mkdirs() }
        return File(dir, "$id.json")
    }

    suspend fun getDetailedAnalysis(context: Context, reportId: String, refresh: Boolean): DetailedAnalysis = withContext(Dispatchers.IO) {
        val cache = detailedCacheFile(context, reportId)
        if (!refresh && cache.exists()) {
            runCatching { gson.fromJson(cache.readText(), DetailedAnalysis::class.java) }.getOrNull()?.let {
                if (it.sections.isNotEmpty()) return@withContext it.copy(cached = true)
            }
        }
        val report = LocalStore.getReport(context, reportId)
            ?: return@withContext DetailedAnalysis(summary = "Report not found.")
        val analysis = MedicalEngine.detailedAnalysis(context, report).copy(generatedAt = nowIso())
        runCatching { cache.writeText(gson.toJson(analysis)) }
        analysis.copy(cached = false)
    }

    // ── Compare two images (no save) ───────────────────────────────────────────
    suspend fun compare(context: Context, img1: ByteArray, mime1: String, scanType1: String, cat1: String,
                        img2: ByteArray, mime2: String, scanType2: String, cat2: String): CompareResponse = withContext(Dispatchers.IO) {
        val e1 = OcrEngine.scan(context, listOf(img1 to mime1), "", scanType1, cat1).merged()
        val e2 = OcrEngine.scan(context, listOf(img2 to mime2), "", scanType2, cat2).merged()
        val r1 = toScanned(e1, cat1, "Report 1")
        val r2 = toScanned(e2, cat2, "Report 2")
        // Reuse comparison by wrapping the extractions in temporary reports.
        val temp1 = MedicalReport(id = "cmp1", patientName = r1.patientName, reportDate = r1.reportDate,
            reportType = r1.reportType, extractedText = r1.rawText, comments = r1.comments, medications = r1.medications,
            imagePath = "", createdAt = nowIso(), testResults = r1.testResults, reportCategory = cat1)
        val temp2 = MedicalReport(id = "cmp2", patientName = r2.patientName, reportDate = r2.reportDate,
            reportType = r2.reportType, extractedText = r2.rawText, comments = r2.comments, medications = r2.medications,
            imagePath = "", createdAt = nowIso(), testResults = r2.testResults, reportCategory = cat2)
        val cmp = MedicalEngine.compareReports(context, temp2, temp1)
        CompareResponse(r1, r2, cmp)
    }

    private fun toScanned(e: ScanExtraction, category: String, fallbackName: String) = ScannedReportData(
        patientName = e.patientName ?: fallbackName,
        reportDate = validDate(e.reportDate) ?: today(),
        reportType = e.reportType ?: "Report",
        reportCategory = category,
        medications = e.medications,
        testResults = e.testResults ?: TestResults(),
        comments = e.comments ?: "",
        rawText = e.rawText ?: ""
    )

    // ── Medicine name correction ──────────────────────────────────────────────
    /** Renames a medicine in a specific report (fixes OCR misreads). */
    suspend fun renameMedicine(context: Context, reportId: String, oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val report = LocalStore.getReport(context, reportId) ?: return@withContext false
        val meds = report.medications.map { m ->
            if (m.name.trim().equals(oldName.trim(), true)) m.copy(name = newName.trim()) else m
        }
        if (meds == report.medications) return@withContext false // no change
        LocalStore.upsertReport(context, report.copy(medications = meds))
        afterWrite(context)
        true
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    private fun validDate(d: String?): String? {
        if (d.isNullOrBlank()) return null
        return try { isoDate.parse(d); d } catch (e: Exception) { null }
    }
}
