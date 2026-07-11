package com.example.medicalscanner.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.medicalscanner.model.MedicationHistory
import com.example.medicalscanner.reminder.MedicineReminderManager
import com.example.medicalscanner.reminder.MedicineSchedule
import com.example.medicalscanner.reminder.MedicineScheduleStore
import java.util.Calendar

private data class SlotStyle(val bg: Color, val fg: Color, val icon: ImageVector)

private val SLOT_STYLES = mapOf(
    "Morning"   to SlotStyle(Color(0xFFFFF8E1), Color(0xFFF57F17), Icons.Default.WbSunny),
    "Afternoon" to SlotStyle(Color(0xFFE3F2FD), Color(0xFF1565C0), Icons.Default.LightMode),
    "Evening"   to SlotStyle(Color(0xFFE8F5E9), Color(0xFF2E7D32), Icons.Default.WbCloudy),
    "Night"     to SlotStyle(Color(0xFFF3E5F5), Color(0xFF6A1B9A), Icons.Default.Bedtime)
)

private val TIME_SLOTS = listOf("Morning", "Afternoon", "Evening", "Night")

@Composable
fun TodaysMedicinesTab(medicationHistory: List<MedicationHistory>) {
    val context = LocalContext.current

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotifPermission = granted }

    var schedules by remember { mutableStateOf(MedicineScheduleStore.loadAll(context)) }

    LaunchedEffect(medicationHistory) {
        medicationHistory.filter { it.status.lowercase() == "active" }.forEach { med ->
            val activeSlots = parseRoutine(med.currentFrequency, med.currentDosage)
                .filter { it.second }.map { it.first }
            MedicineScheduleStore.autoSeedIfAbsent(
                context, med.medicineName, med.patientName,
                med.currentDosage, med.currentFrequency, activeSlots
            )
        }
        schedules = MedicineScheduleStore.loadAll(context)
        MedicineReminderManager.scheduleAll(context)
    }

    val currentSlot = remember {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when { h < 12 -> "Morning"; h < 16 -> "Afternoon"; h < 20 -> "Evening"; else -> "Night" }
    }

    var editingSchedule by remember { mutableStateOf<MedicineSchedule?>(null) }
    var editingSlot     by remember { mutableStateOf<String?>(null) }
    var deletingSchedule by remember { mutableStateOf<MedicineSchedule?>(null) }

    val hasMedsToday = schedules.any { s -> s.slots.values.any { it.enabled } }

    val canScheduleExact = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        else true
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Notification permission banner
        if (!hasNotifPermission) {
            item {
                PermissionBanner(
                    icon = Icons.Default.NotificationsOff,
                    iconTint = Color(0xFFE65100),
                    bg = Color(0xFFFFF3CD),
                    title = "Allow Notifications",
                    body = "Tap to allow so medicine reminders show on your phone and paired watch.",
                    actionLabel = "Allow"
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // Exact alarm permission banner (Android 12+)
        if (!canScheduleExact) {
            item {
                PermissionBanner(
                    icon = Icons.Default.Alarm,
                    iconTint = Color(0xFFC62828),
                    bg = Color(0xFFFFEBEE),
                    title = "Enable Exact Alarms",
                    body = "Tap Settings → allow exact alarms so reminders arrive at the precise time.",
                    actionLabel = "Settings"
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"))
                    )
                }
            }
        }

        // Today's dose sections grouped by time slot
        TIME_SLOTS.forEach { slot ->
            val medsForSlot = schedules.filter { it.slots[slot]?.enabled == true }
            if (medsForSlot.isNotEmpty()) {
                item(key = "header_$slot") {
                    SlotHeader(slot = slot, isCurrent = slot == currentSlot,
                        time = medsForSlot.first().slots[slot]!!)
                }
                items(medsForSlot, key = { "${it.patientName}_${it.medicineName}_$slot" }) { schedule ->
                    MedicineCard(
                        schedule = schedule,
                        slot = slot,
                        onToggle = { enabled ->
                            val updated = schedule.copy(
                                slots = schedule.slots.toMutableMap().also {
                                    it[slot] = it[slot]!!.copy(enabled = enabled)
                                }
                            )
                            MedicineScheduleStore.upsert(context, updated)
                            MedicineReminderManager.scheduleForMedicine(context, updated)
                            schedules = MedicineScheduleStore.loadAll(context)
                        },
                        onEditTime = { editingSchedule = schedule; editingSlot = slot }
                    )
                }
            }
        }

        // Empty state
        if (!hasMedsToday) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.Medication, "No meds",
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    Text("No Medicine Reminders",
                        fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center)
                    Text("Once prescriptions are scanned, your daily medicines will appear here with reminders.\n\nTap a toggle below to enable reminders for each medicine.",
                        fontSize = 16.sp, textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }

        // Manage all schedules section
        if (schedules.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Tune, "Manage",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text("Manage All Reminders",
                        fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Text("Enable or disable reminders per slot, tap the time to change it.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }

            items(schedules, key = { "${it.patientName}_${it.medicineName}_manage" }) { schedule ->
                ManageCard(
                    schedule = schedule,
                    onToggle = { slot, enabled ->
                        val updated = schedule.copy(
                            slots = schedule.slots.toMutableMap().also {
                                it[slot] = it[slot]!!.copy(enabled = enabled)
                            }
                        )
                        MedicineScheduleStore.upsert(context, updated)
                        MedicineReminderManager.scheduleForMedicine(context, updated)
                        schedules = MedicineScheduleStore.loadAll(context)
                    },
                    onEditTime = { slot -> editingSchedule = schedule; editingSlot = slot },
                    onDelete = { deletingSchedule = schedule }
                )
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    // Confirm deletion of a medicine reminder
    deletingSchedule?.let { sched ->
        AlertDialog(
            onDismissRequest = { deletingSchedule = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete reminder?") },
            text = { Text("Remove the reminder for \"${sched.medicineName}\" (${sched.patientName})? Its alarms will be cancelled. This won't affect the scanned report.") },
            confirmButton = {
                Button(
                    onClick = {
                        MedicineScheduleStore.delete(context, sched.medicineName, sched.patientName)
                        MedicineReminderManager.scheduleAll(context) // re-sync grouped alarms
                        schedules = MedicineScheduleStore.loadAll(context)
                        deletingSchedule = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSchedule = null }) { Text("Cancel") } }
        )
    }

    if (editingSchedule != null && editingSlot != null) {
        val cfg = editingSchedule!!.slots[editingSlot!!]!!
        SlotTimePickerDialog(
            slot = editingSlot!!,
            initialHour = cfg.hour,
            initialMinute = cfg.minute,
            onConfirm = { h, m ->
                val s = editingSchedule!!
                val updated = s.copy(
                    slots = s.slots.toMutableMap().also { it[editingSlot!!] = cfg.copy(hour = h, minute = m) }
                )
                MedicineScheduleStore.upsert(context, updated)
                MedicineReminderManager.scheduleForMedicine(context, updated)
                schedules = MedicineScheduleStore.loadAll(context)
                editingSchedule = null; editingSlot = null
            },
            onDismiss = { editingSchedule = null; editingSlot = null }
        )
    }
}

@Composable
private fun PermissionBanner(
    icon: ImageVector,
    iconTint: Color,
    bg: Color,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = iconTint)
                Text(body, fontSize = 14.sp, color = Color(0xFF5D4037), lineHeight = 20.sp)
            }
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = iconTint),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(actionLabel, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun SlotHeader(slot: String, isCurrent: Boolean, time: com.example.medicalscanner.reminder.SlotConfig) {
    val style = SLOT_STYLES[slot] ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(style.bg)
            .then(
                if (isCurrent) Modifier.border(2.dp, style.fg, RoundedCornerShape(12.dp))
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(style.icon, slot, tint = style.fg, modifier = Modifier.size(26.dp))
        Text(slot, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = style.fg)
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(style.fg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("NOW", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.AccessTime, "Time", tint = style.fg, modifier = Modifier.size(16.dp))
        Text(
            text = "%02d:%02d".format(time.hour, time.minute),
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = style.fg
        )
    }
}

@Composable
private fun MedicineCard(
    schedule: MedicineSchedule,
    slot: String,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit
) {
    val cfg = schedule.slots[slot] ?: return
    val style = SLOT_STYLES[slot] ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(58.dp).clip(CircleShape).background(style.bg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Medication, "Medicine",
                    tint = style.fg, modifier = Modifier.size(34.dp))
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    schedule.medicineName,
                    fontWeight = FontWeight.ExtraBold, fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface, lineHeight = 26.sp
                )
                if (schedule.dosage.isNotEmpty()) {
                    Text(schedule.dosage, fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(schedule.patientName, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEditTime)
                        .background(style.bg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Edit, "Change time", tint = style.fg, modifier = Modifier.size(14.dp))
                    Text(
                        text = "%02d:%02d".format(cfg.hour, cfg.minute),
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = style.fg
                    )
                }
                Switch(
                    checked = cfg.enabled,
                    onCheckedChange = onToggle,
                    thumbContent = {
                        Icon(
                            if (cfg.enabled) Icons.Default.Alarm else Icons.Default.AlarmOff,
                            null, modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = style.fg,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}

@Composable
private fun ManageCard(
    schedule: MedicineSchedule,
    onToggle: (String, Boolean) -> Unit,
    onEditTime: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(schedule.medicineName, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    Text(schedule.patientName, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (schedule.dosage.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(schedule.dosage, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete reminder",
                        tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TIME_SLOTS.forEach { slot ->
                    val cfg   = schedule.slots[slot]
                    val style = SLOT_STYLES[slot]!!
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (cfg?.enabled == true) style.bg
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            )
                            .padding(vertical = 8.dp, horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(style.icon, slot,
                            tint = if (cfg?.enabled == true) style.fg
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp))
                        Text(slot.take(3), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (cfg?.enabled == true) style.fg
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        if (cfg != null) {
                            Text(
                                text = "%02d:%02d".format(cfg.hour, cfg.minute),
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (cfg.enabled) style.fg
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.clickable { onEditTime(slot) }
                            )
                            Switch(
                                checked = cfg.enabled,
                                onCheckedChange = { onToggle(slot, it) },
                                modifier = Modifier.height(28.dp).padding(top = 2.dp),
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = style.fg,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotTimePickerDialog(
    slot: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Set $slot Alarm Time",
                fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TimePicker(state = state)
                Text("Medicine reminder will ring daily at this time on your phone and watch.",
                    fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(state.hour, state.minute) },
                modifier = Modifier.height(52.dp)
            ) {
                Text("Set Reminder", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontSize = 17.sp)
            }
        }
    )
}
