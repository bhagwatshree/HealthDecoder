package com.example.medicalscanner.backup

import android.content.Context
import android.net.Uri
import com.example.medicalscanner.local.LocalStore
import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.model.SourceFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable, cross-device export/import of medical records — distinct from [BackupManager], whose
 * zip contains the raw SQLite DB encrypted with a random PER-DEVICE key (so it can only be
 * restored on the same phone). This format is plain, self-contained, and mergeable:
 *
 *  - `export.json` — the selected reports serialized with their FULL analysis already embedded
 *    (testResults, comparisonResult, healthInsights, medications). File paths are stripped to bare
 *    names; the actual bytes ride alongside under `images/` and `sources/`.
 *  - Import re-materialises those files into the receiving device's records folder, rewrites the
 *    paths, and UPSERTS each report by id — merging into existing data instead of overwriting it,
 *    and skipping anything already present (matched by page hash). Because the analysis travels
 *    inside the JSON, import NEVER re-runs the AI (no API calls, no cost).
 *
 * Supports delta exports (only reports created after a given timestamp) and per-patient exports.
 */
object ExportManager {

    const val FORMAT_VERSION = 1
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val gson: Gson = GsonBuilder().create()

    /** What one export file declares about itself, plus the payload. */
    private data class Payload(
        val formatVersion: Int,
        val exportedAt: String,
        val sinceTimestamp: String?,   // the delta cutoff used, or null for a full export
        val patientFilter: String?,    // the single patient exported, or null for all
        val reports: List<MedicalReport>  // paths already reduced to bare file names
    )

    /** Outcome of an import, for a user-facing summary. */
    data class ImportResult(val imported: Int, val skippedDuplicates: Int, val patients: Set<String>)

    private fun exportsDir(context: Context): File =
        File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }

    /**
     * Writes a portable export zip of [reports] to the cache dir and returns it (ready to share via
     * a chooser / SAF). [sinceTimestamp] and [patientFilter] are recorded in the manifest for
     * traceability only — [reports] must already be the filtered set.
     */
    fun export(
        context: Context,
        reports: List<MedicalReport>,
        sinceTimestamp: String?,
        patientFilter: String?
    ): File {
        val tag = (patientFilter?.replace(Regex("[^A-Za-z0-9]"), "_")?.take(20) ?: "all")
        val outFile = File(exportsDir(context), "MedicalAssist_${tag}_${stamp.format(Date())}.zip")

        // Reduce each report's absolute file paths to bare names; the bytes go in the zip separately.
        val portable = reports.map { r ->
            r.copy(
                imagePath = r.imagePath.baseName(),
                imagePaths = r.imagePaths.map { it.baseName() },
                sourceFiles = r.sourceFiles.map { it.copy(path = it.path.baseName()) },
                userEmail = null // re-scoped to the importing account on the way back in
            )
        }

        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            // Bundle every referenced image / source file exactly once.
            val addedImages = HashSet<String>()
            val addedSources = HashSet<String>()
            for (r in reports) {
                (listOfNotNull(r.imagePath.takeIf { it.isNotBlank() }) + r.imagePaths).forEach { path ->
                    val f = File(path)
                    if (f.exists() && addedImages.add(f.name)) zip.putFile("images/${f.name}", f)
                }
                for (sf in r.sourceFiles) {
                    val f = File(sf.path)
                    if (f.exists() && addedSources.add(f.name)) zip.putFile("sources/${f.name}", f)
                }
            }
            val payload = Payload(FORMAT_VERSION, nowIso(), sinceTimestamp, patientFilter, portable)
            zip.putNextEntry(ZipEntry("export.json"))
            zip.write(gson.toJson(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return outFile
    }

    /**
     * Merges a portable export (from [uri]) into this device. Files are re-materialised, paths
     * rewritten, and reports upserted by id; a report whose content already exists here (same page
     * hash under a different id) is skipped. No AI is invoked. Throws on an unreadable/wrong file.
     */
    fun import(context: Context, uri: Uri): ImportResult {
        val imagesDir = LocalStore.imagesDir(context)
        val sourcesDir = LocalStore.sourcesDir(context)
        var payloadJson: String? = null

        // Pass 1: stream the zip, writing file entries straight into the records folder and
        // buffering export.json (its order within the zip isn't guaranteed).
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw.buffered()).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        entry.isDirectory -> {}
                        name == "export.json" -> payloadJson = zin.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("images/") -> writeSafely(imagesDir, name.substringAfter("images/"), zin)
                        name.startsWith("sources/") -> writeSafely(sourcesDir, name.substringAfter("sources/"), zin)
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        } ?: throw IllegalStateException("Couldn't open the selected file.")

        val json = payloadJson ?: throw IllegalStateException("This isn't a Medical Assist export file.")
        val payload = gson.fromJson(json, Payload::class.java)
            ?: throw IllegalStateException("The export file is corrupted.")

        var imported = 0
        var skipped = 0
        val patients = linkedSetOf<String>()
        for (r in payload.reports) {
            // Skip if this exact content already lives here under a different report id.
            val existingByHash = if (r.pageHashes.isNotEmpty()) LocalStore.findReportByAnyHash(context, r.pageHashes) else null
            if (existingByHash != null && existingByHash.id != r.id) { skipped++; continue }

            val rehydrated = r.copy(
                imagePath = r.imagePath.takeIf { it.isNotBlank() }?.let { File(imagesDir, it).absolutePath } ?: "",
                imagePaths = r.imagePaths.map { File(imagesDir, it).absolutePath },
                sourceFiles = r.sourceFiles.map { it.copy(path = File(sourcesDir, it.path.baseName()).absolutePath) },
                userEmail = null // upsertReport re-scopes it to the importing account
            )
            LocalStore.upsertReport(context, rehydrated)
            imported++
            r.patientName?.takeIf { it.isNotBlank() }?.let { patients.add(it) }
        }
        return ImportResult(imported, skipped, patients)
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private fun String.baseName(): String = if (isBlank()) this else File(this).name

    private fun ZipOutputStream.putFile(entryName: String, file: File) {
        putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(this) }
        closeEntry()
    }

    /** Writes a zip entry's bytes into [dir] under a sanitized base name (guards path traversal). */
    private fun writeSafely(dir: File, rawName: String, input: java.io.InputStream) {
        val safe = File(rawName).name // strip any path components
        if (safe.isBlank() || safe == "." || safe == "..") return
        val out = File(dir, safe)
        if (!out.canonicalPath.startsWith(dir.canonicalPath)) return
        FileOutputStream(out).use { input.copyTo(it) }
    }

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())
}
