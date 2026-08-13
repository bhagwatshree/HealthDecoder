package com.healthdecoder.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.healthdecoder.app.FeatureFlags
import com.healthdecoder.app.auth.BiometricHelper
import com.healthdecoder.app.backup.BackupManager
import com.healthdecoder.app.backup.BackupSync
import com.healthdecoder.app.backup.SafCloudUploader
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.local.LocalStore
import com.healthdecoder.app.local.SecureKeyManager
import com.healthdecoder.app.model.KeyAssignment
import com.healthdecoder.app.model.MedicalReport
import com.healthdecoder.app.model.UserAccount
import com.healthdecoder.app.network.AccountSync
import com.healthdecoder.app.network.NetworkModule
import com.healthdecoder.app.network.httpCode
import com.healthdecoder.app.network.apiErrorMessage
import com.healthdecoder.app.ui.components.AppBottomNavBar
import com.healthdecoder.app.ui.components.BottomNavTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// A successful restore swaps the database/records folder out from under the running process —
// screens, LaunchedEffects, and cached repository state have no way to know their in-memory data
// is now stale. "Go back and refresh" doesn't reliably surface the restored data (Home's refresh
// only pings the server). Restarting the whole task guarantees every screen re-reads from disk.
private fun restartApp(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    val restartIntent = android.content.Intent.makeRestartActivityTask(launchIntent.component)
    context.startActivity(restartIntent)
    Runtime.getRuntime().exit(0)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToTab: (BottomNavTab) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var account by remember { mutableStateOf<UserAccount?>(null) }
    var assignment by remember { mutableStateOf<KeyAssignment?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isFingerprintEnabled by remember { mutableStateOf(AppSettings.isBiometricEnabled(context)) }
    var fingerprintError by remember { mutableStateOf<String?>(null) }

    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteAccountError by remember { mutableStateOf<String?>(null) }

    // Family member management — was previously reachable only from Home's greeting dropdown.
    var showFamilyManager by remember { mutableStateOf(false) }

    // ── Former IPConfigScreen state, folded in so Settings is one screen, not two ──
    var prefLanguage by remember { mutableStateOf(AppSettings.getPreferredLanguage(context)) }
    var langExpanded by remember { mutableStateOf(false) }
    var voiceEngine by remember { mutableStateOf(AppSettings.getVoiceEngine(context)) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var reminderStyle by remember { mutableStateOf(AppSettings.getReminderStyle(context)) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteAllResult by remember { mutableStateOf<String?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var dupCandidates by remember { mutableStateOf<List<MedicalReport>>(emptyList()) }
    var showDupDialog by remember { mutableStateOf(false) }
    var dupResult by remember { mutableStateOf<String?>(null) }
    var dupScanning by remember { mutableStateOf(false) }
    var cloudFolderLabel by remember { mutableStateOf(SafCloudUploader.getBackupFolderLabel(context)) }
    var pendingSyncCount by remember { mutableStateOf(BackupSync.pendingCount(context)) }
    var syncing by remember { mutableStateOf(false) }
    var transferResult by remember { mutableStateOf<String?>(null) }
    var transferBusy by remember { mutableStateOf(false) }
    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var exportPatient by remember { mutableStateOf<String?>(null) } // null = all patients
    var exportDelta by remember { mutableStateOf(false) }
    var exportFrom by remember { mutableStateOf("") } // YYYY-MM-DD, inclusive; blank = no lower bound
    var exportTo by remember { mutableStateOf("") }   // YYYY-MM-DD, inclusive; blank = no upper bound
    var patientMenuOpen by remember { mutableStateOf(false) }
    var mergeFrom by remember { mutableStateOf<String?>(null) }
    var mergeTo by remember { mutableStateOf("") }
    var mergeMenuOpen by remember { mutableStateOf(false) }
    var mergeResult by remember { mutableStateOf<String?>(null) }

    // Hoisted here because tr() is @Composable and can't be called from the plain onClick /
    // coroutine lambdas below where these Toasts are shown.
    val scanningInboxToast = tr("Scanning inbox for new reports…")
    val foundNewReportsToastPrefix = tr("Found")
    val foundNewReportsToastSuffix = tr("new report(s) — check your notifications.")
    val noNewReportsInLast2DaysToast = tr("No new reports found in the last 2 days.")
    val scanFailedCheckSettingsToast = tr("Scan failed. Check your email settings and try again.")
    val emailScanHistoryClearedToast = tr("Email scan history cleared — reports can be re-detected.")
    val pleaseLinkGoogleAccountToast = tr("Please link your Google Account first.")
    val emailSettingsSavedToast = tr("Email settings saved successfully.")
    val pleaseEnterEmailAddressToast = tr("Please enter an email address.")

    // DESTRUCTIVE AND IRREVERSIBLE: deletes the server account, then — only once that succeeds —
    // wipes every local record too. On failure nothing local is touched, so the user's on-device
    // data survives a network error or a server-side failure.
    fun deleteAccount() {
        isDeletingAccount = true
        deleteAccountError = null
        coroutineScope.launch {
            val result = runCatching { NetworkModule.getApi(context).deleteAccount() }
            result.onSuccess {
                runCatching { LocalRepository.clearAllLocalData(context) }
                AppSettings.logout(context)
                isDeletingAccount = false
                showDeleteAccountDialog = false
                onLoggedOut()
            }.onFailure { e ->
                isDeletingAccount = false
                deleteAccountError = e.apiErrorMessage() ?: e.message?.takeIf { it.isNotBlank() } ?: "Failed to delete account. Check your connection and try again."
            }
        }
    }

    // Just viewing the screen must never consume a free-tier issuance — use the read-only
    // peek endpoint here. AccountSync.refreshAssignedKeys (which does consume one, and also
    // updates the locally cached active key) is only called after actually saving a new key.
    fun load() {
        // Phone OTP sign-in is optional/off by default (FeatureFlags.PHONE_AUTH_ENABLED) — most
        // installs are never a logged-in `users` row at all, and that is a normal state, NOT a
        // session expiring. Only call the account API (and treat its 401 as "log the user out")
        // when we actually had a session to begin with; otherwise just render the guest view below.
        if (!AppSettings.isLoggedIn(context)) {
            account = null
            assignment = null
            isLoading = false
            loadError = null
            return
        }
        isLoading = true
        loadError = null
        coroutineScope.launch {
            val api = NetworkModule.getApi(context)
            val result = runCatching {
                val me = api.getMe()
                val keys = AccountSync.peekUsage(context)
                me to keys
            }
            isLoading = false
            result.onSuccess { (me, keys) ->
                account = me
                assignment = keys
            }.onFailure { e ->
                if (e.httpCode() == 401) {
                    // Session no longer valid on the server — clear it and go to login.
                    AppSettings.logout(context)
                    onLoggedOut()
                } else {
                    loadError = "Couldn't load your account. Check your connection and try again."
                }
            }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) { patients = LocalRepository.listPatients(context) }

    // SAF folder picker: user picks a cloud-synced folder (Drive / OneDrive / Dropbox / local)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SafCloudUploader.setBackupFolderUri(context, uri)
            cloudFolderLabel = SafCloudUploader.getBackupFolderLabel(context)
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
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = BackupManager.createLocalBackup(context) ?: return@runCatching "Nothing to back up yet — scan a report first."
                    context.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                    "Backup exported successfully."
                }.getOrElse { "Export failed: ${it.message}" }
            }
            backupResult = result
            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // Restore from a backup zip the user picks. Filtering OpenDocument to "application/zip" broke
    // restoring from Google Drive: Drive's DocumentsProvider often reports synced/uploaded files as
    // application/octet-stream rather than the exact MIME type, so the picker greyed the backup out
    // or hid it entirely. "*/*" lets any file be picked; restoreBackup already validates the content
    // and reports "not a valid backup file" if it isn't one.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutineScope.launch {
            var restoredOk = false
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File.createTempFile("restore_", ".zip", context.cacheDir)
                    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    if (copied == null || tmp.length() == 0L) {
                        tmp.delete()
                        return@runCatching "Restore failed — couldn't read the selected file."
                    }
                    val ok = BackupManager.restoreBackup(context, tmp)
                    tmp.delete()
                    restoredOk = ok
                    if (ok) "Backup restored — reloading…"
                    else "Restore failed — this doesn't look like a Backup & Restore file. If it came from \"Transfer Records\", use the Import button in that section instead."
                }.getOrElse { "Restore failed: ${it.message}" }
            }
            backupResult = result
            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_LONG).show()
            if (restoredOk) {
                delay(800) // let the toast register before the app restarts
                restartApp(context)
            }
        }
    }

    fun runExport() {
        coroutineScope.launch {
            transferBusy = true
            transferResult = runCatching {
                val file = LocalRepository.exportData(context, exportPatient, exportDelta, exportFrom.trim(), exportTo.trim())
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
                        append("Added ${res.added}")
                        if (res.updated > 0) append(", updated ${res.updated}")
                        append(" report(s)")
                        if (res.patients.isNotEmpty()) append(" • ${res.patients.joinToString()}")
                    }
                }.getOrElse { "Import failed: ${it.message}" }
            }
            patients = LocalRepository.listPatients(context)
            transferBusy = false
        }
    }

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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Text(tr("Account"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            AppBottomNavBar(currentTab = BottomNavTab.Settings, onNavigate = onNavigateToTab)
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .appWatermark()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            loadError?.let {
                Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            if (!com.healthdecoder.app.local.SecureKeyManager.isStorageHardwareBacked()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            tr("This device's secure hardware storage is unavailable, so your local " +
                                "records key and linked-email credentials are stored without " +
                                "hardware-backed encryption. Your data still isn't sent anywhere " +
                                "insecurely, but this device offers weaker protection than usual."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            account?.let { acc ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val fullName = listOfNotNull(acc.firstName, acc.lastName).joinToString(" ").trim()
                        if (fullName.isNotEmpty()) {
                            Text(fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(acc.email, style = MaterialTheme.typography.bodyMedium)
                        acc.msisdn?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            "${tr("Plan:")} ${acc.plan.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } // account?.let ends here — everything below is LOCAL (theme, units, import/export,
              // fingerprint lock, server address, ...) and doesn't need a logged-in account. Only
              // the profile card above actually reads `acc`; the "AI Vision Engine & API Key" card
              // just below has its own independent `assignment?.let` null-check.

                // Family members — add/edit/remove patients this device tracks. Previously only
                // reachable from Home's small "Hello, Name ▼" dropdown; also surfaced here since
                // that wasn't discoverable enough on its own.
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showFamilyManager = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(tr("Family Members"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tr("Add, edit, or remove people this device tracks"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── Everything below used to live on a separate "Server Settings" screen —
                // folded in here so Settings is one screen instead of two. ──

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
                        Text(text = tr("Preferred Language"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Medicine explanations and the AI assistant will use this language. Medicine and test names stay in English."),
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
                                            Text(text = tr("Language"),
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
                        Text(text = tr("Voice (Read Aloud)"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Which voice reads answers aloud. Sarvam & Gemini speak Indian languages well; Phone uses your device's built-in voices (may not have Marathi)."),
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
                                label = { Text(tr("Voice engine")) },
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
                        Text(text = tr("Medicine Reminder Style"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Full Screen shows a large-text alarm page (even on the lock screen) so medicine names are easy to read. Medicines due at the same time always appear together in one reminder."),
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
                        Text(text = tr("Transfer Records"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Export a shareable file of your records (with analysis included) to send to another phone or a doctor. Importing merges it into this phone and never re-runs the AI, so it's free."),
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
                                DropdownMenuItem(text = { Text(tr("All patients")) }, onClick = { exportPatient = null; patientMenuOpen = false })
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
                                Text(tr("Only new since last export"), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (AppSettings.getLastExportAt(context) == null) tr("No previous export yet — this sends everything")
                                    else tr("Sends just what changed since last time"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = exportDelta, onCheckedChange = { exportDelta = it })
                        }

                        // Date-range window — keep/transfer just a slice (e.g. this year) for lighter
                        // analysis & trends. Leave blank for no bound. Format YYYY-MM-DD.
                        Text(tr("Date range (optional)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = exportFrom,
                                onValueChange = { exportFrom = it },
                                label = { Text(tr("From")) },
                                placeholder = { Text("2026-01-01") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            OutlinedTextField(
                                value = exportTo,
                                onValueChange = { exportTo = it },
                                label = { Text(tr("To")) },
                                placeholder = { Text("2026-12-31") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            )
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
                                else { Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Export")) }
                            }
                            OutlinedButton(
                                onClick = { portableImportLauncher.launch(arrayOf("*/*")) },
                                enabled = !transferBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Import")) }
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
                        Text(text = tr("Fix / Merge Patient"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("If a name was mis-read on a scan and one person shows up twice, merge the wrong name into the correct one. Moves all their reports, trends, reminders and history together."),
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
                                    DropdownMenuItem(text = { Text(tr("No patients yet")) }, onClick = { mergeMenuOpen = false })
                                }
                                patients.forEach { p ->
                                    DropdownMenuItem(text = { Text(p) }, onClick = { mergeFrom = p; if (mergeTo.isBlank()) mergeTo = p; mergeMenuOpen = false })
                                }
                            }
                        }

                        OutlinedTextField(
                            value = mergeTo,
                            onValueChange = { mergeTo = it },
                            label = { Text(tr("Correct name")) },
                            placeholder = { Text(tr("e.g. Rajesh Kumar")) },
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
                            Text(tr("Merge"))
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
                        Text(text = tr("Backup & Restore"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Export all your records (reports + images) as a single backup file. Choose your Google Drive or OneDrive folder in the picker to keep a cloud copy. Restore re-imports a backup file."),
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
                            ) { Text(tr("Export Backup")) }
                            OutlinedButton(
                                onClick = {
                                    android.util.Log.d("BackupRestore", "Restore button tapped, launching picker")
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(tr("Restore")) }
                        }

                        // ── Auto Cloud Backup ──────────────────────────────────
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(text = tr("Auto Cloud Backup"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Pick a folder in Google Drive, OneDrive, or Dropbox. New backups are automatically synced there by the cloud app."),
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
                                ) { Text(tr("Sync Now")) }
                                TextButton(
                                    onClick = {
                                        SafCloudUploader.clearBackupFolder(context)
                                        cloudFolderLabel = null
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text(tr("Disconnect"), color = MaterialTheme.colorScheme.error) }
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
                            ) { Text(tr("Choose Backup Folder")) }
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
                        Text(text = tr("Remove Duplicate Reports"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(text = tr("Finds reports that were saved more than once (same patient, date, and content) and removes the extra copies. The original of each report is always kept. New scans are checked automatically; this cleans up older duplicates."),
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
                        Text(text = tr("Delete All Data"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC62828)
                        )
                        Text(text = tr("Permanently removes every report, medicine, pending test and image. This cannot be undone."),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB71C1C)
                        )
                        deleteAllResult?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showDeleteAllDialog = true },
                            enabled = !deletingAll,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) {
                            if (deletingAll) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(tr("Delete Everything"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                assignment?.let { a ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("AI Vision Engine & API Key"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (a.billedTo == "own") Color(0xFFE8EAF6) else Color(0xFFE8F5E9)
                                ) {
                                    Text(
                                        text = if (a.billedTo == "own") tr("Secondary: Custom Key") else tr("Primary: Shared Key Pool"),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (a.billedTo == "own") Color(0xFF283593) else Color(0xFF2E7D32)
                                    )
                                }
                            }

                            when (a.billedTo) {
                                "own" -> {
                                    Text(tr("Using your individual Gemini API key — unlimited scans, bypassing the free tier limits."),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                "premium" -> Text(tr("Premium plan — unlimited usage."), style = MaterialTheme.typography.bodySmall)
                                else -> {
                                    val used = a.usageToday.coerceAtMost(a.limit)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { if (a.limit > 0) used.toFloat() / a.limit else 0f },
                                            modifier = Modifier.fillMaxWidth().height(8.dp),
                                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                        Text("$used / ${a.limit} free daily scans used (Shared Key Pool)", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (a.quotaExceeded) {
                                        Text(tr("Today's free pool quota is used up. Add a personal API key below for unlimited scans, or wait until tomorrow."),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Individual API Key Configuration Section
                            var customKeyInput by remember { mutableStateOf("") }
                            var isSavingKey by remember { mutableStateOf(false) }
                            var keyActionMessage by remember { mutableStateOf<String?>(null) }
                            var keyActionIsError by remember { mutableStateOf(false) }

                            Text(tr("Custom API Key (Optional)"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(tr("By default the app uses the shared key pool — you don't need to do anything. Advanced: if you already have your own Gemini API key you can paste it below to use it instead."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = customKeyInput,
                                onValueChange = { customKeyInput = it },
                                label = { Text(tr("Gemini API Key (AIzaSy...)")) },
                                placeholder = { Text(tr("Leave blank to use Primary Shared Key Pool")) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            keyActionMessage?.let { msg ->
                                Text(
                                    tr(msg),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (keyActionIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (customKeyInput.isBlank()) {
                                            keyActionMessage = "Please enter a valid API key or tap 'Revert to Shared Pool'."
                                            keyActionIsError = true
                                            return@Button
                                        }
                                        isSavingKey = true
                                        keyActionMessage = null
                                        coroutineScope.launch {
                                            val res = runCatching {
                                                NetworkModule.getApi(context).setGeminiKeyOnAccount(
                                                    com.healthdecoder.app.model.ApiKeyRequest(customKeyInput.trim())
                                                )
                                            }
                                            isSavingKey = false
                                            res.onSuccess {
                                                AccountSync.refreshAssignedKeys(context)
                                                keyActionMessage = "Personal API Key saved! Switched to Individual Key mode."
                                                keyActionIsError = false
                                                customKeyInput = ""
                                                load()
                                            }.onFailure { e ->
                                                keyActionMessage = e.apiErrorMessage() ?: "Failed to save key."
                                                keyActionIsError = true
                                            }
                                        }
                                    },
                                    enabled = !isSavingKey,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(tr("Save Key"))
                                }

                                // Gate on a.billedTo alone — the client no longer holds any key locally to check.
                                if (a.billedTo == "own") {
                                    OutlinedButton(
                                        onClick = {
                                            isSavingKey = true
                                            keyActionMessage = null
                                            coroutineScope.launch {
                                                val res = runCatching {
                                                    NetworkModule.getApi(context).setGeminiKeyOnAccount(
                                                        com.healthdecoder.app.model.ApiKeyRequest("")
                                                    )
                                                }
                                                isSavingKey = false
                                                res.onSuccess {
                                                    AccountSync.refreshAssignedKeys(context)
                                                    keyActionMessage = "Reverted to Primary Shared Key Pool."
                                                    keyActionIsError = false
                                                    load()
                                                }.onFailure { e ->
                                                    keyActionMessage = e.apiErrorMessage() ?: "Failed to revert to shared pool."
                                                    keyActionIsError = true
                                                }
                                            }
                                        },
                                        enabled = !isSavingKey,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text(tr("Revert to Pool"))
                                    }
                                }
                            }

                            // "Connect Google to Auto-Fetch Gemini Key" removed: Google login can't
                            // mint a Gemini key without the scary cloud-platform scope + a per-user
                            // GCP project + Google security review — unusable for non-technical users.
                            // The backend never handled state=apikey, so the redirect's nonce never
                            // matched and the flow silently dropped ("disconnect after login").
                            // Non-technical users are served by the invisible shared key pool above.
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(tr("App Theme"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        var currentThemeMode by remember { mutableStateOf(AppSettings.getThemeMode(context)) }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = currentThemeMode == AppSettings.THEME_LIGHT,
                                onClick = {
                                    currentThemeMode = AppSettings.THEME_LIGHT
                                    AppSettings.setThemeMode(context, AppSettings.THEME_LIGHT)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                            ) { Text(tr("Light")) }
                            SegmentedButton(
                                selected = currentThemeMode == AppSettings.THEME_DARK,
                                onClick = {
                                    currentThemeMode = AppSettings.THEME_DARK
                                    AppSettings.setThemeMode(context, AppSettings.THEME_DARK)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                            ) { Text(tr("Dark")) }
                            SegmentedButton(
                                selected = currentThemeMode == AppSettings.THEME_SYSTEM,
                                onClick = {
                                    currentThemeMode = AppSettings.THEME_SYSTEM
                                    AppSettings.setThemeMode(context, AppSettings.THEME_SYSTEM)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                            ) { Text(tr("System")) }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(tr("Lab Units"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            tr("The unit every trend chart standardises readings to. Reports in a different " +
                                "unit are converted automatically; each report still shows its original value."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        var currentUnitSystem by remember { mutableStateOf(AppSettings.getUnitSystem(context)) }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = currentUnitSystem == AppSettings.UNIT_SYSTEM_CONVENTIONAL,
                                onClick = {
                                    currentUnitSystem = AppSettings.UNIT_SYSTEM_CONVENTIONAL
                                    AppSettings.setUnitSystem(context, AppSettings.UNIT_SYSTEM_CONVENTIONAL)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) { Text(tr("Indian (mg/dL)")) }
                            SegmentedButton(
                                selected = currentUnitSystem == AppSettings.UNIT_SYSTEM_SI,
                                onClick = {
                                    currentUnitSystem = AppSettings.UNIT_SYSTEM_SI
                                    AppSettings.setUnitSystem(context, AppSettings.UNIT_SYSTEM_SI)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) { Text(tr("International (SI)")) }
                        }
                    }
                }

                // Demo data — lets a brand-new user (most likely a Play Store tester who has never
                // scanned a real document) see Records/Trends/Reminders/Doctor Brief populated
                // without scanning anything. Local-only, so it belongs alongside the other
                // local-only cards above (Server Settings, App Theme, Lab Units) rather than
                // being gated on login. Same entry point as the onboarding carousel's "Try Demo".
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        var demoDataPresent by remember { mutableStateOf<Boolean?>(null) }
                        var isTogglingDemo by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) {
                            demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                if (demoDataPresent == true) "Demo Data Active" else "Try Demo Data",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            if (demoDataPresent == true)
                                "The sample patient \"${com.healthdecoder.app.local.DemoDataSeeder.DEMO_PATIENT_NAME}\" is visible in your family list, with example reports, reminders and an appointment."
                            else
                                "Adds a sample patient with example reports, reminders and an appointment, so you can explore the app before scanning anything real.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (demoDataPresent == true) {
                            OutlinedButton(
                                onClick = {
                                    isTogglingDemo = true
                                    coroutineScope.launch {
                                        runCatching { com.healthdecoder.app.local.DemoDataSeeder.removeDemoData(context) }
                                        demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context)
                                        isTogglingDemo = false
                                    }
                                },
                                enabled = !isTogglingDemo,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isTogglingDemo) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(tr("Remove Demo Data"))
                                }
                            }
                        } else {
                            Button(
                                onClick = {
                                    isTogglingDemo = true
                                    coroutineScope.launch {
                                        runCatching { com.healthdecoder.app.local.DemoDataSeeder.seedDemoData(context) }
                                        demoDataPresent = com.healthdecoder.app.local.DemoDataSeeder.isDemoDataPresent(context)
                                        isTogglingDemo = false
                                    }
                                },
                                enabled = !isTogglingDemo,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isTogglingDemo) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Text(tr("Add Demo Data"))
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = Icons.Default.Science,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(tr("Help Fund This App"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            var researchConsent by remember { mutableStateOf(AppSettings.isResearchDataSharingConsented(context)) }
                            Switch(
                                checked = researchConsent,
                                onCheckedChange = { checked ->
                                    researchConsent = checked
                                    AppSettings.setResearchDataSharingConsented(context, checked)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            tr("Why: Health Decoder stays free by keeping costs low, not by selling your records. " +
                                "If you opt in here, only your age and sex — never your name, reports, or exact location — " +
                                "would be shared in aggregate with medical research institutes, to help fund keeping the app " +
                                "free for every family instead of running ads or charging a subscription."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            tr("This program hasn't launched yet — turning this on today only saves your preference. " +
                                "Nothing is sent anywhere until a real data-sharing pipeline exists, and you can change " +
                                "your answer here at any time."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (BiometricHelper.isBiometricsAvailable(context)) {
                    val enableFingerprintTitle = tr("Enable Fingerprint Login")
                    val enableFingerprintSubtitle = tr("Confirm fingerprint to register")
                    val fingerprintReloginError = tr("Error: Please log in again to configure fingerprint.")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(tr("Fingerprint Login"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(tr("Sign in quickly using fingerprint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = isFingerprintEnabled,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        val activity = context.findActivity() as? FragmentActivity
                                        if (activity != null) {
                                            BiometricHelper.showBiometricPrompt(
                                                activity = activity,
                                                title = enableFingerprintTitle,
                                                subtitle = enableFingerprintSubtitle,
                                                onResult = { result ->
                                                    if (result.isSuccess) {
                                                        val currentToken = AppSettings.getAuthToken(context)
                                                        val currentEmail = AppSettings.getUserEmail(context)
                                                        if (currentToken != null && currentEmail != null) {
                                                            AppSettings.setBiometricEnabled(context, true)
                                                            AppSettings.setBiometricToken(context, currentToken)
                                                            AppSettings.setBiometricUserEmail(context, currentEmail)
                                                            isFingerprintEnabled = true
                                                        } else {
                                                            fingerprintError = fingerprintReloginError
                                                        }
                                                    } else {
                                                        isFingerprintEnabled = false
                                                    }
                                                }
                                            )
                                        }
                                    } else {
                                        AppSettings.setBiometricEnabled(context, false)
                                        AppSettings.clearBiometricCredentials(context)
                                        isFingerprintEnabled = false
                                    }
                                }
                            )
                        }
                        fingerprintError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(tr("Local Database Encryption"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(text = tr("AES-SQLCipher"),
                                color = Color(0xFF2E7D32),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Change Password Section
                var showChangePasswordSection by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showChangePasswordSection = !showChangePasswordSection },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tr("Change Password"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showChangePasswordSection = !showChangePasswordSection }) {
                                Icon(
                                    imageVector = if (showChangePasswordSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }

                        if (showChangePasswordSection) {
                            var currentPassword by remember { mutableStateOf("") }
                            var newPassword by remember { mutableStateOf("") }
                            var confirmPassword by remember { mutableStateOf("") }

                            var currentPasswordVisible by remember { mutableStateOf(false) }
                            var newPasswordVisible by remember { mutableStateOf(false) }
                            var confirmPasswordVisible by remember { mutableStateOf(false) }

                            var isUpdatingPassword by remember { mutableStateOf(false) }
                            var passwordUpdateError by remember { mutableStateOf<String?>(null) }
                            var passwordUpdateSuccess by remember { mutableStateOf<String?>(null) }

                            OutlinedTextField(
                                value = currentPassword,
                                onValueChange = { currentPassword = it },
                                label = { Text(tr("Current Password")) },
                                singleLine = true,
                                visualTransformation = if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (currentPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                                        Icon(imageVector = image, contentDescription = null)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = newPassword,
                                onValueChange = { newPassword = it },
                                label = { Text(tr("New Password (min 6 chars)")) },
                                singleLine = true,
                                visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (newPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                                        Icon(imageVector = image, contentDescription = null)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text(tr("Confirm New Password")) },
                                singleLine = true,
                                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    val image = if (confirmPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                        Icon(imageVector = image, contentDescription = null)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            passwordUpdateError?.let {
                                Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            passwordUpdateSuccess?.let {
                                Text(tr(it), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    if (currentPassword.isEmpty() || newPassword.isEmpty()) {
                                        passwordUpdateError = "All fields are required."
                                        return@Button
                                    }
                                    if (newPassword.length < 6) {
                                        passwordUpdateError = "New password must be at least 6 characters."
                                        return@Button
                                    }
                                    if (newPassword != confirmPassword) {
                                        passwordUpdateError = "Passwords do not match."
                                        return@Button
                                    }
                                    passwordUpdateError = null
                                    passwordUpdateSuccess = null
                                    isUpdatingPassword = true
                                    coroutineScope.launch {
                                        val result = runCatching {
                                            NetworkModule.getApi(context).changePassword(
                                                com.healthdecoder.app.model.ChangePasswordRequest(
                                                    currentPassword = currentPassword,
                                                    newPassword = newPassword
                                                )
                                            )
                                        }
                                        isUpdatingPassword = false
                                        result.onSuccess {
                                            // Changing the password revokes every previously-issued
                                            // token server-side — store the fresh one so this device
                                            // doesn't get logged out by its own request.
                                            it.token?.let { fresh -> AppSettings.setAuthToken(context, fresh) }
                                            passwordUpdateSuccess = "Password updated successfully."
                                            currentPassword = ""
                                            newPassword = ""
                                            confirmPassword = ""
                                        }.onFailure { e ->
                                            passwordUpdateError = e.apiErrorMessage() ?: e.message ?: "Failed to update password."
                                        }
                                    }
                                },
                                enabled = !isUpdatingPassword,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isUpdatingPassword) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(tr("Update Password"))
                                }
                            }
                        }
                    }
                }

                // Email Integration Card — hidden while GMAIL_SYNC_ENABLED is off: this was
                // built and tested against one specific Gmail account, and its Gmail API
                // cost/quota behavior for arbitrary public users hasn't been verified.
                if (FeatureFlags.GMAIL_SYNC_ENABLED) {
                var showEmailIntegration by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showEmailIntegration = !showEmailIntegration },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(tr("Email Report Scanner"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    val linked = AppSettings.getLinkedEmail(context)
                                    Text(
                                        text = if (linked != null) "Linked to $linked" else "Not connected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (linked != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { showEmailIntegration = !showEmailIntegration }) {
                                Icon(
                                    imageVector = if (showEmailIntegration) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }

                        if (showEmailIntegration) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            var emailConsent by remember { mutableStateOf(AppSettings.isEmailConsentGranted(context)) }
                            var scanHour by remember { mutableStateOf(AppSettings.getEmailScanHour(context)) }
                            var scanMinute by remember { mutableStateOf(AppSettings.getEmailScanMinute(context)) }
                            var showScanTimePicker by remember { mutableStateOf(false) }
                            var searchPromptInput by remember { mutableStateOf(AppSettings.getEmailSearchPrompt(context)) }

                            fun rescheduleDailyScan() {
                                com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tr("Auto-scan Inbox daily"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(tr("Checks for medical report attachments once a day"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = emailConsent,
                                    onCheckedChange = { checked ->
                                        emailConsent = checked
                                        AppSettings.setEmailConsentGranted(context, checked)
                                        if (checked) {
                                            rescheduleDailyScan()
                                        } else {
                                            com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                        }
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = emailConsent) { showScanTimePicker = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(tr("Scan time"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (emailConsent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    String.format("%02d:%02d", scanHour, scanMinute),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (emailConsent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (showScanTimePicker) {
                                val timeState = rememberTimePickerState(initialHour = scanHour, initialMinute = scanMinute)
                                AlertDialog(
                                    onDismissRequest = { showScanTimePicker = false },
                                    text = { TimePicker(state = timeState) },
                                    confirmButton = {
                                        Button(onClick = {
                                            scanHour = timeState.hour
                                            scanMinute = timeState.minute
                                            AppSettings.setEmailScanTime(context, scanHour, scanMinute)
                                            rescheduleDailyScan()
                                            showScanTimePicker = false
                                        }) { Text(tr("OK")) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showScanTimePicker = false }) { Text(tr("Cancel")) }
                                    }
                                )
                            }

                            Text(tr("Hospital Search Prompt (Optional)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = searchPromptInput,
                                onValueChange = { searchPromptInput = it },
                                label = { Text(tr("e.g. Apollo, Metropolis, Fortis")) },
                                placeholder = { Text(tr("Leave blank to search all reports")) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Text(tr("Translates this intent using AI to target specific lab emails."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            TextButton(
                                onClick = {
                                    AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                    val request = androidx.work.OneTimeWorkRequestBuilder<com.healthdecoder.app.local.EmailScanWorker>()
                                        .setInputData(
                                            androidx.work.Data.Builder()
                                                .putInt(com.healthdecoder.app.local.EmailScanWorker.KEY_LOOKBACK_DAYS, 2)
                                                .build()
                                        )
                                        .build()
                                    val workManager = androidx.work.WorkManager.getInstance(context)
                                    workManager.enqueueUniqueWork(
                                        "ManualEmailScanWork",
                                        androidx.work.ExistingWorkPolicy.REPLACE,
                                        request
                                    )
                                    android.widget.Toast.makeText(context, scanningInboxToast, android.widget.Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        val info = workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
                                        val message = when (info?.state) {
                                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                                val count = info.outputData.getInt(com.healthdecoder.app.local.EmailScanWorker.KEY_FOUND_COUNT, 0)
                                                if (count > 0) "$foundNewReportsToastPrefix $count $foundNewReportsToastSuffix" else noNewReportsInLast2DaysToast
                                            }
                                            else -> scanFailedCheckSettingsToast
                                        }
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Scan Now (last 2 days)"))
                            }

                            TextButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        LocalStore.getDatabase(context).processedEmailDao().deleteAll()
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, emailScanHistoryClearedToast, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(tr("Clear Email Scan History"), color = MaterialTheme.colorScheme.error)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            var emailType by remember { mutableStateOf(AppSettings.getLinkedEmailType(context) ?: "gmail") }
                            var userEmailInput by remember { mutableStateOf(AppSettings.getLinkedEmail(context) ?: "") }
                            var imapHostInput by remember { mutableStateOf(AppSettings.getImapHost(context)) }
                            var imapPortInput by remember { mutableStateOf(AppSettings.getImapPort(context).toString()) }
                            var imapPasswordInput by remember { mutableStateOf(SecureKeyManager.getImapPassword(context) ?: "") }
                            var oauthTokenInput by remember { mutableStateOf(SecureKeyManager.getEmailToken(context) ?: "") }

                            Text(tr("Email Provider"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = emailType == "gmail",
                                    onClick = { emailType = "gmail" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) { Text(tr("Gmail (OAuth)")) }
                                SegmentedButton(
                                    selected = emailType == "imap",
                                    onClick = { emailType = "imap" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) { Text(tr("Other (IMAP)")) }
                            }

                            if (emailType == "gmail") {
                                val linkedEmail = AppSettings.getLinkedEmail(context)
                                val hasLinkedGmail = !linkedEmail.isNullOrBlank() && AppSettings.getLinkedEmailType(context) == "gmail"
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = if (hasLinkedGmail) "Linked Gmail Account: $linkedEmail" else "No Google Account Linked",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                val token = AppSettings.getAuthToken(context) ?: ""
                                                // Single-use correlator so the medicalscanner://oauth2-link
                                                // redirect that comes back is only trusted if it's the one
                                                // this exact tap requested — see Navigation.kt.
                                                val nonce = java.util.UUID.randomUUID().toString()
                                                AppSettings.setPendingOAuthNonce(context, nonce)
                                                val url = com.healthdecoder.app.network.NetworkModule.getFullImageUrl(context, "api/auth/google?state=link|$token|$nonce")
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                context.startActivity(intent)
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(if (hasLinkedGmail) "Re-link Google Account" else "Link Google Account")
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = userEmailInput,
                                    onValueChange = { userEmailInput = it },
                                    label = { Text(tr("Email Address")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                                )
                                OutlinedTextField(
                                    value = imapHostInput,
                                    onValueChange = { imapHostInput = it },
                                    label = { Text(tr("IMAP Host")) },
                                    placeholder = { Text("imap.mail.yahoo.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = imapPortInput,
                                    onValueChange = { imapPortInput = it },
                                    label = { Text(tr("IMAP Port")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = imapPasswordInput,
                                    onValueChange = { imapPasswordInput = it },
                                    label = { Text(tr("App Password / Password")) },
                                    placeholder = { Text(tr("Secure App Password")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    visualTransformation = PasswordVisualTransformation()
                                )
                                Text(tr("Note: Gmail, Yahoo, and Outlook require you to generate an 'App Password' from your account security settings to log in via IMAP."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (emailType == "gmail") {
                                        val hasLinkedGmail = !AppSettings.getLinkedEmail(context).isNullOrBlank() && AppSettings.getLinkedEmailType(context) == "gmail"
                                        if (!hasLinkedGmail) {
                                            android.widget.Toast.makeText(context, pleaseLinkGoogleAccountToast, android.widget.Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        AppSettings.setEmailSearchPrompt(context, searchPromptInput)

                                        if (emailConsent) {
                                            com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                        } else {
                                            com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                        }

                                        android.widget.Toast.makeText(context, emailSettingsSavedToast, android.widget.Toast.LENGTH_SHORT).show()
                                        showEmailIntegration = false
                                    } else {
                                        if (userEmailInput.isNotBlank()) {
                                            AppSettings.setLinkedEmail(context, userEmailInput)
                                            AppSettings.setLinkedEmailType(context, emailType)
                                            AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                            AppSettings.setImapHost(context, imapHostInput)
                                            AppSettings.setImapPort(context, imapPortInput.toIntOrNull() ?: 993)
                                            SecureKeyManager.setImapPassword(context, imapPasswordInput)

                                            if (emailConsent) {
                                                com.healthdecoder.app.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                            } else {
                                                com.healthdecoder.app.reminder.EmailScanReminderManager.cancel(context)
                                            }

                                            android.widget.Toast.makeText(context, emailSettingsSavedToast, android.widget.Toast.LENGTH_SHORT).show()
                                            showEmailIntegration = false
                                        } else {
                                            android.widget.Toast.makeText(context, pleaseEnterEmailAddressToast, android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(tr("Save Settings"))
                            }
                        }

                    }
                }
                }

                // isLoggedIn (session exists), not account != null (profile fetch succeeded) —
                // otherwise a failed getMe() on a logged-in user hides Log Out entirely, leaving
                // no way to sign out short of clearing app data.
                if (AppSettings.isLoggedIn(context)) {
                    OutlinedButton(
                        onClick = {
                            AppSettings.logout(context)
                            onLoggedOut()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                    ) { Text(tr("Log Out")) }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { deleteAccountError = null; showDeleteAccountDialog = true },
                        enabled = !isDeletingAccount,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Delete Account"))
                    }

                    deleteAccountError?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
        }
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountDialog = false },
            title = { Text(tr("Delete account permanently?"), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    tr("This permanently deletes your Health Decoder account AND all medical " +
                        "records stored on this device — reports, reminders, appointments, and " +
                        "family profiles. This cannot be undone."),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { deleteAccount() },
                    enabled = !isDeletingAccount,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isDeletingAccount) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onError, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(tr("Delete Permanently"), color = MaterialTheme.colorScheme.onError)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }, enabled = !isDeletingAccount) {
                    Text(tr("Cancel"))
                }
            }
        )
    }

    if (showFamilyManager) {
        FamilyManagerDialog(
            onDismiss = { showFamilyManager = false },
            onChanged = { coroutineScope.launch { patients = LocalRepository.listPatients(context) } }
        )
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
                }) { Text(tr("Remove Duplicates")) }
            },
            dismissButton = { TextButton(onClick = { showDupDialog = false }) { Text(tr("Cancel")) } }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFC62828)) },
            title = { Text(tr("Delete everything?")) },
            text = { Text(tr("This permanently deletes ALL reports, medicines, pending tests and images. This cannot be undone.")) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAllDialog = false
                        coroutineScope.launch {
                            deletingAll = true
                            deleteAllResult = null
                            runCatching {
                                com.healthdecoder.app.reminder.MedicineReminderManager.cancelAll(context)
                                com.healthdecoder.app.reminder.MedicineScheduleStore.clearAll(context)
                                val appointmentsList = com.healthdecoder.app.reminder.AppointmentStore.loadAll(context)
                                appointmentsList.forEach { com.healthdecoder.app.reminder.AppointmentReminderManager.cancel(context, it.id) }
                                com.healthdecoder.app.reminder.AppointmentStore.clearAll(context)
                            }
                            runCatching { com.healthdecoder.app.local.LocalRepository.clearAllData(context) }
                            deletingAll = false
                            deleteAllResult = "All data deleted."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text(tr("Delete Everything")) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }, enabled = !deletingAll) {
                    Text(tr("Cancel"))
                }
            }
        )
    }
}
