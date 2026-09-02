package com.healthdecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.MedicalReport

/**
 * Every section of one multi-panel document (Haemogram + PT/INR + Biochemistry etc.) on a
 * single scrollable screen, so seeing "the whole report" doesn't mean tapping into each panel
 * one at a time from Records or from another panel's own detail screen. Shows each section's
 * parameter table directly here; "Open full section" still leads to that panel's own
 * ReportDetailScreen for anything this summary doesn't carry (AI insights, side-effects, editing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteReportScreen(
    reportId: String,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var sections by remember { mutableStateOf<List<MedicalReport>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(reportId) {
        isLoading = true
        sections = runCatching { LocalRepository.documentSiblings(context, reportId) }.getOrDefault(emptyList())
        isLoading = false
    }

    val first = sections.firstOrNull()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(tr("Complete Report"), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (first == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(tr("Report not found."))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Patient: ${first.patientName ?: "Unknown Patient"}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            first.reportDate ?: tr("No Date"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        first.sourceFiles.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { fileName ->
                            Text(fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            trFormat("%1\$d section(s) in this document", sections.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                items(sections, key = { it.id }) { section ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onNavigateToDetail(section.id) },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    (section.reportType?.takeIf { it.isNotBlank() } ?: tr("Report")).uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = tr("Open full section"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            val params = section.testResults?.parameters ?: emptyList()
                            if (params.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    params.forEach { p ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                p.name,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1.4f)
                                            )
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "${p.value} ${p.unit}".trim(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                                )
                                                p.status?.takeIf { it.isNotBlank() }?.let { status ->
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    StatusBadge(status, compact = true)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (!section.comments.isNullOrBlank()) {
                                Text(section.comments, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(
                                    tr("No extracted values for this section."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            TextButton(onClick = { onNavigateToDetail(section.id) }, modifier = Modifier.align(Alignment.End)) {
                                Text(tr("Open full section"))
                            }
                        }
                    }
                }
            }
        }
    }
}
