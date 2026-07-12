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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.example.medicalscanner.model.KeyAssignment
import com.example.medicalscanner.model.UserAccount
import com.example.medicalscanner.network.AccountSync
import com.example.medicalscanner.network.NetworkModule
import com.example.medicalscanner.network.httpCode
import com.example.medicalscanner.network.apiErrorMessage
import kotlinx.coroutines.launch

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
