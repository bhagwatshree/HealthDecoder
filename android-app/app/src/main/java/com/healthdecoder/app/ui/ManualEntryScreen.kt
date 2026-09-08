package com.healthdecoder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.healthdecoder.app.local.AppSettings
import com.healthdecoder.app.local.LocalRepository
import com.healthdecoder.app.model.FamilyProfile
import com.healthdecoder.app.model.VitalCatalog
import com.healthdecoder.app.model.VitalMetric
import com.healthdecoder.app.model.VitalReading
import com.healthdecoder.app.util.VitalReference
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val RECORDED_AT_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
private val DISPLAY_DATE_FMT = SimpleDateFormat("d MMM yyyy", Locale.US)
private val DISPLAY_TIME_FMT = SimpleDateFormat("h:mm a", Locale.US)
private val DISPLAY_DATETIME_FMT = SimpleDateFormat("d MMM, h:mm a", Locale.US)

private fun nowRecordedAt(): String = RECORDED_AT_FMT.format(Calendar.getInstance().time)

private fun parseRecordedAt(s: String): Calendar {
    val cal = Calendar.getInstance()
    runCatching { RECORDED_AT_FMT.parse(s)?.let { cal.time = it } }
    return cal
}

/** Renders a metric's reading as one display value + unit, e.g. "128/82 mmHg" for BP. */
private fun VitalReading.displayValue(): String = when (metric) {
    VitalCatalog.KEY_BP -> "$value/$value2".trim('/')
    else -> value
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    initialMetric: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf(listOf<FamilyProfile>()) }
    var selectedPatient by remember { mutableStateOf<String?>(null) }
    var patientMenu by remember { mutableStateOf(false) }
    var readings by remember { mutableStateOf<List<VitalReading>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var editingMetric by remember { mutableStateOf<VitalMetric?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    var showFamilyManager by remember { mutableStateOf(false) }
    var familyReload by remember { mutableStateOf(0) }

    // Re-runs when a person is added, so the new member is selectable without leaving the screen.
    LaunchedEffect(familyReload) {
        val loaded = LocalRepository.familyMembers(context)
        profiles = loaded
        val active = AppSettings.getActivePatient(context)
        // Keep whoever is already chosen — re-picking the active patient here would yank the
        // selection back after the user deliberately switched people.
        selectedPatient = selectedPatient?.takeIf { sel -> loaded.any { it.name == sel } }
            ?: loaded.firstOrNull { it.name.equals(active, ignoreCase = true) }?.name
            ?: loaded.firstOrNull()?.name
    }

    // Deep-link from a Trends chart point or the Home tile. Keyed on the argument, NOT on the
    // family reload above — otherwise adding a person would re-open this dialog unprompted.
    LaunchedEffect(initialMetric) {
        editingMetric = VitalCatalog.byKey(initialMetric ?: "")
    }

    LaunchedEffect(selectedPatient, reloadTick) {
        val p = selectedPatient
        isLoading = true
        readings = if (p == null) emptyList() else LocalRepository.getVitals(context, p)
        isLoading = false
    }

    val latestByMetric = remember(readings) {
        readings.groupBy { it.metric }.mapValues { (_, list) -> list.maxByOrNull { it.recordedAt } }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TopBarLogo()
                        Text(tr("Add a Reading"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = tr("Back")) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (profiles.size > 1) {
                ExposedDropdownMenuBox(
                    expanded = patientMenu, onExpandedChange = { patientMenu = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedPatient ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(tr("Patient")) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patientMenu) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = patientMenu, onDismissRequest = { patientMenu = false }) {
                        profiles.forEach { p ->
                            DropdownMenuItem(text = { Text("${p.avatarEmoji} ${p.name}") }, onClick = { selectedPatient = p.name; patientMenu = false })
                        }
                    }
                }
            }

            if (selectedPatient == null) {
                // A first-run user (no reports scanned yet, so familyMembers() has auto-seeded
                // nobody) would otherwise land on a dead end here, told to go elsewhere with no
                // way to act on it. The family manager is a dialog, so it opens in place.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.MonitorHeart, contentDescription = null, modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Text(tr("Add a family member first"), fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tr("Home readings are saved against a person, so we need to know who this reading is for."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Button(onClick = { showFamilyManager = true }) { Text(tr("Add a person")) }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(tr("Tap a tile to log a reading"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            VitalCatalog.METRICS.chunked(2).forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { metric ->
                                        QuickLogTile(
                                            metric = metric,
                                            latest = latestByMetric[metric.key],
                                            modifier = Modifier.weight(1f),
                                            onClick = { editingMetric = metric }
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    item {
                        Text(tr("Recent readings"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp))
                    }

                    if (isLoading) {
                        item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    } else if (readings.isEmpty()) {
                        // NOT EmptyStateView here — it fillMaxSize()s internally, which crashes
                        // with an infinite-height constraint inside a LazyColumn item.
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.MonitorHeart, contentDescription = null, modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                Text(tr("No readings yet"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    tr("Log your Sugar, BP, Heart Rate or Oxygen readings here as you take them at home."),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(readings.sortedByDescending { it.recordedAt }.take(50), key = { it.id }) { reading ->
                            RecentReadingRow(
                                reading = reading,
                                onDelete = {
                                    coroutineScope.launch {
                                        LocalRepository.deleteVital(context, reading.id)
                                        reloadTick++
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFamilyManager) {
        FamilyManagerDialog(
            onDismiss = { showFamilyManager = false; familyReload++ },
            onChanged = { familyReload++ }
        )
    }

    editingMetric?.let { metric ->
        val patient = selectedPatient
        if (patient != null) {
            VitalEntryDialog(
                metric = metric,
                patientName = patient,
                onDismiss = { editingMetric = null },
                onSaved = {
                    editingMetric = null
                    reloadTick++
                }
            )
        }
    }
}

@Composable
private fun QuickLogTile(metric: VitalMetric, latest: VitalReading?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val status = latest?.let { statusFor(metric, it) } ?: ""
    val color = ClinicalStatus.colorFor(status)
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.emoji, fontSize = 20.sp)
                // Two lines with an ellipsis, not one with a hard clip. These tiles are half the
                // screen wide and share the row with the emoji, so "Blood Pressure" and "Blood
                // Sugar" both truncated to a bare "Blood" — two different tiles rendering the same
                // word. Same fix, and same reasoning, as ActionSquare on the Home grid.
                Text(
                    tr(metric.displayName),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            if (latest == null) {
                Text(tr("No readings yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${latest.displayValue()} ${latest.unit}".trim(),
                    fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium,
                    color = if (status.isNotBlank() && status != "Normal") color else MaterialTheme.colorScheme.onSurface
                )
                Text(DISPLAY_DATETIME_FMT.format(parseRecordedAt(latest.recordedAt).time),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Text(tr("Log now"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RecentReadingRow(reading: VitalReading, onDelete: () -> Unit) {
    val metric = VitalCatalog.byKey(reading.metric)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(metric?.emoji ?: "•", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${reading.displayValue()} ${reading.unit}".trim(), fontWeight = FontWeight.Bold)
                    if (metric != null && metric.hasPulseField && reading.value3.isNotBlank()) {
                        Text("· ${reading.value3} bpm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    buildString {
                        append(DISPLAY_DATETIME_FMT.format(parseRecordedAt(reading.recordedAt).time))
                        if (reading.context.isNotBlank()) append(" · ${reading.context}")
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reading.note.isNotBlank()) {
                    Text(reading.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = tr("Delete"), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Normal/High/Low for one reading, using the same curated (non-AI) thresholds Trends uses. */
private fun statusFor(metric: VitalMetric, reading: VitalReading): String = when (metric.key) {
    VitalCatalog.KEY_GLUCOSE -> reading.value.toFloatOrNull()?.let { VitalReference.glucoseStatus(it, reading.unit, reading.context) } ?: ""
    VitalCatalog.KEY_BP -> {
        val sys = reading.value.toFloatOrNull(); val dia = reading.value2.toFloatOrNull()
        if (sys != null && dia != null) VitalReference.bpStatus(sys, dia) else ""
    }
    VitalCatalog.KEY_PULSE -> reading.value.toFloatOrNull()?.let { VitalReference.pulseStatus(it) } ?: ""
    VitalCatalog.KEY_SPO2 -> reading.value.toFloatOrNull()?.let { VitalReference.spo2Status(it) } ?: ""
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VitalEntryDialog(
    metric: VitalMetric,
    patientName: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var value1 by remember { mutableStateOf("") }
    var value2 by remember { mutableStateOf("") }
    var value3 by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(metric.units.firstOrNull() ?: "") }
    var selectedContext by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var recordedAt by remember { mutableStateOf(nowRecordedAt()) }
    var saving by remember { mutableStateOf(false) }

    val range2 = metric.plausibleRange2
    val num1 = value1.toFloatOrNull()
    val num2 = value2.toFloatOrNull()
    val value1Error = value1.isNotBlank() && (num1 == null || num1 !in metric.plausibleRange)
    val value2Error = metric.secondValueLabel.isNotBlank() &&
        value2.isNotBlank() && (num2 == null || range2 == null || num2 !in range2)
    // The optional pulse riding along with a BP/SpO2 reading was previously saved unvalidated —
    // a mistyped 900 went straight onto the Pulse trend line, since it bypasses the standalone
    // Heart Rate metric's own range check.
    val num3 = value3.toFloatOrNull()
    val value3Error = metric.hasPulseField && value3.isNotBlank() &&
        (num3 == null || num3 !in VitalCatalog.pulseRange)
    val cal = remember(recordedAt) { parseRecordedAt(recordedAt) }
    // A reading cannot have been taken in the future. The date picker already caps at today, but
    // the time picker can still push a today-dated reading past the current hour, so the composed
    // timestamp is validated too. Truncating to the minute always rounds down, so "now" itself can
    // never trip this.
    val isFuture = cal.timeInMillis > System.currentTimeMillis()
    val canSave = num1 != null && num1 in metric.plausibleRange &&
        (metric.secondValueLabel.isBlank() || (num2 != null && range2 != null && num2 in range2)) &&
        !value3Error && !isFuture

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(trFormat("Log %1\$s", tr(metric.displayName)), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value1,
                        onValueChange = { value1 = it },
                        label = { Text(tr(metric.firstValueLabel.ifBlank { metric.displayName })) },
                        isError = value1Error,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (metric.secondValueLabel.isNotBlank()) {
                        OutlinedTextField(
                            value = value2,
                            onValueChange = { value2 = it },
                            label = { Text(tr(metric.secondValueLabel)) },
                            isError = value2Error,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (value1Error || value2Error) {
                    Text(tr("That doesn't look like a valid reading — please check the number."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                } else if (metric.valueHint.isNotBlank()) {
                    // Only when there is no error to show, so the hint never competes with it.
                    Text(tr(metric.valueHint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (metric.hasPulseField) {
                    OutlinedTextField(
                        value = value3,
                        onValueChange = { value3 = it },
                        label = { Text(tr("Pulse (optional, bpm)")) },
                        isError = value3Error,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (value3Error) {
                        Text(tr("That doesn't look like a valid pulse — please check the number."),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (metric.units.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(metric.units) { u ->
                            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u, fontSize = 12.sp) })
                        }
                    }
                }

                if (metric.contextOptions.isNotEmpty()) {
                    Text(tr("When was this taken?"), style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(metric.contextOptions) { c ->
                            FilterChip(
                                selected = selectedContext == c,
                                onClick = { selectedContext = if (selectedContext == c) null else c },
                                label = { Text(tr(c), fontSize = 12.sp) }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    cal.set(y, m, d)
                                    recordedAt = RECORDED_AT_FMT.format(cal.time)
                                },
                                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                            ).apply {
                                // You cannot have taken a reading tomorrow. Beyond being wrong, a
                                // future-dated point stretches the trend chart's x-axis out to that
                                // date and leaves every real reading bunched at the left edge.
                                datePicker.maxDate = System.currentTimeMillis()
                            }.show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(DISPLAY_DATE_FMT.format(cal.time)) }
                    OutlinedButton(
                        onClick = {
                            android.app.TimePickerDialog(
                                context,
                                { _, h, m ->
                                    cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m)
                                    recordedAt = RECORDED_AT_FMT.format(cal.time)
                                },
                                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(DISPLAY_TIME_FMT.format(cal.time)) }
                }
                if (isFuture) {
                    Text(
                        tr("That time is in the future — pick when the reading was actually taken."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall
                    )
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(tr("Note (optional)")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave && !saving,
                onClick = {
                    saving = true
                    coroutineScope.launch {
                        LocalRepository.addVital(
                            context = context,
                            patientName = patientName,
                            metric = metric.key,
                            value = value1,
                            value2 = value2,
                            value3 = value3,
                            unit = unit,
                            readingContext = selectedContext ?: "",
                            note = note,
                            recordedAt = recordedAt
                        )
                        saving = false
                        onSaved()
                    }
                }
            ) { Text(if (saving) tr("Saving…") else tr("Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        }
    )
}
