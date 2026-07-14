package com.example.medicalscanner.ui

import androidx.compose.foundation.Image
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

private data class HomeAction(
    val label: String,
    val icon: ImageVector,
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
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Order matches the priority the user asked for: Scan first, Trends last.
    val actions = listOf(
        HomeAction(
            "Scan Report", Icons.Default.AddPhotoAlternate,
            Color(0xFFE8F5E9), Color(0xFF2E7D32), onNavigateToScan
        ),
        HomeAction(
            "Records", Icons.Default.History,
            Color(0xFFECEFF1), Color(0xFF455A64), onNavigateToRecords
        ),
        HomeAction(
            "Reminders", Icons.Default.Alarm,
            Color(0xFFFFF3E0), Color(0xFFE65100), onNavigateToReminders
        ),
        HomeAction(
            "Medication Tracker", Icons.Default.Medication,
            Color(0xFFF3E5F5), Color(0xFF6A1B9A), onNavigateToMedicationTracker
        ),
        HomeAction(
            "Pending Tests", Icons.Default.NotificationsActive,
            Color(0xFFFFEBEE), Color(0xFFC62828), onNavigateToPendingTests
        ),
        HomeAction(
            "Trends", Icons.Default.ShowChart,
            Color(0xFFE3F2FD), Color(0xFF1565C0), onNavigateToTrends
        )
    )

    Scaffold(
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Image(
                            painter = painterResource(id = com.example.medicalscanner.R.drawable.medical_assist_logo),
                            contentDescription = "HealthDecoder",
                            modifier = Modifier
                                .height(32.dp)
                                .width(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = tr("Refresh"),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCompare) {
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
        ) {
            BackgroundScanProgressBar(onNavigateToDetail = onNavigateToDetail)

            Column(
                modifier = Modifier
                    .fillMaxSize()
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
            }
        }
    }
}

@Composable
private fun ActionSquare(action: HomeAction, modifier: Modifier = Modifier) {
    val label = tr(action.label)
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = action.onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = action.containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = label,
                tint = action.contentColor,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                color = action.contentColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
