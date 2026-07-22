package com.example.medicalscanner.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.reminder.AppointmentSchedule
import com.example.medicalscanner.reminder.AppointmentStore
import com.example.medicalscanner.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorBriefScreen(
    patientName: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var appointment by remember { mutableStateOf<AppointmentSchedule?>(null) }
    var briefText by remember { mutableStateOf("Preparing brief…") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(patientName) {
        loading = true
        val (appt, brief) = withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val appts = AppointmentStore.loadAll(context)
            // Next upcoming appointment (soonest date/time from today), else the most recent one.
            val next = appts.filter { it.date >= today }
                .sortedWith(compareBy({ it.date }, { it.time })).firstOrNull()
                ?: appts.sortedByDescending { it.date }.firstOrNull()

            val target = patientName.trim()
            val reports = LocalRepository.getReports(context)
                .filter { target.isBlank() || it.patientName.equals(target, ignoreCase = true) }
                .sortedByDescending { it.reportDate ?: it.createdAt }

            val text = if (reports.isEmpty()) {
                "No records found${if (target.isNotBlank()) " for $target" else ""} yet — scan a report first to prepare a doctor brief."
            } else {
                val who = target.ifBlank { reports.first().patientName ?: "Patient" }
                val summary = LocalRepository.getHealthSummary(context, who, "all")
                val profile = LocalRepository.familyMembers(context).firstOrNull { it.name.equals(who, ignoreCase = true) }
                buildString {
                    append("🩺 Doctor Brief — $who")
                    profile?.let { p ->
                        val bits = listOfNotNull(
                            familyAge(p.dateOfBirth)?.let { "${it}y" },
                            p.sex.takeIf { it.isNotBlank() }
                        )
                        if (bits.isNotEmpty()) append(" (${bits.joinToString(", ")})")
                    }
                    append("\n")
                    reports.first().let { append("Last report: ${it.reportDate ?: "—"} · ${it.reportType ?: "Report"}\n") }
                    append("${reports.size} report(s) on file\n")

                    if (summary.overallNarrative.isNotBlank())
                        append("\nSummary:\n${summary.overallNarrative}\n")

                    if (summary.activeFlags.isNotEmpty()) {
                        append("\n⚠ Needs attention:\n")
                        summary.activeFlags.take(5).forEach { append("• $it\n") }
                    }

                    val trends = summary.parameterTrends.filter { it.dataPoints.isNotEmpty() }
                    if (trends.isNotEmpty()) {
                        append("\nKey results (latest):\n")
                        trends.take(8).forEach { t ->
                            val last = t.dataPoints.last()
                            val arrow = when (t.trend) {
                                "improving" -> "↗ improving"; "worsening" -> "↘ worsening"
                                "increasing" -> "↑ rising"; "decreasing" -> "↓ falling"; else -> "→ stable"
                            }
                            append("• ${t.name}: ${"${last.value} ${last.unit}".trim()} ($arrow)\n")
                        }
                    }

                    val meds = summary.medicationTimeline.lastOrNull()?.activeMedicines?.map { it.name }?.distinct()
                        ?: reports.flatMap { it.medications }.map { it.name }.filter { it.isNotBlank() }.distinct()
                    if (meds.isNotEmpty()) {
                        append("\n💊 Current medicines:\n")
                        meds.take(8).forEach { append("• $it\n") }
                    }
                    append("\n— Prepared by Medical Assist")
                }.trim()
            }
            next to text
        }
        appointment = appt
        briefText = brief
        loading = false
    }

    // ── Text-to-speech (on-device). Language set on the real instance once ready. ──
    var isPlaying by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set on the actual instance (the previous code set it on a still-null state var).
                val r = tts?.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isPlaying = true }
            override fun onDone(utteranceId: String?) { isPlaying = false }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { isPlaying = false }
        })
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }

    val shareTitle = tr("Share brief") // hoisted: tr() is @Composable
    fun shareBrief() {
        if (briefText.isBlank() || loading) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Doctor Brief — $patientName")
            putExtra(Intent.EXTRA_TEXT, briefText)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, shareTitle)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarLogo()
                        Text(tr("Doctor Visit Brief"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { shareBrief() }, enabled = !loading) {
                        Icon(Icons.Default.Share, contentDescription = tr("Share"), tint = Color(0xFF25D366))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .appWatermark()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appointment
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
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF0D7377)),
                        contentAlignment = Alignment.Center
                    ) { Text("👨‍⚕️", fontSize = 24.sp) }
                    Column {
                        val a = appointment
                        if (a != null) {
                            Text("Dr. ${a.doctorName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(a.place.ifBlank { tr("Appointment") }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${a.date}, ${a.time}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text(tr("No upcoming appointment"), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(if (patientName.isNotBlank()) "For $patientName" else "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Read-aloud
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlaying) { tts?.stop(); isPlaying = false }
                            else {
                                val spoken = briefText.replace(Regex("[🩺⚠💊•↗↘↑↓→]"), "").replace("\n", ". ")
                                tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "BriefingTTS")
                                isPlaying = true
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier.background(Color(0xFF1565C0), CircleShape).size(48.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play", tint = Color.White
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("Read the brief aloud"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            if (isPlaying) tr("Playing…") else tr("Tap play to hear this summary"),
                            fontSize = 12.sp, color = Color(0xFF1565C0)
                        )
                    }
                }
            }

            // The brief itself
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFDCF8C6))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(tr("Summary for the doctor"), fontWeight = FontWeight.Bold, color = Color(0xFF075E54))
                    Spacer(modifier = Modifier.height(8.dp))
                    if (loading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF075E54))
                            Text(tr("Preparing brief…"), color = Color(0xFF075E54))
                        }
                    } else {
                        Text(briefText, color = Color(0xFF075E54))
                    }
                }
            }

            Button(
                onClick = { shareBrief() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Share with doctor"), fontWeight = FontWeight.Bold)
            }
        }
    }
}
