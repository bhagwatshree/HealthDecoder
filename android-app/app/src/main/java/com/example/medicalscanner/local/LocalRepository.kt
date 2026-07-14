package com.example.medicalscanner.local

import android.content.Context
import com.example.medicalscanner.ai.DashboardEngine
import com.example.medicalscanner.ai.DateResolver
import com.example.medicalscanner.ai.MedicalEngine
import com.example.medicalscanner.ai.OcrEngine
import com.example.medicalscanner.ai.ScanExtraction
import com.example.medicalscanner.backup.BackupManager
import com.example.medicalscanner.backup.BackupSync
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
     * Re-runs OCR/AI extraction on a report's originally scanned image(s) and refreshes its
     * test parameters, medications and insights. For when the first scan came back incomplete
     * (e.g. the AI API was briefly unavailable) — this is the only way to redo extraction
     * without deleting and re-scanning the report from scratch.
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
            extractedText = existing.extractedText?.takeIf { it.isNotBlank() } ?: section.rawText
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

    // ── Dashboard / summary ─────────────────────────────────────────────────
    suspend fun getDashboard(context: Context, period: String?): DashboardData = withContext(Dispatchers.IO) {
        val reports = filterByPeriod(LocalStore.getReports(context), period)
        DashboardEngine.buildDashboard(reports, LocalStore.getPendingTests(context))
    }

    suspend fun getHealthSummary(context: Context, patientName: String, period: String?): HealthSummary = withContext(Dispatchers.IO) {
        val reports = filterByPeriod(LocalStore.getReports(context), period)
            .filter { it.patientName.equals(patientName, true) }
        DashboardEngine.buildHealthSummary(patientName, reports)
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
