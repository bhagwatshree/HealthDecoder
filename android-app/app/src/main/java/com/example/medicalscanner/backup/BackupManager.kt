package com.example.medicalscanner.backup

import android.content.Context
import com.example.medicalscanner.local.LocalStore
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
 * On-device backup engine. Snapshots the entire records/ folder (medical_records.db,
 * images/, and sources/) into a single timestamped .zip stored locally on the
 * device. These local snapshots are the source of truth for backup; cloud upload
 * (Google Drive) is a separate, deferred step that runs when the network is available
 * (see BackupSync). Uses only java.util.zip — no external dependencies.
 *
 * Older backups containing the legacy reports.json files restore fine: LocalStore
 * imports them into the database the next time it opens.
 */
object BackupManager {

    private const val MAX_LOCAL_BACKUPS = 15
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun backupsDir(context: Context): File =
        File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }

    /** All local backup zips, newest first. */
    fun listBackups(context: Context): List<File> =
        backupsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Creates a new local backup snapshot of all records. Returns the created zip file,
     * or null if there is nothing to back up. Old snapshots beyond MAX_LOCAL_BACKUPS are pruned.
     */
    @Synchronized
    fun createLocalBackup(context: Context): File? {
        val recordsDir = LocalStore.recordsDir(context)
        if (!recordsDir.exists() || recordsDir.listFiles().isNullOrEmpty()) return null

        val outFile = File(backupsDir(context), "backup_${stamp.format(Date())}.zip")
        ZipOutputStream(FileOutputStream(outFile).buffered()).use { zip ->
            zipDirectory(recordsDir, recordsDir, zip)
        }
        pruneOldBackups(context)
        return outFile
    }

    /**
     * Restores a local backup, replacing the current records folder contents.
     * Existing records are cleared first so the restore is a clean overwrite.
     */
    @Synchronized
    fun restoreBackup(context: Context, backupZip: File): Boolean {
        if (!backupZip.exists()) return false
        LocalStore.closeDatabase() // release the SQLite file before replacing it
        val recordsDir = LocalStore.recordsDir(context)
        // Clear current records (but keep the folder itself).
        recordsDir.listFiles()?.forEach { it.deleteRecursively() }

        ZipInputStream(FileInputStream(backupZip).buffered()).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                val outFile = File(recordsDir, entry.name)
                // Guard against zip path traversal.
                if (!outFile.canonicalPath.startsWith(recordsDir.canonicalPath)) {
                    entry = zin.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zin.copyTo(out) }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        return true
    }

    /** Human-readable label for a backup file (from its timestamp). */
    fun labelFor(file: File): String {
        return try {
            val name = file.nameWithoutExtension.removePrefix("backup_")
            val parsed = stamp.parse(name)
            SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault()).format(parsed!!)
        } catch (e: Exception) {
            file.name
        }
    }

    fun backupSizeKb(file: File): Long = (file.length() / 1024).coerceAtLeast(1)

    // ── internals ────────────────────────────────────────────────────────────
    private fun zipDirectory(root: File, current: File, zip: ZipOutputStream) {
        val children = current.listFiles() ?: return
        for (child in children) {
            val relPath = child.absolutePath.removePrefix(root.absolutePath).trimStart('/', '\\')
            if (child.isDirectory) {
                zipDirectory(root, child, zip)
            } else {
                zip.putNextEntry(ZipEntry(relPath.replace('\\', '/')))
                FileInputStream(child).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun pruneOldBackups(context: Context) {
        val backups = listBackups(context)
        if (backups.size > MAX_LOCAL_BACKUPS) {
            backups.drop(MAX_LOCAL_BACKUPS).forEach { runCatching { it.delete() } }
        }
    }
}
