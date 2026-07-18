package com.example.medicalscanner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.medicalscanner.backup.BackupManager
import com.example.medicalscanner.backup.BackupSync
import com.example.medicalscanner.backup.SafCloudUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.model.MedicalReport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IPConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var prefLanguage by remember { mutableStateOf(AppSettings.getPreferredLanguage(context)) }
    var langExpanded by remember { mutableStateOf(false) }
    var voiceEngine by remember { mutableStateOf(AppSettings.getVoiceEngine(context)) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var reminderStyle by remember { mutableStateOf(AppSettings.getReminderStyle(context)) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var dupCandidates by remember { mutableStateOf<List<MedicalReport>>(emptyList()) }
    var showDupDialog by remember { mutableStateOf(false) }
    var dupResult by remember { mutableStateOf<String?>(null) }
    var dupScanning by remember { mutableStateOf(false) }
    var cloudFolderLabel by remember { mutableStateOf(SafCloudUploader.getBackupFolderLabel(context)) }
    var pendingSyncCount by remember { mutableStateOf(BackupSync.pendingCount(context)) }
    var syncing by remember { mutableStateOf(false) }

    // SAF folder picker: user picks a cloud-synced folder (Drive / OneDrive / Dropbox / local)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafCloudUploader.setBackupFolderUri(context, uri)
            cloudFolderLabel = SafCloudUploader.getBackupFolderLabel(context)
            // Immediately sync any pending backups to the newly chosen folder
            coroutineScope.launch {
                syncing = true
                withContext(Dispatchers.IO) { BackupSync.syncPending(context) }
                pendingSyncCount = BackupSync.pendingCount(context)
                syncing = false
            }
        }
    }

    // Export a backup zip to any folder the user picks (Google Drive / OneDrive / local).
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) coroutineScope.launch {
            backupResult = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = BackupManager.createLocalBackup(context) ?: return@runCatching "Nothing to back up yet — scan a report first."
                    context.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                    "Backup exported successfully."
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    // Restore from a backup zip the user picks.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            backupResult = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("restore_", ".zip", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val ok = BackupManager.restoreBackup(context, tmp)
                    tmp.delete()
                    if (ok) "Backup restored. Go back and refresh." else "Restore failed — is this a valid backup file?"
                }.getOrElse { "Restore failed: ${it.message}" }
            }
        }
    }

    // ── Portable transfer (share records to another phone / merge someone else's in) ──
    var transferResult by remember { mutableStateOf<String?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportPatient by remember { mutableStateOf<String?>(null) } // null = all patients
    var exportDelta by remember { mutableStateOf(false) }
    var patientMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { patients = LocalRepository.listPatients(context) }

    fun runExport() {
        coroutineScope.launch {
            transferBusy = true
            transferResult = runCatching {
                val file = LocalRepository.exportData(context, exportPatient, exportDelta)
                if (file == null) "Nothing to export for that selection." else {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share export"))
                    "Export ready — choose where to send it."
                }
            }.getOrElse { "Export failed: ${it.message}" }
            transferBusy = false
        }
    }

    val portableImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            transferBusy = true
            transferResult = withContext(Dispatchers.IO) {
                runCatching {
                    val res = LocalRepository.importData(context, uri)
                    buildString {
                        append("Imported ${res.imported} report(s)")
                        if (res.skippedDuplicates > 0) append(", skipped ${res.skippedDuplicates} already present")
                        if (res.patients.isNotEmpty()) append(" • ${res.patients.joinToString()}")
                    }
                }.getOrElse { "Import failed: ${it.message}" }
            }
            patients = LocalRepository.listPatients(context)
            transferBusy = false
        }
    }

    // ── Merge / fix patient names (a mis-scan splits one person into two) ──
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    var mergeTo by remember { mutableStateOf("") }
    var mergeMenuOpen by remember { mutableStateOf(false) }
    var mergeResult by remember { mutableStateOf<String?>(null) }

    fun runMerge() {
        val from = mergeFrom
        val to = mergeTo.trim()
        if (from == null || to.isEmpty()) { mergeResult = "Pick a patient, then type the correct name."; return }
        coroutineScope.launch {
            transferBusy = true
            mergeResult = runCatching {
                val n = LocalRepository.mergePatient(context, from, to)
                patients = LocalRepository.listPatients(context)
                mergeFrom = null; mergeTo = ""
                "Merged $n report(s) into \"$to\"."
            }.getOrElse { "Merge failed: ${it.message}" }
            transferBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Preferred language for explanations & assistant
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Preferred Language",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Medicine explanations and the AI assistant will use this language. Medicine and test names stay in English.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedCard(
                            onClick = { langExpanded = true },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Translate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "Language",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = prefLanguage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = if (langExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = langExpanded,
                            onDismissRequest = { langExpanded = false }
                        ) {
                            AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang, fontWeight = FontWeight.Medium) },
                                    onClick = {
                                        prefLanguage = lang
                                        AppSettings.setPreferredLanguage(context, lang)
                                        langExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Voice (Text-to-Speech) engine
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Voice (Read Aloud)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Which voice reads answers aloud. Sarvam & Gemini speak Indian languages well; Phone uses your device's built-in voices (may not have Marathi).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExposedDropdownMenuBox(
                        expanded = voiceExpanded,
                        onExpandedChange = { voiceExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = voiceEngine,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice engine") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }) {
                            AppSettings.VOICE_ENGINES.forEach { eng ->
                                DropdownMenuItem(
                                    text = { Text(eng) },
                                    onClick = {
                                        voiceEngine = eng
                                        AppSettings.setVoiceEngine(context, eng)
                                        voiceExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Medicine reminder style: standard notification vs full-screen large text
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Medicine Reminder Style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Full Screen shows a large-text alarm page (even on the lock screen) so medicine names are easy to read. Medicines due at the same time always appear together in one reminder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(
                        Triple(AppSettings.REMINDER_STYLE_NORMAL, "Normal notification", "A standard notification with sound and vibration."),
                        Triple(AppSettings.REMINDER_STYLE_FULLSCREEN, "Full screen (large text)", "Fills the screen with big letters — best for elderly users.")
                    ).forEach { (value, label, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = reminderStyle == value,
                                onClick = {
                                    reminderStyle = value
                                    AppSettings.setReminderStyle(context, value)
                                }
                            )
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // Portable transfer — share records to another phone, or merge someone else's in.
            // Unlike Backup (a whole-device snapshot that only restores on the SAME phone), this
            // is a plain, cross-device file that carries the AI analysis inside it, so importing
            // never re-runs the AI. Import MERGES (adds to what's already here) instead of wiping.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Transfer Records",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Export a shareable file of your records (with analysis included) to send to another phone or a doctor. Importing merges it into this phone and never re-runs the AI, so it's free.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Which patient to export (all, or one).
                    Box {
                        OutlinedButton(
                            onClick = { patientMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(exportPatient ?: "All patients", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = patientMenuOpen, onDismissRequest = { patientMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("All patients") }, onClick = { exportPatient = null; patientMenuOpen = false })
                            patients.forEach { p ->
                                DropdownMenuItem(text = { Text(p) }, onClick = { exportPatient = p; patientMenuOpen = false })
                            }
                        }
                    }

                    // Delta toggle — only reports added since the last export.
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { exportDelta = !exportDelta },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Only new since last export", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (AppSettings.getLastExportAt(context) == null) "No previous export yet — this sends everything"
                                else "Sends just what changed since last time",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = exportDelta, onCheckedChange = { exportDelta = it })
                    }

                    transferResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { runExport() },
                            enabled = !transferBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (transferBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export") }
                        }
                        OutlinedButton(
                            onClick = { portableImportLauncher.launch(arrayOf("application/zip", "*/*")) },
                            enabled = !transferBusy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import") }
                    }
                }
            }

            // Merge / fix patient names — collapse a mis-scanned variant into the correct patient
            // so their reports, trends, reminders and history stop being split in two.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Fix / Merge Patient",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "If a name was mis-read on a scan and one person shows up twice, merge the wrong name into the correct one. Moves all their reports, trends, reminders and history together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Source: the (possibly mis-scanned) patient to move away from.
                    Box {
                        OutlinedButton(
                            onClick = { mergeMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(mergeFrom ?: "Select patient to fix", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = mergeMenuOpen, onDismissRequest = { mergeMenuOpen = false }) {
                            if (patients.isEmpty()) {
                                DropdownMenuItem(text = { Text("No patients yet") }, onClick = { mergeMenuOpen = false })
                            }
                            patients.forEach { p ->
                                DropdownMenuItem(text = { Text(p) }, onClick = { mergeFrom = p; if (mergeTo.isBlank()) mergeTo = p; mergeMenuOpen = false })
                            }
                        }
                    }

                    OutlinedTextField(
                        value = mergeTo,
                        onValueChange = { mergeTo = it },
                        label = { Text("Correct name") },
                        placeholder = { Text("e.g. Rajesh Kumar") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    mergeResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }

                    Button(
                        onClick = { runMerge() },
                        enabled = !transferBusy && mergeFrom != null && mergeTo.isNotBlank() && !mergeTo.trim().equals(mergeFrom, ignoreCase = true),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Merge")
                    }
                }
            }

            // Backup & restore (export to Google Drive / OneDrive / any folder)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Backup & Restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Export all your records (reports + images) as a single backup file. Choose your Google Drive or OneDrive folder in the picker to keep a cloud copy. Restore re-imports a backup file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    backupResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportLauncher.launch("medical-backup-$stamp.zip")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Export Backup") }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/zip")) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("Restore") }
                    }

                    // ── Auto Cloud Backup ──────────────────────────────────
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Auto Cloud Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Pick a folder in Google Drive, OneDrive, or Dropbox. New backups are automatically synced there by the cloud app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (cloudFolderLabel != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cloudFolderLabel ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val statusText = when {
                                    syncing -> "Syncing…"
                                    pendingSyncCount > 0 -> "$pendingSyncCount backup(s) pending sync"
                                    else -> "All backups synced ✓"
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        syncing = true
                                        withContext(Dispatchers.IO) { BackupSync.syncPending(context) }
                                        pendingSyncCount = BackupSync.pendingCount(context)
                                        syncing = false
                                    }
                                },
                                enabled = !syncing && pendingSyncCount > 0,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Sync Now") }
                            TextButton(
                                onClick = {
                                    SafCloudUploader.clearBackupFolder(context)
                                    cloudFolderLabel = null
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
                        }
                    } else {
                        Button(
                            onClick = { folderPickerLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) { Text("Choose Backup Folder") }
                    }
                }
            }

            // Duplicate cleanup — remove reports saved twice (pre-dating duplicate detection)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Remove Duplicate Reports",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Finds reports that were saved more than once (same patient, date, and content) and removes the extra copies. The original of each report is always kept. New scans are checked automatically; this cleans up older duplicates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    dupResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedButton(
                        onClick = {
                            dupScanning = true
                            dupResult = null
                            coroutineScope.launch {
                                val found = runCatching { LocalRepository.findDuplicateReports(context) }
                                    .getOrDefault(emptyList())
                                dupScanning = false
                                if (found.isEmpty()) {
                                    dupResult = "No duplicate reports found."
                                } else {
                                    dupCandidates = found
                                    showDupDialog = true
                                }
                            }
                        },
                        enabled = !dupScanning,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(if (dupScanning) "Scanning…" else "Scan for Duplicates") }
                }
            }

            // Danger zone — delete everything
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Delete All Data",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC62828)
                    )
                    Text(
                        text = "Permanently removes every report, medicine, pending test and image. This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB71C1C)
                    )
                    deleteResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { showDeleteAllDialog = true },
                        enabled = !deleting,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) {
                        if (deleting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Delete Everything", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Back button
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Go to Dashboard", fontWeight = FontWeight.SemiBold)
            }
        }

        if (showDupDialog) {
            AlertDialog(
                onDismissRequest = { showDupDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Remove ${dupCandidates.size} duplicate report${if (dupCandidates.size == 1) "" else "s"}?") },
                text = {
                    val preview = dupCandidates.take(5).joinToString("\n") {
                        "• ${it.reportType ?: "Report"} — ${it.patientName ?: "Unknown"} (${it.reportDate ?: "no date"})"
                    }
                    val more = if (dupCandidates.size > 5) "\n…and ${dupCandidates.size - 5} more" else ""
                    Text("These are extra copies of reports you already have. The original of each is kept.\n\n$preview$more")
                },
                confirmButton = {
                    Button(onClick = {
                        showDupDialog = false
                        coroutineScope.launch {
                            val removed = runCatching { LocalRepository.deleteDuplicateReports(context) }.getOrDefault(0)
                            dupResult = "Removed $removed duplicate report${if (removed == 1) "" else "s"}."
                            dupCandidates = emptyList()
                        }
                    }) { Text("Remove Duplicates") }
                },
                dismissButton = { TextButton(onClick = { showDupDialog = false }) { Text("Cancel") } }
            )
        }

        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC62828)) },
                title = { Text("Delete everything?") },
                text = { Text("This permanently deletes ALL reports, medicines, pending tests and images. This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteAllDialog = false
                            coroutineScope.launch {
                                deleting = true
                                deleteResult = null
                                // Cancel & clear all medicine reminders.
                                runCatching {
                                    com.example.medicalscanner.reminder.MedicineReminderManager.cancelAll(context)
                                    com.example.medicalscanner.reminder.MedicineScheduleStore.clearAll(context)
                                    val appointmentsList = com.example.medicalscanner.reminder.AppointmentStore.loadAll(context)
                                    appointmentsList.forEach { com.example.medicalscanner.reminder.AppointmentReminderManager.cancel(context, it.id) }
                                    com.example.medicalscanner.reminder.AppointmentStore.clearAll(context)
                                }
                                // Clear all on-device data.
                                runCatching { com.example.medicalscanner.local.LocalRepository.clearAllData(context) }
                                deleting = false
                                deleteResult = "All data deleted."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                    ) { Text("Delete Everything") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllDialog = false }) { Text("Cancel") }
                }
            )
        }
    }
}
