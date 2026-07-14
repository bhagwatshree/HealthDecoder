package com.example.medicalscanner.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Email
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
import androidx.fragment.app.FragmentActivity
import com.example.medicalscanner.auth.BiometricHelper
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.LocalStore
import com.example.medicalscanner.local.SecureKeyManager
import com.example.medicalscanner.model.KeyAssignment
import com.example.medicalscanner.model.UserAccount
import com.example.medicalscanner.network.AccountSync
import com.example.medicalscanner.network.NetworkModule
import com.example.medicalscanner.network.httpCode
import com.example.medicalscanner.network.apiErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var account by remember { mutableStateOf<UserAccount?>(null) }
    var assignment by remember { mutableStateOf<KeyAssignment?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var isFingerprintEnabled by remember { mutableStateOf(AppSettings.isBiometricEnabled(context)) }
    var fingerprintError by remember { mutableStateOf<String?>(null) }

    // Just viewing the screen must never consume a free-tier issuance — use the read-only
    // peek endpoint here. AccountSync.refreshAssignedKeys (which does consume one, and also
    // updates the locally cached active key) is only called after actually saving a new key.
    fun load() {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account", fontWeight = FontWeight.Bold) },
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
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
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
                            "Plan: ${acc.plan.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onNavigateToSettings),
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
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Server Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Server address, language, voice, backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                assignment?.let { a ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Today's AI Usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            when (a.billedTo) {
                                "own" -> Text("Using your own API key — unlimited, not counted against the free tier.", style = MaterialTheme.typography.bodySmall)
                                "premium" -> Text("Premium plan — unlimited usage.", style = MaterialTheme.typography.bodySmall)
                                else -> {
                                    val used = a.usageToday.coerceAtMost(a.limit)
                                    LinearProgressIndicator(
                                        progress = { if (a.limit > 0) used.toFloat() / a.limit else 0f },
                                        modifier = Modifier.fillMaxWidth().height(8.dp),
                                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                    Text("$used / ${a.limit} free scans used today", style = MaterialTheme.typography.bodySmall)
                                    if (a.quotaExceeded) {
                                        Text(
                                            "Today's free quota is used up. It resets tomorrow.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
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
                        Text("App Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                            ) { Text("Light") }
                            SegmentedButton(
                                selected = currentThemeMode == AppSettings.THEME_DARK,
                                onClick = {
                                    currentThemeMode = AppSettings.THEME_DARK
                                    AppSettings.setThemeMode(context, AppSettings.THEME_DARK)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                            ) { Text("Dark") }
                            SegmentedButton(
                                selected = currentThemeMode == AppSettings.THEME_SYSTEM,
                                onClick = {
                                    currentThemeMode = AppSettings.THEME_SYSTEM
                                    AppSettings.setThemeMode(context, AppSettings.THEME_SYSTEM)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                            ) { Text("System") }
                        }
                    }
                }

                if (BiometricHelper.isBiometricsAvailable(context)) {
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
                                    Text("Fingerprint Login", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Sign in quickly using fingerprint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                                title = "Enable Fingerprint Login",
                                                subtitle = "Confirm fingerprint to register",
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
                                                            fingerprintError = "Error: Please log in again to configure fingerprint."
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
                            Text("Change Password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                label = { Text("Current Password") },
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
                                label = { Text("New Password (min 6 chars)") },
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
                                label = { Text("Confirm New Password") },
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
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            passwordUpdateSuccess?.let {
                                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                                                com.example.medicalscanner.model.ChangePasswordRequest(
                                                    currentPassword = currentPassword,
                                                    newPassword = newPassword
                                                )
                                            )
                                        }
                                        isUpdatingPassword = false
                                        result.onSuccess {
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
                                    Text("Update Password")
                                }
                            }
                        }
                    }
                }

                // Email Integration Card
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
                                    Text("Email Report Scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                com.example.medicalscanner.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Auto-scan Inbox daily", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Checks for medical report attachments once a day", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = emailConsent,
                                    onCheckedChange = { checked ->
                                        emailConsent = checked
                                        AppSettings.setEmailConsentGranted(context, checked)
                                        if (checked) {
                                            rescheduleDailyScan()
                                        } else {
                                            com.example.medicalscanner.reminder.EmailScanReminderManager.cancel(context)
                                        }
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable(enabled = emailConsent) { showScanTimePicker = true },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Scan time",
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
                                        }) { Text("OK") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showScanTimePicker = false }) { Text("Cancel") }
                                    }
                                )
                            }

                            Text("Hospital Search Prompt (Optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = searchPromptInput,
                                onValueChange = { searchPromptInput = it },
                                label = { Text("e.g. Apollo, Metropolis, Fortis") },
                                placeholder = { Text("Leave blank to search all reports") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            Text(
                                "Translates this intent using AI to target specific lab emails.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            TextButton(
                                onClick = {
                                    AppSettings.setEmailSearchPrompt(context, searchPromptInput)
                                    val request = androidx.work.OneTimeWorkRequestBuilder<com.example.medicalscanner.local.EmailScanWorker>()
                                        .setInputData(
                                            androidx.work.Data.Builder()
                                                .putInt(com.example.medicalscanner.local.EmailScanWorker.KEY_LOOKBACK_DAYS, 2)
                                                .build()
                                        )
                                        .build()
                                    val workManager = androidx.work.WorkManager.getInstance(context)
                                    workManager.enqueueUniqueWork(
                                        "ManualEmailScanWork",
                                        androidx.work.ExistingWorkPolicy.REPLACE,
                                        request
                                    )
                                    android.widget.Toast.makeText(context, "Scanning inbox for new reports…", android.widget.Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        val info = workManager.getWorkInfoByIdFlow(request.id).first { it != null && it.state.isFinished }
                                        val message = when (info?.state) {
                                            androidx.work.WorkInfo.State.SUCCEEDED -> {
                                                val count = info.outputData.getInt(com.example.medicalscanner.local.EmailScanWorker.KEY_FOUND_COUNT, 0)
                                                if (count > 0) "Found $count new report(s) — check your notifications." else "No new reports found in the last 2 days."
                                            }
                                            else -> "Scan failed. Check your email settings and try again."
                                        }
                                        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Scan Now (last 2 days)")
                            }

                            TextButton(
                                onClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        LocalStore.getDatabase(context).processedEmailDao().deleteAll()
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(context, "Email scan history cleared — reports can be re-detected.", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                enabled = !AppSettings.getLinkedEmail(context).isNullOrBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clear Email Scan History", color = MaterialTheme.colorScheme.error)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            var emailType by remember { mutableStateOf(AppSettings.getLinkedEmailType(context) ?: "gmail") }
                            var userEmailInput by remember { mutableStateOf(AppSettings.getLinkedEmail(context) ?: "") }
                            var imapHostInput by remember { mutableStateOf(AppSettings.getImapHost(context)) }
                            var imapPortInput by remember { mutableStateOf(AppSettings.getImapPort(context).toString()) }
                            var imapPasswordInput by remember { mutableStateOf(SecureKeyManager.getImapPassword(context) ?: "") }
                            var oauthTokenInput by remember { mutableStateOf(SecureKeyManager.getEmailToken(context) ?: "") }

                            Text("Email Provider", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                SegmentedButton(
                                    selected = emailType == "gmail",
                                    onClick = { emailType = "gmail" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) { Text("Gmail (OAuth)") }
                                SegmentedButton(
                                    selected = emailType == "imap",
                                    onClick = { emailType = "imap" },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) { Text("Other (IMAP)") }
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
                                                val url = com.example.medicalscanner.network.NetworkModule.getFullImageUrl(context, "api/auth/google?state=link|$token")
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
                                    label = { Text("Email Address") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                                )
                                OutlinedTextField(
                                    value = imapHostInput,
                                    onValueChange = { imapHostInput = it },
                                    label = { Text("IMAP Host") },
                                    placeholder = { Text("imap.mail.yahoo.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = imapPortInput,
                                    onValueChange = { imapPortInput = it },
                                    label = { Text("IMAP Port") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = imapPasswordInput,
                                    onValueChange = { imapPasswordInput = it },
                                    label = { Text("App Password / Password") },
                                    placeholder = { Text("Secure App Password") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    visualTransformation = PasswordVisualTransformation()
                                )
                                Text(
                                    "Note: Gmail, Yahoo, and Outlook require you to generate an 'App Password' from your account security settings to log in via IMAP.",
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
                                            android.widget.Toast.makeText(context, "Please link your Google Account first.", android.widget.Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        AppSettings.setEmailSearchPrompt(context, searchPromptInput)

                                        if (emailConsent) {
                                            com.example.medicalscanner.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                        } else {
                                            com.example.medicalscanner.reminder.EmailScanReminderManager.cancel(context)
                                        }

                                        android.widget.Toast.makeText(context, "Email settings saved successfully.", android.widget.Toast.LENGTH_SHORT).show()
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
                                                com.example.medicalscanner.reminder.EmailScanReminderManager.scheduleDaily(context, scanHour, scanMinute)
                                            } else {
                                                com.example.medicalscanner.reminder.EmailScanReminderManager.cancel(context)
                                            }

                                            android.widget.Toast.makeText(context, "Email settings saved successfully.", android.widget.Toast.LENGTH_SHORT).show()
                                            showEmailIntegration = false
                                        } else {
                                            android.widget.Toast.makeText(context, "Please enter an email address.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Save Settings")
                            }
                        }

                    }
                }

                OutlinedButton(
                    onClick = {
                        AppSettings.logout(context)
                        onLoggedOut()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828))
                ) { Text("Log Out") }
            }
        }
    }
}
