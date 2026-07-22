package com.example.medicalscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.DisposableEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorBriefScreen(
    patientName: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var appointment by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.example.medicalscanner.reminder.AppointmentSchedule?>(null) }
    var historySummary by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Loading...") }

    androidx.compose.runtime.LaunchedEffect(patientName) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val allAppts = com.example.medicalscanner.reminder.AppointmentStore.loadAll(context)
            appointment = allAppts.firstOrNull()

            val reports = com.example.medicalscanner.local.LocalRepository.getReports(context)
                .filter { it.patientName.equals(patientName, ignoreCase = true) }
                .sortedByDescending { it.reportDate }
                .take(3)
            
            if (reports.isEmpty()) {
                historySummary = "No recent medical reports found for this patient."
            } else {
                historySummary = buildString {
                    append("• Last visit: ${reports.first().reportDate}\n")
                    val latestResults = reports.first().testResults?.parameters
                    if (!latestResults.isNullOrEmpty()) {
                        val abnormal = latestResults.filter { it.status == "High" || it.status == "Low" }
                        if (abnormal.isNotEmpty()) {
                            append("• Note: ${abnormal.take(2).joinToString { "${it.name} is ${it.status}" }}\n")
                        }
                    }
                    val meds = reports.flatMap { it.medications }.map { it.name }.distinct()
                    if (meds.isNotEmpty()) {
                        append("• Current Medications: ${meds.take(3).joinToString()}\n")
                    }
                }.trimEnd()
                if (historySummary.isBlank()) {
                    historySummary = "Reports show stable parameters."
                }
            }
        }
    }

    var isPlaying by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var tts by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
            }
        }
        textToSpeech.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isPlaying = true
            }
            override fun onDone(utteranceId: String?) {
                isPlaying = false
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                isPlaying = false
            }
        })
        tts = textToSpeech
        
        onDispose {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Doctor Visit Brief") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Share on WhatsApp */ }) {
                        Icon(
                            imageVector = Icons.Default.Share, // Using built-in Share icon
                            contentDescription = "Share on WhatsApp",
                            tint = Color(0xFF25D366)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appointment Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0D7377)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👨‍⚕️", fontSize = 24.sp)
                    }
                    Column {
                        val currentAppt = appointment
                        if (currentAppt != null) {
                            Text("Dr. ${currentAppt.doctorName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Scheduled Appointment", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${currentAppt.date}, ${currentAppt.time}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("No Upcoming Appointments", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("For $patientName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Audio Player Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)) // Light blue
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                tts?.stop()
                                isPlaying = false
                            } else {
                                val speechText = "Briefing for $patientName. ${historySummary.replace("•", "")}"
                                tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "BriefingTTS")
                                isPlaying = true
                            }
                        },
                        modifier = Modifier
                            .background(Color(0xFF1565C0), CircleShape)
                            .size(48.dp)
                    ) {
                        if (isPlaying) {
                            Icon(Icons.Default.Close, contentDescription = "Stop", tint = Color.White)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                        }
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Patient Briefing Audio", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        // Simulated progress bar (could be animated based on speech duration)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                        ) {
                            if (isPlaying) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f) // Just a static indicator for now
                                        .fillMaxHeight()
                                        .background(Color(0xFF1565C0), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }
            }
            
            // Text Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCF8C6)) // WhatsApp style
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Top Insights for Doctor", fontWeight = FontWeight.Bold, color = Color(0xFF075E54))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        historySummary,
                        color = Color(0xFF075E54)
                    )
                }
            }
        }
    }
}
