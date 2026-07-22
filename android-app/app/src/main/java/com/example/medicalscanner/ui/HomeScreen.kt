package com.example.medicalscanner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import com.example.medicalscanner.model.MockProfiles
import com.example.medicalscanner.model.FamilyProfile


private data class HomeAction(
    val label: String,
    val emoji: String,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToCompare: () -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToTrends: () -> Unit,
    onNavigateToAccount: () -> Unit,
    onNavigateToRecords: () -> Unit,
    onNavigateToMedicationTracker: () -> Unit,
    onNavigateToReminders: () -> Unit,
    onNavigateToPendingTests: () -> Unit,
    onNavigateToDiscovery: (String) -> Unit,
    onNavigateToLiveVision: () -> Unit,
    onNavigateToDoctorBrief: (String) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isBackendReady = false

    val context = androidx.compose.ui.platform.LocalContext.current
    var profiles by remember { mutableStateOf(listOf<FamilyProfile>()) }
    var selectedProfile by remember { mutableStateOf<FamilyProfile?>(null) }
    var expandedProfileMenu by remember { mutableStateOf(false) }
    var showFamilyManager by remember { mutableStateOf(false) }
    var familyReload by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(familyReload) {
        // Real, persisted family members (includes people added with no reports yet). The stored
        // "active patient" scopes the app: null = Everyone (show all), else that member.
        val loaded = com.example.medicalscanner.local.LocalRepository.familyMembers(context)
        profiles = loaded
        val active = com.example.medicalscanner.local.AppSettings.getActivePatient(context)
        selectedProfile = if (active == null) null else loaded.firstOrNull { it.name.equals(active, ignoreCase = true) }
        // If the active member was renamed/removed out from under us, fall back to Everyone.
        if (active != null && selectedProfile == null) com.example.medicalscanner.local.AppSettings.setActivePatient(context, null)
    }

    if (showFamilyManager) {
        FamilyManagerDialog(
            onDismiss = { showFamilyManager = false; familyReload++ },
            onChanged = { familyReload++; onRefresh() }
        )
    }

    val actions = buildList {
        add(HomeAction("Scan Report", "📸", Color(0xFFE8F5E9), Color(0xFF2E7D32), onNavigateToScan))
        add(HomeAction("Records", "📜", Color(0xFFECEFF1), Color(0xFF455A64), onNavigateToRecords))
        add(HomeAction("Reminders", "⏰", Color(0xFFFFF3E0), Color(0xFFE65100), onNavigateToReminders))
        add(HomeAction("Medications", "💊", Color(0xFFF3E5F5), Color(0xFF6A1B9A), onNavigateToMedicationTracker))
        add(HomeAction("Pending Tests", "🚨", Color(0xFFFFF9C4), Color(0xFFC62828), onNavigateToPendingTests))
        if (isBackendReady) {
            add(HomeAction("Find Doctors", "🩺", Color(0xFFE0F2F1), Color(0xFF00796B), { onNavigateToDiscovery("doctors") }))
            add(HomeAction("Find Labs", "🧪", Color(0xFFE0F7FA), Color(0xFF006064), { onNavigateToDiscovery("lab_tests") }))
            add(HomeAction("Find Hospitals", "🏥", Color(0xFFE1F5FE), Color(0xFF0277BD), { onNavigateToDiscovery("hospitals") }))
        }
        add(HomeAction("Trends", "📈", Color(0xFFE3F2FD), Color(0xFF1565C0), onNavigateToTrends))
        add(HomeAction("Smart Health Lens", "👁️‍🗨️", Color(0xFF0F172A), Color.White, onNavigateToLiveVision))
        add(HomeAction("Doctor Brief", "👨‍⚕️", Color(0xFF0D7377), Color.White, { onNavigateToDoctorBrief(selectedProfile?.name ?: "") }))
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToChat,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Default.Mic, contentDescription = "Voice Search") },
                text = { Text(text = tr("Voice Search"), fontWeight = FontWeight.Bold) }
            )
        },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateToAccount) {
                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = tr("Account"))
                        }
                        IconButton(onClick = onNavigateToChat) {
                            Icon(imageVector = Icons.Default.QuestionAnswer, contentDescription = tr("Ask AI Assistant"))
                        }
                    }
                },
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { expandedProfileMenu = true }
                        ) {
                            Text(
                                text = selectedProfile?.let { "${it.avatarEmoji} ${it.name}" } ?: "👨‍👩‍👧 Everyone",
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Switch Profile",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        DropdownMenu(
                            expanded = expandedProfileMenu,
                            onDismissRequest = { expandedProfileMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("👨‍👩‍👧 Everyone") },
                                onClick = {
                                    selectedProfile = null
                                    com.example.medicalscanner.local.AppSettings.setActivePatient(context, null)
                                    expandedProfileMenu = false
                                    onRefresh()
                                }
                            )
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text("${profile.avatarEmoji} ${profile.name} (${profile.relation})") },
                                    onClick = {
                                        selectedProfile = profile
                                        com.example.medicalscanner.local.AppSettings.setActivePatient(context, profile.name)
                                        expandedProfileMenu = false
                                        onRefresh()
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("⚙️  Manage / edit family") },
                                onClick = { expandedProfileMenu = false; showFamilyManager = true }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = tr("Refresh"))
                    }
                    IconButton(onClick = onNavigateToCompare, modifier = Modifier.size(40.dp)) {
                        Icon(imageVector = Icons.Default.CompareArrows, contentDescription = tr("Compare Reports"))
                    }
                    LanguagePickerIcon()
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
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                actions.chunked(2).forEach { rowActions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowActions.forEach { action ->
                            ActionSquare(action = action, modifier = Modifier.weight(1f))
                        }
                        if (rowActions.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)
                
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun ActionSquare(action: HomeAction, modifier: Modifier = Modifier) {
    val label = tr(action.label)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        label = "scale"
    )
    val elevation by animateFloatAsState(
        targetValue = if (isPressed) 0f else 3f,
        label = "elevation"
    )

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = action.onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = action.containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = action.emoji,
                fontSize = 38.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                color = action.contentColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
