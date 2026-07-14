package com.example.medicalscanner.ai

import android.content.Context
import com.example.medicalscanner.model.Medication
import com.example.medicalscanner.model.TestResults
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName

/** Future test the doctor recommended, extracted from a scan. */
data class RecommendedTest(
    @SerializedName("testName") val testName: String,
    @SerializedName("dueDate") val dueDate: String? = null
)

/** One date visible on a page together with its printed label ("Reported", "Collected"…). */
data class FoundDate(
    @SerializedName("label") val label: String? = null,
    @SerializedName("date") val date: String? = null
)

/** Structured result of scanning one report/prescription image. */
data class ScanExtraction(
    @SerializedName("patientName") val patientName: String? = null,
    @SerializedName("reportName") val reportName: String? = null,
    @SerializedName("reportDate") val reportDate: String? = null,
    @SerializedName("dateSource") val dateSource: String? = null,
    @SerializedName("datesFound") val datesFound: List<FoundDate> = emptyList(),
    @SerializedName("reportType") val reportType: String? = null,
    @SerializedName("comments") val comments: String? = null,
    @SerializedName("medications") val medications: List<Medication> = emptyList(),
    @SerializedName("recommendedTests") val recommendedTests: List<RecommendedTest> = emptyList(),
    @SerializedName("testResults") val testResults: TestResults? = null,
    @SerializedName("rawText") val rawText: String? = null
)

/**
 * Full extraction of a scan, which may contain SEVERAL distinct reports (e.g. a bundle
 * of CBC + lipid profile + 2D Echo pages), each with its own name and dates.
 */
data class MultiScanExtraction(
    @SerializedName("patientName") val patientName: String? = null,
    @SerializedName("reports") val reports: List<ScanExtraction> = emptyList(),
    @SerializedName("rawText") val rawText: String? = null
) {
    /** Collapses all sections into one legacy-style extraction (used by Compare). */
    fun merged(): ScanExtraction {
        val first = reports.firstOrNull() ?: return ScanExtraction(patientName = patientName, rawText = rawText)
        return ScanExtraction(
            patientName = patientName,
            reportName = first.reportName,
            reportDate = first.reportDate,
            dateSource = first.dateSource,
            datesFound = reports.flatMap { it.datesFound },
            reportType = first.reportType,
            comments = reports.mapNotNull { it.comments?.takeIf { c -> c.isNotBlank() } }.joinToString("\n"),
            medications = reports.flatMap { it.medications },
            recommendedTests = reports.flatMap { it.recommendedTests },
            testResults = TestResults(
                parameters = reports.flatMap { it.testResults?.parameters ?: emptyList() },
                findings = reports.flatMap { it.testResults?.findings ?: emptyList() }
            ),
            rawText = rawText ?: first.rawText
        )
    }
}

/**
 * On-device replacement for the Node backend's scanMedicalReport(). Sends the actual image
 * to Gemini vision (so handwriting is read directly) with any device OCR text as a hint.
 * Falls back to a light local parse if Gemini is unavailable.
 */
object OcrEngine {

    private val gson: Gson = GsonBuilder().setLenient().create()

    /**
     * Scans one or more page images. The pages may contain several distinct reports;
     * each comes back as its own entry with its own name and correctly chosen date.
     *
     * Large batches are processed CHUNK BY CHUNK ([AppSettings.getScanChunkPages] pages
     * per AI request — one giant request exceeds free-tier limits and fails), then the
     * chunk results are merged; a report whose pages span two chunks is recombined by
     * matching name + date. A failed chunk is skipped rather than failing the whole scan.
     */
    fun scan(
        context: Context,
        images: List<Pair<ByteArray, String>>,
        localOcrText: String,
        scanType: String,
        reportCategory: String
    ): MultiScanExtraction {
        val chunkSize = com.example.medicalscanner.local.AppSettings.getScanChunkPages(context)
        val chunks = if (images.isEmpty()) listOf(emptyList()) else images.chunked(chunkSize)

        val results = mutableListOf<MultiScanExtraction>()
        for ((index, chunk) in chunks.withIndex()) {
            // The device-OCR hint text belongs to the first page; only give it to chunk 1.
            val ref = if (index == 0) localOcrText else ""
            scanChunk(context, chunk, ref, scanType, reportCategory, index + 1, chunks.size)
                ?.let { results.add(it) }
        }
        if (results.isEmpty()) return localFallback(localOcrText, scanType)
        return mergeChunks(results)
    }

    private fun scanChunk(
        context: Context,
        images: List<Pair<ByteArray, String>>,
        referenceText: String,
        scanType: String,
        reportCategory: String,
        part: Int,
        totalParts: Int
    ): MultiScanExtraction? = try {
        val prompt = buildPrompt(referenceText, scanType, reportCategory, images.size, part, totalParts)
        val raw = GeminiClient.generateFromImages(context, prompt, images)
        parse(GeminiClient.stripJsonFences(raw))
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    /**
     * Merges per-chunk extractions into one result. Sections with the same report name
     * and date (a report whose pages landed in different chunks) are combined; distinct
     * reports stay separate. Visible for testing.
     */
    internal fun mergeChunks(chunks: List<MultiScanExtraction>): MultiScanExtraction {
        if (chunks.size == 1) return chunks.first()
        val merged = linkedMapOf<String, ScanExtraction>()
        for (chunk in chunks) {
            for (section in chunk.reports) {
                val key = (section.reportName ?: section.reportType ?: "report")
                    .trim().lowercase() + "|" + (section.reportDate ?: "")
                val prev = merged[key]
                merged[key] = if (prev == null) section else prev.copy(
                    comments = listOfNotNull(
                        prev.comments?.takeIf { it.isNotBlank() },
                        section.comments?.takeIf { it.isNotBlank() }
                    ).distinct().joinToString("\n"),
                    medications = prev.medications + section.medications,
                    recommendedTests = (prev.recommendedTests + section.recommendedTests)
                        .distinctBy { it.testName.trim().lowercase() },
                    datesFound = (prev.datesFound + section.datesFound).distinct(),
                    testResults = TestResults(
                        parameters = (prev.testResults?.parameters ?: emptyList()) +
                            (section.testResults?.parameters ?: emptyList()),
                        findings = ((prev.testResults?.findings ?: emptyList()) +
                            (section.testResults?.findings ?: emptyList())).distinct()
                    ),
                    rawText = listOfNotNull(
                        prev.rawText?.takeIf { it.isNotBlank() },
                        section.rawText?.takeIf { it.isNotBlank() }
                    ).joinToString("\n\n")
                )
            }
        }
        return MultiScanExtraction(
            patientName = chunks.firstNotNullOfOrNull { it.patientName?.takeIf { n -> n.isNotBlank() } },
            reports = merged.values.toList(),
            rawText = chunks.mapNotNull { it.rawText?.takeIf { t -> t.isNotBlank() } }.joinToString("\n\n")
        )
    }

    /** Parses the new multi-report shape, falling back to the legacy single-report shape. */
    private fun parse(json: String): MultiScanExtraction? {
        val multi = try { gson.fromJson(json, MultiScanExtraction::class.java) } catch (e: Exception) { null }
        if (multi != null && multi.reports.isNotEmpty()) return multi
        val legacy = try { gson.fromJson(json, ScanExtraction::class.java) } catch (e: Exception) { null }
            ?: return null
        return MultiScanExtraction(
            patientName = legacy.patientName,
            reports = listOf(legacy),
            rawText = legacy.rawText
        )
    }

    private fun buildPrompt(
        referenceText: String,
        scanType: String,
        reportCategory: String,
        pageCount: Int,
        part: Int = 1,
        totalParts: Int = 1
    ): String {
        val pagesNote = buildString {
            if (pageCount > 1) append("The document is provided as $pageCount page images.")
            if (totalParts > 1) append(
                " NOTE: these pages are part $part of $totalParts of a larger scan batch processed in " +
                "chunks. Extract ONLY what is visible on these pages; other parts are processed " +
                "separately. A report may continue in another part — still extract everything visible here."
            )
        }
        val categoryText = if (scanType == "prescription")
            "This document is a Medicine Prescription. Focus heavily on identifying the doctor's prescribed medications, dosages, frequency, durations, and instruction comments."
        else
            "This document is a Medical/Diagnostic Report of category \"$reportCategory\". Focus on patient name, dates, and extracting findings, observations, conclusions, and test parameters (values, units, reference ranges, abnormal flags)."

        val refBlock = if (referenceText.isNotBlank())
            "Here is auxiliary on-device OCR text to assist accuracy. It may be incomplete or miss handwriting, so ALWAYS prefer what you can read directly from the image:\n\"\"\"\n$referenceText\n\"\"\"\n"
        else ""

        return """
Analyze this medical report, lab result, or prescription image and extract the details as a JSON object.
$pagesNote
$refBlock
Context instructions:
$categoryText

IMPORTANT: This document may contain HANDWRITTEN text (a doctor's handwriting, margin notes, ticked boxes, or corrections). Read handwritten medicines, dosages, frequencies, and comments carefully and include them — do NOT ignore handwriting. If partly illegible, transcribe your best interpretation.

MULTIPLE REPORTS: The pages may contain SEVERAL distinct reports (for example a CBC, a lipid profile, and a 2D Echo bundled together), each with its own report name and its own dates. Return one entry in "reports" for EACH distinct report. Pages belonging to the same report must be merged into ONE entry. If everything is one single report, return a single entry.

DATES — read these rules very carefully:
A page often shows several dates with different labels: "Printed on", "Registered on", "Collected on" / "Sample collected", "Reported on" / "Reporting date" / "Report date", "Date of procedure" / "Study date" / "Date of examination", a visit date, or a bare date with no label. For EACH report:
1. List EVERY visible date with its label in "datesFound" (use label "" for an unlabeled date).
2. Choose "reportDate" by these priority rules:
   - Blood / urine / any sample-based lab report: use the REPORTED / REPORTING date. If missing, the sample COLLECTED date. NEVER the printed date.
   - Procedure or imaging report (2D Echo, Sonography/USG, X-Ray, ECG, CT, MRI, Doppler, Endoscopy...): use the PROCEDURE / STUDY / EXAMINATION date — the date it was performed.
   - Prescription: the visit / consultation date.
   - A bare unlabeled date: use it only when none of the above exist.
3. Set "dateSource" to the label of the date you chose (e.g. "Reported", "Procedure", "Visit", "Unlabeled").
4. Convert ALL dates to YYYY-MM-DD. Dates may be printed day-first in Indian formats (12/03/2026, 12-03-26, 12.Mar.2026, 12 March 2026). Do not guess; if no date is visible for a report, set reportDate to null.

Also ensure that:
1. Patient name is identified accurately.
2. "reportName" is the specific printed name of each report (e.g. "Complete Blood Count", "Lipid Profile", "2D Echocardiography").
3. Comments, instructions, remarks, or advice are extracted per report.
4. Medicines mentioned are extracted as an array in the report where they appear.
5. Future recommended tests go into that report's "recommendedTests".
6. Test results go into that report's "testResults": lab parameters into "parameters"; scan/diagnostic conclusions into "findings".
7. For each parameter, also classify it for trend-charting across multiple reports over time:
   - "trendCategory": if it matches one of these, use that EXACT text (case-sensitive) —
     Blood Sugar, HbA1c, TSH, T3, T4, Hemoglobin, WBC, Platelets, Total Cholesterol, LDL, HDL,
     Triglycerides, Creatinine, Oxygen (SpO2), Ejection Fraction, Vitamin D, Vitamin B12
     — otherwise a short clean name of your own for that specific test. Never merge two
     DIFFERENT tests into one category just because they share a word or organ — e.g. serum
     creatinine and urinary creatinine are different categories; blood glucose and urine
     glucose are different categories; an actual measured value and a value CALCULATED from
     a different test (e.g. HbA1c's "estimated average glucose") are different categories.
   - "trendCondition": the condition it was measured under, if the report states one — mainly
     relevant to blood sugar: "Fasting", "PP" (post-meal), or "Random". Empty string otherwise.
   - "excludeFromTrend": true only when the value is NOT a direct numeric measurement — e.g. a
     value calculated/derived from another test, or a semi-quantitative dipstick result like
     "+", "++", "+++", "Negative", "Trace". False for every normal numeric lab result.
   (This list must stay in sync with DashboardEngine.KEY_PARAMETER_ORDER in the Android app.)

The response MUST be a JSON object with this schema:
{
  "patientName": "Name or null",
  "reports": [
    {
      "reportName": "Specific report name or null",
      "reportType": "Prescription | Lab Report | Diagnostic Scan | Other",
      "reportDate": "YYYY-MM-DD or null",
      "dateSource": "Reported | Collected | Procedure | Visit | Unlabeled | null",
      "datesFound": [ { "label": "Reported", "date": "YYYY-MM-DD" } ],
      "comments": "Doctor's instructions/advice/notes for THIS report",
      "medications": [
        { "name": "", "dosage": "", "frequency": "", "duration": "", "isOptional": false, "weeklySchedule": ["Everyday"], "notes": "" }
      ],
      "recommendedTests": [ { "testName": "", "dueDate": "YYYY-MM-DD or null" } ],
      "testResults": {
        "parameters": [
          {
            "name": "", "value": "", "unit": "", "referenceRange": "", "status": "High | Low | Normal",
            "trendCategory": "", "trendCondition": "", "excludeFromTrend": false
          }
        ],
        "findings": [ "" ]
      },
      "rawText": "Markdown transcription of THIS report's pages"
    }
  ],
  "rawText": "A clean, markdown-formatted full transcription of ALL visible text."
}

Return ONLY raw JSON. No markdown code fences, no extra text.
""".trim()
    }

    /** Minimal offline fallback: keep the OCR text and a best-effort patient name. */
    private fun localFallback(localOcrText: String, scanType: String): MultiScanExtraction {
        val name = extractPatientName(localOcrText)
        val type = when (scanType) {
            "prescription" -> "Prescription"
            "report" -> "Lab Report"
            else -> "Other"
        }
        val section = ScanExtraction(
            patientName = name,
            reportDate = null,
            reportType = type,
            comments = "Parsed on-device from OCR text (AI unavailable).",
            medications = emptyList(),
            recommendedTests = emptyList(),
            testResults = TestResults(),
            rawText = localOcrText
        )
        return MultiScanExtraction(patientName = name, reports = listOf(section), rawText = localOcrText)
    }

    private fun extractPatientName(text: String): String {
        if (text.isBlank()) return "Unknown Patient"
        val regex = Regex("(?:Name|Patient|Patient\\s*Name)\\s*[:\\-]?\\s*(?:Mr\\.|Mrs\\.|Ms\\.)?\\s*([A-Za-z ]{3,})", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        val candidate = match?.groupValues?.getOrNull(1)?.trim()?.replace(Regex("\\s+"), " ")
        return if (!candidate.isNullOrBlank() && candidate.length > 3) candidate else "Unknown Patient"
    }

    suspend fun translateSearchPromptToFilter(context: Context, userPrompt: String): String {
        if (userPrompt.isBlank()) {
            return "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
        }
        val prompt = """
            You are a medical email search assistant. Translate the user's request for medical reports or hospital emails into a standard Gmail search query syntax.
            Also, automatically include generic keywords for medical reports (like report, lab, test, diagnostic, prescription) so that the search captures reports even if they don't exactly match the user's specific request.
            The default generic query is: "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Examples:
            Input: "search SRL Labs"
            Output: "SRL subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Input: "find blood test from Metropolis"
            Output: "Metropolis subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription OR blood) has:attachment filename:pdf"
            
            Input: "Max Hospital"
            Output: "Max subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            
            Input: "$userPrompt"
            Output: 
        """.trimIndent()
        return try {
            val response = GeminiClient.generateText(context, prompt)
            GeminiClient.stripJsonFences(response).trim().removeSurrounding("\"").trim()
        } catch (e: Exception) {
            e.printStackTrace()
            val words = userPrompt.split(" ").filter { it.length > 2 }.joinToString(" OR ")
            if (words.isNotBlank()) {
                "($words) subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            } else {
                "subject:(report OR lab OR diagnostic OR billing OR test OR health OR prescription) has:attachment filename:pdf"
            }
        }
    }
}
