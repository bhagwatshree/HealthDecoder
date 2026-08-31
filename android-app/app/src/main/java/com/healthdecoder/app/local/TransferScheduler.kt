package com.healthdecoder.app.local

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.healthdecoder.app.backup.BackupManager
import com.healthdecoder.app.backup.BackupSync
import java.io.File

/**
 * Runs the Settings screen's backup-export and portable export/import transfers via
 * [BackgroundTasks] instead of the screen's own rememberCoroutineScope() — the same fix
 * [RestoreScheduler] already applies to restoring a backup. A backup zip or a portable export can
 * run well past what someone waits before switching tabs once there are enough scanned images in
 * it, and a transfer cut off mid-run isn't a visible error, it's a half-written zip or a partial
 * import silently never finishing.
 */
object TransferScheduler {
    // Backup export to a user-chosen folder (Drive/OneDrive/local) — its own busy/result slot,
    // matching the Settings screen's existing separate "Local Backup" section.
    var backupBusy by mutableStateOf(false)
        private set
    var backupResult by mutableStateOf<String?>(null)
        private set

    fun exportBackupTo(context: Context, destUri: Uri, password: String?, onDone: (String) -> Unit = {}) {
        if (backupBusy) return
        backupBusy = true
        backupResult = null
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            val result = runCatching {
                val zip = BackupManager.createLocalBackup(appContext, password)
                    ?: return@runCatching "Nothing to back up yet — scan a report first."
                appContext.contentResolver.openOutputStream(destUri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                "Backup exported successfully."
            }.getOrElse { "Export failed: ${it.message}" }
            backupResult = result
            backupBusy = false
            onDone(result)
        }
    }

    // Portable export (share) and portable import (merge) — the "Transfer Data" section's shared
    // busy/result slot, same as before.
    var transferBusy by mutableStateOf(false)
        private set
    var transferResult by mutableStateOf<String?>(null)
        private set

    fun exportPortable(
        context: Context,
        patientName: String?,
        sinceDelta: Boolean,
        from: String,
        to: String,
        onFileReady: (File) -> Unit
    ) {
        if (transferBusy) return
        transferBusy = true
        transferResult = null
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            transferResult = runCatching {
                val file = LocalRepository.exportData(appContext, patientName, sinceDelta, from, to)
                if (file == null) "Nothing to export for that selection."
                else {
                    onFileReady(file)
                    "Export ready — choose where to send it."
                }
            }.getOrElse { e ->
                e.printStackTrace()
                "Export failed. Please try again."
            }
            transferBusy = false
        }
    }

    fun importPortable(context: Context, uri: Uri, onDone: suspend () -> Unit = {}) {
        if (transferBusy) return
        transferBusy = true
        transferResult = null
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            transferResult = runCatching {
                val res = LocalRepository.importData(appContext, uri)
                buildString {
                    append("Added ${res.added}")
                    if (res.updated > 0) append(", updated ${res.updated}")
                    append(" report(s)")
                    if (res.skippedDuplicate > 0) append(" (${res.skippedDuplicate} already on this device, skipped)")
                    if (res.patients.isNotEmpty()) append(" • ${res.patients.joinToString()}")
                }
            }.getOrElse { e ->
                e.printStackTrace()
                "Import failed. Please check the file and try again."
            }
            transferBusy = false
            onDone()
        }
    }

    // Manual cloud-folder sync ("Sync Now" / just having picked a folder) — its own busy slot,
    // read from AppSettings/BackupSync's own pending count rather than a cached result string.
    var syncBusy by mutableStateOf(false)
        private set

    fun syncNow(context: Context, onDone: () -> Unit = {}) {
        if (syncBusy) return
        syncBusy = true
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BackupSync.syncPending(appContext) }
            syncBusy = false
            onDone()
        }
    }
}
