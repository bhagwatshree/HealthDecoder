package com.example.medicalscanner.ui

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medicalscanner.model.DashboardData
import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.model.MedLogEntry
import com.example.medicalscanner.model.MedicationBulkItem
import com.example.medicalscanner.model.MedicationBulkRequest
import com.example.medicalscanner.model.MedicationHistory
import com.example.medicalscanner.model.PendingTest
import com.example.medicalscanner.network.NetworkModule
import com.example.medicalscanner.local.BackgroundScanScheduler
import com.example.medicalscanner.local.ScanJobStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportListScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCompare: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToTrends: () -> Unit = {},
    onNavigateToAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
    reloadKey: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var dashboardData by remember { mutableStateOf<DashboardData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Report IDs matched by full-text search (includes OCR'd document text), refreshed
    // as the user types. Complements the in-memory field matching below.
    var ftsMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            ftsMatchIds = emptySet()
        } else {
            kotlinx.coroutines.delay(250) // debounce keystrokes
            ftsMatchIds = runCatching { LocalRepository.searchReportIds(context, searchQuery) }
                .getOrDefault(emptySet())
        }
    }

    // Tab state: 0 = Today's Meds, 1 = Reports, 2 = Medication Tracker, 3 = Pending Tests
    var selectedTab by remember { mutableIntStateOf(0) }

    // Duration filter: null=all, 1m, 3m, 6m
    var selectedPeriod by remember { mutableStateOf<String?>(null) }

    // Dialog for adding manual pending test
    var showAddTestDialog by remember { mutableStateOf(false) }
    var newPatientName by remember { mutableStateOf("") }
    var newTestName by remember { mutableStateOf("") }
    var newDueDate by remember { mutableStateOf("") }
    var selectedMedForDetails by remember { mutableStateOf<MedicationHistory?>(null) }

    // Multi-select state for the Medication Tracker tab (bulk delete / change frequency)
    var medSelectionMode by remember { mutableStateOf(false) }
    val selectedMedKeys = remember { mutableStateListOf<String>() }
    var showBulkFrequencyDialog by remember { mutableStateOf(false) }
    fun medKey(m: MedicationHistory) = "${m.reportId}::${m.medicineName}"
    fun exitSelection() {
        medSelectionMode = false
        selectedMedKeys.clear()
    }
    fun toggleMed(m: MedicationHistory) {
        val k = medKey(m)
        if (selectedMedKeys.contains(k)) selectedMedKeys.remove(k) else selectedMedKeys.add(k)
    }

    // Fetch dashboard data
    val loadDashboard = {
        coroutineScope.launch {
            isLoading = true
            errorMessage = ""
            try {
                dashboardData = LocalRepository.getDashboard(context, selectedPeriod)
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to fetch dashboard data. Make sure your local server is running and configured correctly in settings."
            } finally {
                isLoading = false
            }
        }
    }

    // Reload on first launch and whenever reloadKey changes (e.g. returning from IP settings)
    LaunchedEffect(reloadKey) {
        loadDashboard()
    }

    // Observe background jobs completing to refresh the dashboard list
    LaunchedEffect(Unit) {
        BackgroundScanScheduler.onJobCompleted.collect {
            loadDashboard()
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
                        Image(
                            painter = painterResource(id = com.example.medicalscanner.R.drawable.medical_assist_logo),
                            contentDescription = "Medical Assist Logo",
                            modifier = Modifier
                                .height(36.dp)
                                .width(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { loadDashboard() }) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onNavigateToTrends) {
                        Icon(imageVector = Icons.Default.ShowChart, contentDescription = "Health Trends")
                    }
                    IconButton(onClick = onNavigateToChat) {
                        Icon(imageVector = Icons.Default.QuestionAnswer, contentDescription = "Ask AI Assistant")
                    }
                    IconButton(onClick = onNavigateToCompare) {
                        Icon(imageVector = Icons.Default.CompareArrows, contentDescription = "Compare Reports")
                    }
                    IconButton(onClick = onNavigateToAccount) {
                        Icon(imageVector = Icons.Default.AccountCircle, contentDescription = "Account")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            if (medSelectionMode && selectedTab == 2) {
                val allMeds = dashboardData?.medicationHistory ?: emptyList()
                val selected = allMeds.filter { selectedMedKeys.contains(medKey(it)) }
                Surface(
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                        Text(
                            text = "${selected.size} selected",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { showBulkFrequencyDialog = true },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Frequency")
                        }
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        LocalRepository.bulkDeleteMedications(
                                            context,
                                            selected.map { MedicationBulkItem(it.reportId, it.medicineName, it.patientName) }
                                        )
                                        exitSelection()
                                        loadDashboard()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        errorMessage = "Failed to delete the selected medicines."
                                    }
                                }
                            },
                            enabled = selected.isNotEmpty(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == 1) {
                FloatingActionButton(
                    onClick = onNavigateToScan,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.AddPhotoAlternate, contentDescription = "Scan New")
                        Text("Scan Report", fontWeight = FontWeight.Bold)
                    }
                }
            } else if (selectedTab == 3) {
                FloatingActionButton(
                    onClick = { showAddTestDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = "Add Test")
                        Text("Add Test Reminder", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Background scan progress indicators
            BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)

            // Dashboard Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                edgePadding = 0.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Today's Meds", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                    icon = { Icon(imageVector = Icons.Default.Alarm, contentDescription = "Today's Medicines") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Reports History") },
                    icon = { Icon(imageVector = Icons.Default.History, contentDescription = "Reports") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Medication Tracker") },
                    icon = { Icon(imageVector = Icons.Default.Medication, contentDescription = "Medications") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Pending Tests") },
                    icon = { Icon(imageVector = Icons.Default.NotificationsActive, contentDescription = "Pending Tests") }
                )
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    val hint = when (selectedTab) {
                        0 -> "Search today's medicines..."
                        1 -> "Search reports, patient, or document text..."
                        2 -> "Search medicine name, dosage..."
                        else -> "Search test name, patient..."
                    }
                    Text(hint)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Duration Filter Chips (only on Reports tab)
            if (selectedTab == 1) {
                val periods = listOf(
                    null to "All Time",
                    "1m" to "1 Month",
                    "3m" to "3 Months",
                    "6m" to "6 Months"
                )
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(periods) { (value, label) ->
                        FilterChip(
                            selected = selectedPeriod == value,
                            onClick = {
                                selectedPeriod = value
                                loadDashboard()
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            leadingIcon = if (selectedPeriod == value) {{
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(14.dp)
                                )
                            }} else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Connection Warning / Errors
            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onNavigateToSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Configure IP Address")
                            }
                        }
                    }
                }
            }

            // Dashboard Content Panels
            Box(modifier = Modifier.weight(1f)) {
                if (isLoading && dashboardData == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (dashboardData == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Disconnected",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Text(
                                text = "Unable to fetch data",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = { loadDashboard() }) {
                                Text("Retry Connection")
                            }
                        }
                    }
                } else {
                    val data = dashboardData!!

                    when (selectedTab) {
                        0 -> { // Today's Meds
                            TodaysMedicinesTab(
                                medicationHistory = data.medicationHistory
                            )
                        }

                        1 -> { // Reports History list
                            val filteredReports = data.reports.filter { report ->
                                val patientMatch = report.patientName?.contains(searchQuery, ignoreCase = true) == true
                                val commentsMatch = report.comments?.contains(searchQuery, ignoreCase = true) == true
                                val typeMatch = report.reportType?.contains(searchQuery, ignoreCase = true) == true
                                val medMatch = report.medications.any { it.name.contains(searchQuery, ignoreCase = true) }
                                val textMatch = report.id in ftsMatchIds // full-text hit inside the document
                                patientMatch || commentsMatch || typeMatch || medMatch || textMatch
                            }

                            if (filteredReports.isEmpty()) {
                                EmptyStateView(
                                    icon = Icons.Default.History,
                                    title = if (searchQuery.isNotEmpty()) "No matching reports" else "No scanned history",
                                    description = if (searchQuery.isNotEmpty()) "Try searching a different name" else "Press 'Scan Report' to upload your first prescription."
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (data.testInferences.isNotEmpty() && searchQuery.isEmpty()) {
                                        item {
                                            ClinicalInsightsPanel(
                                                inferences = data.testInferences,
                                                onInferenceClick = { reportId -> onNavigateToDetail(reportId) }
                                            )
                                        }
                                    }
                                    
                                    items(filteredReports, key = { it.id }) { report ->
                                        ReportItemCard(report = report, onClick = { onNavigateToDetail(report.id) })
                                    }
                                }
                            }
                        }
                        
                        2 -> { // Medication dosage tracker (Current vs Previous)
                            val filteredMeds = data.medicationHistory.filter { med ->
                                med.medicineName.contains(searchQuery, ignoreCase = true) ||
                                        med.patientName.contains(searchQuery, ignoreCase = true)
                            }

                            if (filteredMeds.isEmpty()) {
                                EmptyStateView(
                                    icon = Icons.Default.Medication,
                                    title = if (searchQuery.isNotEmpty()) "No matching medications" else "No medication history",
                                    description = "All medications extracted from prescriptions will show up here along with their dosage history."
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredMeds, key = { "${it.patientName}-${it.medicineName}" }) { med ->
                                        MedicationHistoryCard(
                                            med = med,
                                            selectionMode = medSelectionMode,
                                            selected = selectedMedKeys.contains(medKey(med)),
                                            onClick = {
                                                if (medSelectionMode) toggleMed(med)
                                                else selectedMedForDetails = med
                                            },
                                            onLongClick = {
                                                if (!medSelectionMode) medSelectionMode = true
                                                toggleMed(med)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        
                        3 -> { // Pending Tests tab
                            val filteredTests = data.pendingTests.filter { test ->
                                test.testName.contains(searchQuery, ignoreCase = true) ||
                                        test.patientName.contains(searchQuery, ignoreCase = true)
                            }

                            if (filteredTests.isEmpty()) {
                                EmptyStateView(
                                    icon = Icons.Default.NotificationsActive,
                                    title = if (searchQuery.isNotEmpty()) "No matching tests" else "No pending tests",
                                    description = "Tests recommended in prescriptions are automatically tracked. You can also add them manually."
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredTests, key = { it.id }) { test ->
                                        PendingTestCard(
                                            test = test,
                                            onResolveClick = {
                                                if (test.resolvedReportId != null) {
                                                    onNavigateToDetail(test.resolvedReportId)
                                                }
                                            },
                                            onDeleteClick = {
                                                coroutineScope.launch {
                                                    try {
                                                        LocalRepository.deletePendingTest(context, test.id)
                                                        loadDashboard()
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                        errorMessage = "Failed to delete test reminder."
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Add manual pending test Dialog
        if (showAddTestDialog) {
            AlertDialog(
                onDismissRequest = { showAddTestDialog = false },
                title = { Text("Add Recommended Test", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = newPatientName,
                            onValueChange = { newPatientName = it },
                            label = { Text("Patient Name") },
                            placeholder = { Text("e.g. John Doe") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newTestName,
                            onValueChange = { newTestName = it },
                            label = { Text("Recommended Test Name") },
                            placeholder = { Text("e.g. Blood Sugar Fasting (BSF)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newDueDate,
                            onValueChange = { newDueDate = it },
                            label = { Text("Estimated Due Date (YYYY-MM-DD)") },
                            placeholder = { Text("e.g. 2026-09-22") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                if (newPatientName.isBlank() || newTestName.isBlank()) {
                                    errorMessage = "Patient and Test names are required."
                                    showAddTestDialog = false
                                    return@launch
                                }
                                
                                try {
                                    LocalRepository.createPendingTest(context, newPatientName, newTestName, newDueDate)
                                    showAddTestDialog = false
                                    // Reset fields
                                    newPatientName = ""
                                    newTestName = ""
                                    newDueDate = ""
                                    loadDashboard()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    errorMessage = "Failed to save test reminder."
                                    showAddTestDialog = false
                                }
                            }
                        },
                        enabled = newPatientName.isNotBlank() && newTestName.isNotBlank()
                    ) {
                        Text("Add Reminder")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddTestDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
        
        if (selectedMedForDetails != null) {
            MedicationDetailsDialog(
                med = selectedMedForDetails!!,
                onDismiss = { selectedMedForDetails = null },
                onUpdateSuccess = {
                    selectedMedForDetails = null
                    loadDashboard()
                }
            )
        }

        // Bulk "Change Frequency" dialog for the selected medicines
        if (showBulkFrequencyDialog) {
            val allMeds = dashboardData?.medicationHistory ?: emptyList()
            val selected = allMeds.filter { selectedMedKeys.contains(medKey(it)) }
            var bulkFrequency by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showBulkFrequencyDialog = false },
                title = { Text("Change Frequency", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Apply a new frequency to all ${selected.size} selected medicine(s).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = bulkFrequency,
                            onValueChange = { bulkFrequency = it },
                            label = { Text("New Frequency") },
                            placeholder = { Text("e.g. 1-0-1, twice daily, as needed") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Tip: 1-0-1 = morning & night, 1-1-1 = three times a day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    LocalRepository.bulkUpdateMedications(
                                        context,
                                        selected.map {
                                            MedicationBulkItem(
                                                reportId = it.reportId,
                                                medicineName = it.medicineName,
                                                patientName = it.patientName,
                                                frequency = bulkFrequency
                                            )
                                        }
                                    )
                                    showBulkFrequencyDialog = false
                                    exitSelection()
                                    loadDashboard()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    errorMessage = "Failed to update the selected medicines."
                                    showBulkFrequencyDialog = false
                                }
                            }
                        },
                        enabled = bulkFrequency.isNotBlank() && selected.isNotEmpty()
                    ) {
                        Text("Apply to ${selected.size}")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkFrequencyDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Empty",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

fun calculateEndDate(startDateStr: String, durationStr: String): String {
    if (durationStr.isBlank()) return "Ongoing"
    try {
        val cleanDate = startDateStr.split("T")[0] // Handle ISO format
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val date = format.parse(cleanDate) ?: return durationStr
        val calendar = java.util.Calendar.getInstance()
        calendar.time = date
        
        val cleanDuration = durationStr.lowercase().trim()
        val pattern = java.util.regex.Pattern.compile("(\\d+)")
        val matcher = pattern.matcher(cleanDuration)
        if (matcher.find()) {
            val value = matcher.group(1)?.toInt() ?: 0
            if (cleanDuration.contains("day")) {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, value)
            } else if (cleanDuration.contains("week")) {
                calendar.add(java.util.Calendar.WEEK_OF_YEAR, value)
            } else if (cleanDuration.contains("month")) {
                calendar.add(java.util.Calendar.MONTH, value)
            } else {
                calendar.add(java.util.Calendar.DAY_OF_YEAR, value)
            }
            
            val outFormat = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
            return outFormat.format(calendar.time)
        }
    } catch (e: Exception) {
        // Fallback
    }
    return durationStr
}

fun parseRoutine(frequencyStr: String, dosageStr: String): List<Pair<String, Boolean>> {
    val freq = (frequencyStr + " " + dosageStr).lowercase()
    
    var morning = false
    var afternoon = false
    var evening = false
    var night = false
    
    if (freq.contains("1-0-1") || freq.contains("1 - 0 - 1")) {
        morning = true
        night = true
    } else if (freq.contains("1-1-1") || freq.contains("1 - 1 - 1")) {
        morning = true
        afternoon = true
        night = true
    } else if (freq.contains("1-0-0") || freq.contains("1 - 0 - 0")) {
        morning = true
    } else if (freq.contains("0-1-0") || freq.contains("0 - 1 - 0")) {
        afternoon = true
    } else if (freq.contains("0-0-1") || freq.contains("0 - 0 - 1")) {
        night = true
    } else if (freq.contains("twice daily") || freq.contains("twice a day") || freq.contains("bid") || freq.contains("b.i.d.")) {
        morning = true
        night = true
    } else if (freq.contains("thrice daily") || freq.contains("three times") || freq.contains("tid") || freq.contains("t.i.d.")) {
        morning = true
        afternoon = true
        night = true
    } else {
        if (freq.contains("morning") || freq.contains("breakfast") || freq.contains("am")) morning = true
        if (freq.contains("afternoon") || freq.contains("lunch") || freq.contains("noon")) afternoon = true
        if (freq.contains("evening") || freq.contains("snack") || freq.contains("pm")) evening = true
        if (freq.contains("night") || freq.contains("dinner") || freq.contains("bedtime") || freq.contains("hs")) night = true
        
        // If nothing is matched, check if it contains once daily
        if (!morning && !afternoon && !evening && !night) {
            if (freq.contains("once daily") || freq.contains("od") || freq.contains("daily")) {
                morning = true
            } else {
                morning = true // default to morning
            }
        }
    }
    
    return listOf(
        Pair("Morning", morning),
        Pair("Afternoon", afternoon),
        Pair("Evening", evening),
        Pair("Night", night)
    )
}

fun parseFoodInstruction(frequencyStr: String, dosageStr: String): String? {
    val text = (frequencyStr + " " + dosageStr).lowercase()
    return when {
        text.contains("empty stomach") || text.contains("empty") -> "Empty Stomach"
        text.contains("before food") || text.contains("before meal") || text.contains("before breakfast") || text.contains("before lunch") || text.contains("before dinner") -> "Before Meals"
        text.contains("after food") || text.contains("after meal") || text.contains("after breakfast") || text.contains("after lunch") || text.contains("after dinner") -> "After Meals"
        text.contains("with food") || text.contains("with meal") -> "Take With Food"
        else -> null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MedicationHistoryCard(
    med: MedicationHistory,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val activeDays = remember(med.weeklySchedule, med.currentFrequency) {
        getActiveDaysFromSchedule(med.weeklySchedule, med.currentFrequency)
    }
    val routine = remember(med.currentFrequency, med.currentDosage) {
        parseRoutine(med.currentFrequency, med.currentDosage)
    }
    val foodInstruction = remember(med.currentFrequency, med.currentDosage) {
        parseFoodInstruction(med.currentFrequency, med.currentDosage)
    }
    val calculatedEndDate = remember(med.lastUpdated, med.currentDuration) {
        calculateEndDate(med.lastUpdated, med.currentDuration)
    }
    val plainInstruction = remember(med.currentFrequency, med.currentDosage) {
        getFrequencyExplanation(med.currentFrequency, med.currentDosage)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Selection checkbox row (only in multi-select mode)
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (selected) "Selected" else "Not selected",
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (selected) "Selected" else "Tap to select",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Patient Name & Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = med.patientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                val badgeColors = when (med.status.lowercase()) {
                    "active" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32)) // Green
                    "changed" -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100)) // Orange
                    else -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828)) // Red (Discontinued)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColors.first)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = med.status.uppercase(),
                        color = badgeColors.second,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Medicine Name & Food Instruction chip
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = med.medicineName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (foodInstruction != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restaurant,
                            contentDescription = "Food instruction",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = foodInstruction,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // Dynamic Decoded Instruction block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        RoundedCornerShape(10.dp)
                    )
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Instruction",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = plainInstruction,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Routine visual capsules
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Daily Dosage Routine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    routine.forEach { (slot, active) ->
                        val slotIcon = when (slot) {
                            "Morning" -> Icons.Default.WbSunny
                            "Afternoon" -> Icons.Default.WbSunny
                            "Evening" -> Icons.Default.WbSunny
                            else -> Icons.Default.Bedtime
                        }
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer 
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .border(
                                    1.dp,
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) 
                                    else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = slotIcon,
                                    contentDescription = slot,
                                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = slot,
                                    fontSize = 10.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Dosage details + Calendar Schedule strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Dosage & Frequency", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (med.currentFrequency.isNotEmpty()) "${med.currentDosage} (${med.currentFrequency})" else med.currentDosage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Duration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = med.currentDuration.ifEmpty { "Ongoing" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (med.currentDuration.isNotEmpty()) {
                            Text(
                                text = "Ends: $calculatedEndDate",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Calendar Days Strip (Active Highlighted)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = if (med.isOptional) "Optional / As Needed" else "Weekly Schedule",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (med.isOptional) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (med.isOptional) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
                            dayLabels.forEachIndexed { idx, day ->
                                val active = activeDays.getOrElse(idx) { true }
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            if (med.status.lowercase() == "discontinued") {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else if (active) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day,
                                        color = if (med.status.lowercase() == "discontinued") {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        } else if (active) {
                                            Color.White
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Last verified on: ${med.lastUpdated}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                
                if (med.previousDosage.isNotEmpty()) {
                    Text(
                        text = "Prev: ${med.previousDosage} ${med.previousFrequency.takeIf { it.isNotEmpty() }?.let { "($it)" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun PendingTestCard(
    test: PendingTest,
    onResolveClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val isPending = test.status == "Pending"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = test.patientName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isPending) Color(0xFFFFEBEE) else Color(0xFFE8F5E9))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isPending) "Report not available" else "Completed",
                        color = if (isPending) Color(0xFFC62828) else Color(0xFF2E7D32),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Test name and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = test.testName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Reminder",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Due Date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = test.dueDate ?: "No timeline specified",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPending) MaterialTheme.colorScheme.onSurface else Color(0xFF2E7D32)
                    )
                }

                if (!isPending && test.resolvedReportId != null) {
                    TextButton(onClick = onResolveClick) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(imageVector = Icons.Default.Visibility, contentDescription = "View Report", modifier = Modifier.size(16.dp))
                            Text("View Scanned Report", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportItemCard(
    report: MedicalReport,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = report.patientName ?: "Unknown Patient",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                val typeColor = when (report.reportType?.lowercase()) {
                    "prescription" -> Pair(Color(0xFFE3F2FD), Color(0xFF1565C0)) // Blue
                    "lab report" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32)) // Green
                    else -> Pair(Color(0xFFECEFF1), Color(0xFF455A64)) // Grey
                }
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(typeColor.first)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = report.reportType ?: "Report",
                        color = typeColor.second,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = report.reportDate ?: "No Date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (report.medications.isNotEmpty()) {
                    Text(
                        text = "${report.medications.size} Med(s)",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            if (!report.comments.isNullOrBlank()) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = report.comments,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun getFrequencyExplanation(frequency: String, dosage: String): String {
    val freq = frequency.trim().lowercase()
    val dose = dosage.trim().ifEmpty { "1 pill/tablet" }
    
    if (freq.contains("1-0-1") || freq.contains("1 - 0 - 1")) {
        return "Take $dose in the Morning and $dose at Night."
    }
    if (freq.contains("1-1-1") || freq.contains("1 - 1 - 1")) {
        return "Take $dose in the Morning, $dose in the Afternoon, and $dose at Night."
    }
    if (freq.contains("1-0-0") || freq.contains("1 - 0 - 0")) {
        return "Take $dose in the Morning only."
    }
    if (freq.contains("0-1-0") || freq.contains("0 - 1 - 0")) {
        return "Take $dose in the Afternoon only."
    }
    if (freq.contains("0-0-1") || freq.contains("0 - 0 - 1")) {
        return "Take $dose at Night only."
    }
    if (freq.contains("1/2-0-1/2") || freq.contains("0.5-0-0.5")) {
        return "Take half ($dose) in the Morning and half ($dose) at Night."
    }
    if (freq.contains("1/2-0-0") || freq.contains("0.5-0-0")) {
        return "Take half ($dose) in the Morning only."
    }
    if (freq.contains("0-0-1/2") || freq.contains("0-0-0.5")) {
        return "Take half ($dose) at Night only."
    }
    if (freq.contains("twice daily") || freq.contains("twice a day") || freq.contains("bid") || freq.contains("b.i.d.")) {
        return "Take $dose twice a day (typically Morning and Night)."
    }
    if (freq.contains("thrice daily") || freq.contains("three times") || freq.contains("tid") || freq.contains("t.i.d.")) {
        return "Take $dose three times a day (Morning, Afternoon, and Night)."
    }
    if (freq.contains("once daily") || freq.contains("od") || freq.contains("daily")) {
        return "Take $dose once daily."
    }
    if (freq.contains("every 6 hours") || freq.contains("q6h")) {
        return "Take $dose every 6 hours."
    }
    if (freq.contains("every 4 hours") || freq.contains("q4h")) {
        return "Take $dose every 4 hours."
    }
    if (freq.contains("every 8 hours") || freq.contains("q8h")) {
        return "Take $dose every 8 hours."
    }
    if (freq.contains("every 12 hours") || freq.contains("q12h")) {
        return "Take $dose every 12 hours."
    }
    
    if (freq.contains("alternate") || freq.contains("alt")) {
        return "Take $dose every alternate day."
    }
    if (freq.contains("weekly") || freq.contains("once a week")) {
        return "Take $dose once a week."
    }
    if (freq.contains("2 days a week") || freq.contains("twice weekly") || freq.contains("2 days/week") || freq.contains("2 days / week")) {
        return "Take $dose two days a week."
    }
    if (freq.contains("3 days a week") || freq.contains("three times a week") || freq.contains("3 days/week")) {
        return "Take $dose three days a week."
    }
    
    if (freq.contains("as needed") || freq.contains("prn") || freq.contains("sos") || freq.contains("optional") || freq.contains("whenever needed")) {
        return "Take $dose only when needed / optionally."
    }
    
    return "Take $dose according to instructions: $frequency"
}

fun getActiveDaysFromSchedule(weeklySchedule: List<String>, frequencyStr: String): List<Boolean> {
    if (weeklySchedule.isNotEmpty() && !weeklySchedule.contains("Everyday")) {
        if (weeklySchedule.contains("As Needed") || weeklySchedule.contains("Optional")) {
            return listOf(false, false, false, false, false, false, false)
        }
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return days.map { day -> weeklySchedule.any { it.startsWith(day, ignoreCase = true) } }
    }
    
    val freq = frequencyStr.lowercase()
    if (freq.contains("as needed") || freq.contains("prn") || freq.contains("sos") || freq.contains("optional") || freq.contains("whenever needed")) {
        return listOf(false, false, false, false, false, false, false)
    }
    
    val mon = freq.contains("mon") || freq.contains("monday")
    val tue = freq.contains("tue") || freq.contains("tuesday")
    val wed = freq.contains("wed") || freq.contains("wednesday")
    val thu = freq.contains("thu") || freq.contains("thursday")
    val fri = freq.contains("fri") || freq.contains("friday")
    val sat = freq.contains("sat") || freq.contains("saturday")
    val sun = freq.contains("sun") || freq.contains("sunday")
    
    if (mon || tue || wed || thu || fri || sat || sun) {
        return listOf(mon, tue, wed, thu, fri, sat, sun)
    }
    
    if (freq.contains("alternate") || freq.contains("alt")) {
        return listOf(true, false, true, false, true, false, true)
    }
    
    if (freq.contains("weekly") || freq.contains("once a week") || freq.contains("1 day a week")) {
        return listOf(false, false, false, false, false, false, true)
    }
    
    if (freq.contains("2 days a week") || freq.contains("twice weekly") || freq.contains("2 days/week") || freq.contains("2 days / week")) {
        return listOf(true, false, false, true, false, false, false)
    }
    
    if (freq.contains("3 days a week") || freq.contains("three times a week") || freq.contains("3 days/week")) {
        return listOf(true, false, true, false, true, false, false)
    }
    
    return listOf(true, true, true, true, true, true, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailsDialog(
    med: MedicationHistory,
    onDismiss: () -> Unit,
    onUpdateSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var dosage by remember { mutableStateOf(med.currentDosage) }
    var frequency by remember { mutableStateOf(med.currentFrequency) }
    var duration by remember { mutableStateOf(med.currentDuration) }
    var isOptional by remember { mutableStateOf(med.isOptional) }
    var weeklySchedule by remember { mutableStateOf(med.weeklySchedule) }
    var notes by remember { mutableStateOf(med.notes) }
    
    var intakeLogs by remember { mutableStateOf<List<MedLogEntry>>(emptyList()) }
    var isLogsLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    
    val loadLogs = {
        coroutineScope.launch {
            isLogsLoading = true
            try {
                intakeLogs = LocalRepository.getMedicationLogs(context, med.patientName, med.medicineName)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLogsLoading = false
            }
        }
    }
    
    LaunchedEffect(med) {
        loadLogs()
    }
    
    val formatTimestamp = { timestampStr: String ->
        try {
            val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val date = inputFormat.parse(timestampStr)
            if (date != null) {
                val outputFormat = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
                outputFormat.format(date)
            } else {
                timestampStr
            }
        } catch (e: Exception) {
            timestampStr
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = med.medicineName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "Patient: ${med.patientName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "DIRECTIONS FOR PATIENT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = getFrequencyExplanation(frequency, dosage),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "RECORD INTAKE",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        LocalRepository.logMedicationIntake(
                                            context, med.patientName, med.medicineName, "TAKEN", frequency
                                        )
                                        loadLogs()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = "Log Dose")
                                Text("Log Dose Taken Now", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "EDIT PRESCRIPTION METADATA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        OutlinedTextField(
                            value = dosage,
                            onValueChange = { dosage = it },
                            label = { Text("Dosage") },
                            placeholder = { Text("e.g. 1 tablet") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        OutlinedTextField(
                            value = frequency,
                            onValueChange = { frequency = it },
                            label = { Text("Frequency String") },
                            placeholder = { Text("e.g. 1-0-1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it },
                            label = { Text("Duration") },
                            placeholder = { Text("e.g. 5 days") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Special Patient Notes") },
                            placeholder = { Text("e.g. Take with warm water") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Optional / As Needed", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text("Check this if taken only when symptoms arise (e.g. SOS, PRN)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = isOptional,
                                onCheckedChange = { 
                                    isOptional = it
                                    if (it) {
                                        weeklySchedule = listOf("As Needed")
                                    } else {
                                        weeklySchedule = listOf("Everyday")
                                    }
                                }
                            )
                        }
                    }
                }
                
                if (!isOptional) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "CUSTOM WEEKLY SCHEDULE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Select which days the patient should take this medicine:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            
                            val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                daysOfWeek.forEach { day ->
                                    val isSelected = weeklySchedule.contains(day) || (weeklySchedule.contains("Everyday") || weeklySchedule.isEmpty())
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .clickable {
                                                val currentList = if (weeklySchedule.contains("Everyday") || weeklySchedule.isEmpty()) {
                                                    daysOfWeek.toMutableList()
                                                } else {
                                                    weeklySchedule.toMutableList()
                                                }
                                                
                                                if (currentList.contains(day)) {
                                                    currentList.remove(day)
                                                } else {
                                                    currentList.add(day)
                                                }
                                                
                                                if (currentList.size == 7) {
                                                    weeklySchedule = listOf("Everyday")
                                                } else if (currentList.isEmpty()) {
                                                    weeklySchedule = listOf("Everyday")
                                                } else {
                                                    weeklySchedule = currentList
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = day.take(1),
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "RECENT ACTIVITY & HISTORY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        if (isLogsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                        } else if (intakeLogs.isEmpty()) {
                            Text(
                                text = "No recorded logs for this medicine yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                intakeLogs.take(5).forEach { log ->
                                    val action = log.actionType
                                    val time = log.takenAt
                                    val logNotes = log.notes ?: ""
                                    
                                    val displayText = when (action) {
                                        "TAKEN" -> "Dose recorded as taken"
                                        "UPDATE_DETAILS" -> "Frequency/details updated"
                                        else -> action
                                    }
                                    
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = displayText,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (action == "TAKEN") Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = formatTimestamp(time),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (logNotes.isNotEmpty()) {
                                            Text(
                                                text = logNotes,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    coroutineScope.launch {
                        isSaving = true
                        try {
                            LocalRepository.updateMedicationDetails(
                                context, med.reportId, med.medicineName, med.patientName,
                                dosage, frequency, duration, isOptional, weeklySchedule, notes
                            )
                            onUpdateSuccess()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = !isSaving && dosage.isNotEmpty() && frequency.isNotEmpty()
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                } else {
                    Text("Save Changes")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ClinicalInsightsPanel(
    inferences: List<com.example.medicalscanner.model.TestInference>,
    onInferenceClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Insights",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Clinical Insights & Trends",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Text(
                text = "Based on your latest uploaded reports, here is what has changed and the differences:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                inferences.forEach { inference ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onInferenceClick(inference.reportId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val categoryLabel = when (inference.reportCategory.lowercase()) {
                                    "blood_test" -> "Blood Test"
                                    "sonography" -> "Sonography"
                                    "2d_echo" -> "2D Echo"
                                    "xray" -> "X-Ray"
                                    "prescription" -> "Prescription"
                                    else -> "Medical Scan"
                                }
                                Text(
                                    text = "${inference.patientName} • $categoryLabel",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                val trendColors = when (inference.status.lowercase()) {
                                    "improved" -> Pair(Color(0xFFE8F5E9), Color(0xFF2E7D32))
                                    "worsened" -> Pair(Color(0xFFFFEBEE), Color(0xFFC62828))
                                    else -> Pair(Color(0xFFFFF3E0), Color(0xFFE65100))
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(trendColors.first)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = inference.status.uppercase(java.util.Locale.getDefault()),
                                        color = trendColors.second,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                text = inference.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Date: ${inference.reportDate}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "View details",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Details",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BackgroundScanProgressBar(
    onNavigateToDetail: (String) -> Unit
) {
    val activeJobs = BackgroundScanScheduler.activeJobs
    if (activeJobs.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        activeJobs.forEach { job ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = job.status == ScanJobStatus.COMPLETED) {
                        job.resultReportId?.let { onNavigateToDetail(it) }
                        BackgroundScanScheduler.removeJob(job.id)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = when (job.status) {
                        ScanJobStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                        ScanJobStatus.COMPLETED -> Color(0xFFE8F5E9)
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (job.status != ScanJobStatus.COMPLETED && job.status != ScanJobStatus.ERROR) {
                        CircularProgressIndicator(
                            progress = { job.progress },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (job.status == ScanJobStatus.COMPLETED) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        val statusText = when (job.status) {
                            ScanJobStatus.UPLOADING -> "Uploading report..."
                            ScanJobStatus.OCR -> "Analyzing with AI..."
                            ScanJobStatus.SAVING -> "Saving details..."
                            ScanJobStatus.COMPLETED -> "Report scan complete! Tap to view."
                            ScanJobStatus.ERROR -> job.error ?: "Scan failed."
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (job.status) {
                                ScanJobStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                                ScanJobStatus.COMPLETED -> Color(0xFF1B5E20)
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                        Text(
                            text = "Patient: ${job.patientName} (${job.scanType.replaceFirstChar { it.lowercase() }})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (job.status == ScanJobStatus.COMPLETED || job.status == ScanJobStatus.ERROR) {
                        IconButton(
                            onClick = { BackgroundScanScheduler.removeJob(job.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

