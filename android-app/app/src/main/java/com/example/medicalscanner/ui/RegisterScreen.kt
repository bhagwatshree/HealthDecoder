package com.example.medicalscanner.ui

import android.app.Activity
import android.app.DatePickerDialog
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
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.medicalscanner.auth.PhoneAuthHelper
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.model.SignupRequest
import com.example.medicalscanner.network.AccountSync
import com.example.medicalscanner.network.NetworkModule
import kotlinx.coroutines.launch
import java.util.Calendar

private val GENDER_OPTIONS = listOf(
    "Male" to "male",
    "Female" to "female",
    "Other" to "other",
    "Prefer not to say" to "prefer_not_to_say"
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Registration: name/surname/DOB/gender + email/password + phone number, with the phone
 * verified by OTP (Firebase Phone Auth) before the account is created. Email and phone each
 * get a UNIQUE constraint on the same user row server-side (see db_init.sql), so every account
 * has exactly one of each — no email shared across two phone numbers or vice versa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    prepopulatedMsisdn: String? = null,
    onRegistered: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val coroutineScope = rememberCoroutineScope()

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var dobMillis by remember { mutableStateOf<Long?>(null) }
    var dobDisplay by remember { mutableStateOf("") }
    var genderIndex by remember { mutableStateOf(-1) }
    var genderMenuExpanded by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val initialPhone = remember(prepopulatedMsisdn) {
        prepopulatedMsisdn?.removePrefix("+91")?.trim() ?: ""
    }
    var phoneDigits by remember { mutableStateOf(initialPhone) }
    var otpCode by remember { mutableStateOf("") }

    var verificationId by remember { mutableStateOf<String?>(null) }
    var otpSent by remember { mutableStateOf(false) }
    var isSendingOtp by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun validateForm(): String? {
        if (firstName.trim().isEmpty() || lastName.trim().isEmpty()) return "Enter your first and last name."
        if (dobMillis == null) return "Select your date of birth."
        if (genderIndex == -1) return "Select a gender."
        if (email.trim().isEmpty() || !email.contains("@")) return "Enter a valid email."
        if (password.length < 6) return "Password must be at least 6 characters."
        if (phoneDigits.length != 10) return "Enter a valid 10-digit mobile number."
        return null
    }

    fun signupWithIdToken(idToken: String) {
        coroutineScope.launch {
            val cal = Calendar.getInstance().apply { timeInMillis = dobMillis!! }
            val dob = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            val result = runCatching {
                val api = NetworkModule.getApi(context)
                api.signup(
                    SignupRequest(
                        firstName = firstName.trim(),
                        lastName = lastName.trim(),
                        dateOfBirth = dob,
                        gender = GENDER_OPTIONS[genderIndex].second,
                        email = email.trim(),
                        password = password,
                        phoneIdToken = idToken
                    )
                )
            }
            isVerifying = false
            PhoneAuthHelper.signOut()
            result.onSuccess { auth ->
                AppSettings.setAuthToken(context, auth.token)
                AppSettings.setUserEmail(context, auth.user.email)
                runCatching { AccountSync.refreshAssignedKeys(context) }
                onRegistered()
            }.onFailure { e ->
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Check your connection and try again."
            }
        }
    }

    fun sendOtp() {
        val validationError = validateForm()
        if (validationError != null) {
            errorMessage = validationError
            return
        }
        if (activity == null) {
            errorMessage = "Couldn't start phone verification. Please try again."
            return
        }
        errorMessage = null
        isSendingOtp = true
        PhoneAuthHelper.sendOtp(activity, "+91$phoneDigits") { event ->
            isSendingOtp = false
            when (event) {
                is PhoneAuthHelper.OtpEvent.CodeSent -> {
                    verificationId = event.verificationId
                    otpSent = true
                }
                is PhoneAuthHelper.OtpEvent.AutoVerified -> {
                    isVerifying = true
                    signupWithIdToken(event.idToken)
                }
                is PhoneAuthHelper.OtpEvent.Failed -> {
                    errorMessage = event.message
                }
            }
        }
    }

    fun verifyOtpAndSignup() {
        val id = verificationId ?: return
        if (otpCode.length < 6) {
            errorMessage = "Enter the 6-digit code sent to your phone."
            return
        }
        errorMessage = null
        isVerifying = true
        PhoneAuthHelper.verifyOtp(id, otpCode) { result ->
            result.onSuccess { signupWithIdToken(it) }
                .onFailure {
                    isVerifying = false
                    errorMessage = "Incorrect or expired code. Please try again."
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create your account", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!otpSent) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dobDisplay,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date of birth") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable {
                                val cal = Calendar.getInstance()
                                DatePickerDialog(
                                    context,
                                    { _, year, month, dayOfMonth ->
                                        val picked = Calendar.getInstance().apply { set(year, month, dayOfMonth, 0, 0, 0) }
                                        dobMillis = picked.timeInMillis
                                        dobDisplay = "%02d/%02d/%04d".format(dayOfMonth, month + 1, year)
                                    },
                                    cal.get(Calendar.YEAR) - 25, cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                                ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                            }
                    )
                }

                ExposedDropdownMenuBox(
                    expanded = genderMenuExpanded,
                    onExpandedChange = { genderMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = if (genderIndex >= 0) GENDER_OPTIONS[genderIndex].first else "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Gender") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable)
                    )
                    ExposedDropdownMenu(expanded = genderMenuExpanded, onDismissRequest = { genderMenuExpanded = false }) {
                        GENDER_OPTIONS.forEachIndexed { index, (label, _) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { genderIndex = index; genderMenuExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        val description = if (passwordVisible) "Hide password" else "Show password"
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(imageVector = image, contentDescription = description)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phoneDigits,
                    onValueChange = { phoneDigits = it.filter { c -> c.isDigit() }.take(10) },
                    label = { Text("Mobile number") },
                    singleLine = true,
                    leadingIcon = { Text("+91", modifier = Modifier.padding(start = 12.dp)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { sendOtp() },
                    enabled = !isSendingOtp,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSendingOtp) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send OTP", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Text(
                    "Enter the 6-digit code sent to +91$phoneDigits",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { otpCode = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("OTP code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { verifyOtpAndSignup() },
                    enabled = !isVerifying,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isVerifying) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Verify & Create Account", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { otpSent = false; otpCode = ""; errorMessage = null }) {
                    Text("Change phone number")
                }
            }
        }
    }
}
