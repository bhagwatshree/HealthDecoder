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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.medicalscanner.reminder.SlotConfig
import com.example.medicalscanner.reminder.AppointmentSchedule
import com.example.medicalscanner.reminder.AppointmentStore
import com.example.medicalscanner.reminder.AppointmentReminderManager
import java.util.Calendar
import java.util.UUID

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
    var appointments by remember { mutableStateOf(AppointmentStore.loadAll(context)) }

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
        appointments = AppointmentStore.loadAll(context)
        MedicineReminderManager.scheduleAll(context)
        AppointmentReminderManager.scheduleAll(context)
    }

    val currentSlot = remember {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when { h < 12 -> "Morning"; h < 16 -> "Afternoon"; h < 20 -> "Evening"; else -> "Night" }
    }

    var editingSchedule by remember { mutableStateOf<MedicineSchedule?>(null) }
    var editingSlot     by remember { mutableStateOf<String?>(null) }
    var deletingSchedule by remember { mutableStateOf<MedicineSchedule?>(null) }

    var showAddMedDialog by remember { mutableStateOf(false) }
    var showAddApptDialog by remember { mutableStateOf(false) }
    var editingMedSchedule by remember { mutableStateOf<MedicineSchedule?>(null) }
    var editingAppt by remember { mutableStateOf<AppointmentSchedule?>(null) }
    var deletingAppt by remember { mutableStateOf<AppointmentSchedule?>(null) }

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
                    title = tr("Allow Notifications"),
                    body = tr("Tap to allow so medicine reminders show on your phone and paired watch."),
                    actionLabel = tr("Allow")
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
                    title = tr("Enable Exact Alarms"),
                    body = tr("Tap Settings → allow exact alarms so reminders arrive at the precise time."),
                    actionLabel = tr("Settings")
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:${context.packageName}"))
                    )
                }
            }
        }

        // Custom quick-add actions row
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { showAddMedDialog = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Add Med"), fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showAddApptDialog = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Add Appt"), fontWeight = FontWeight.Bold)
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
                    Icon(Icons.Default.Medication, tr("No meds"),
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    Text(tr("No Medicine Reminders"),
                        fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center)
                    Text(tr("Once prescriptions are scanned, your daily medicines will appear here with reminders.\n\nTap a toggle below to enable reminders for each medicine."),
                        fontSize = 16.sp, textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }

        // Appointments section
        if (appointments.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CalendarMonth, tr("Appointments"),
                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
                    Text(tr("Doctor Appointments"),
                        fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(4.dp))
                Text(tr("Manage your scheduled doctor visits and clinical check-ups."),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }

            items(appointments, key = { "${it.id}_appt" }) { appt ->
                AppointmentCard(
                    appointment = appt,
                    onEdit = { editingAppt = appt },
                    onDelete = { deletingAppt = appt }
                )
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
                    Icon(Icons.Default.Tune, tr("Manage"),
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(tr("Manage All Reminders"),
                        fontWeight = FontWeight.ExtraBold, fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Text(tr("Enable or disable reminders per slot, tap the time to change it."),
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
                    onDelete = { deletingSchedule = schedule },
                    onEdit = { editingMedSchedule = schedule }
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
            title = { Text(tr("Delete reminder?")) },
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
                ) { Text(tr("Delete")) }
            },
            dismissButton = { TextButton(onClick = { deletingSchedule = null }) { Text(tr("Cancel")) } }
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

    // --- Custom Medicine Add / Edit Dialogs ---
    if (showAddMedDialog) {
        AddMedicineDialog(
            onConfirm = { schedule ->
                MedicineScheduleStore.upsert(context, schedule)
                MedicineReminderManager.scheduleAll(context)
                schedules = MedicineScheduleStore.loadAll(context)
                showAddMedDialog = false
            },
            onDismiss = { showAddMedDialog = false }
        )
    }

    editingMedSchedule?.let { schedule ->
        AddMedicineDialog(
            initialSchedule = schedule,
            onConfirm = { updated ->
                if (!updated.medicineName.equals(schedule.medicineName, ignoreCase = true) ||
                    !updated.patientName.equals(schedule.patientName, ignoreCase = true)) {
                    MedicineScheduleStore.delete(context, schedule.medicineName, schedule.patientName)
                }
                MedicineScheduleStore.upsert(context, updated)
                MedicineReminderManager.scheduleAll(context)
                schedules = MedicineScheduleStore.loadAll(context)
                editingMedSchedule = null
            },
            onDismiss = { editingMedSchedule = null }
        )
    }

    // --- Custom Appointment Add / Edit Dialogs ---
    if (showAddApptDialog) {
        AddAppointmentDialog(
            onConfirm = { appt ->
                AppointmentStore.upsert(context, appt)
                AppointmentReminderManager.scheduleAll(context)
                appointments = AppointmentStore.loadAll(context)
                showAddApptDialog = false
            },
            onDismiss = { showAddApptDialog = false }
        )
    }

    editingAppt?.let { appt ->
        AddAppointmentDialog(
            initialAppointment = appt,
            onConfirm = { updated ->
                AppointmentStore.upsert(context, updated)
                AppointmentReminderManager.scheduleAll(context)
                appointments = AppointmentStore.loadAll(context)
                editingAppt = null
            },
            onDismiss = { editingAppt = null }
        )
    }

    deletingAppt?.let { appt ->
        AlertDialog(
            onDismissRequest = { deletingAppt = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(tr("Delete Appointment?")) },
            text = { Text("Are you sure you want to cancel and delete the appointment with Dr. ${appt.doctorName}?") },
            confirmButton = {
                Button(
                    onClick = {
                        AppointmentStore.delete(context, appt.id)
                        AppointmentReminderManager.cancel(context, appt.id)
                        appointments = AppointmentStore.loadAll(context)
                        deletingAppt = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(tr("Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deletingAppt = null }) { Text(tr("Cancel")) }
            }
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
                Text(tr("NOW"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.AccessTime, tr("Time"), tint = style.fg, modifier = Modifier.size(16.dp))
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
                Icon(Icons.Default.Medication, tr("Medicine"),
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
                    Icon(Icons.Default.Edit, tr("Change time"), tint = style.fg, modifier = Modifier.size(14.dp))
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
    onDelete: () -> Unit,
    onEdit: () -> Unit
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
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = tr("Edit reminder"),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = tr("Delete reminder"),
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
                Text(tr("Medicine reminder will ring daily at this time on your phone and watch."),
                    fontSize = 14.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(state.hour, state.minute) },
                modifier = Modifier.height(52.dp)
            ) {
                Text(tr("Set Reminder"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel"), fontSize = 17.sp)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMedicineDialog(
    initialSchedule: MedicineSchedule? = null,
    onConfirm: (MedicineSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    var medName by remember { mutableStateOf(initialSchedule?.medicineName ?: "") }
    var patName by remember { mutableStateOf(initialSchedule?.patientName ?: "Me") }
    var dosage by remember { mutableStateOf(initialSchedule?.dosage ?: "") }
    var frequency by remember { mutableStateOf(initialSchedule?.frequency ?: "Daily") }

    val slotConfigMap = remember {
        mutableStateMapOf<String, SlotConfig>().apply {
            MedicineScheduleStore.defaultSlotTimes.forEach { (slot, hm) ->
                val existing = initialSchedule?.slots?.get(slot)
                put(slot, existing ?: SlotConfig(enabled = false, hour = hm.first, minute = hm.second))
            }
        }
    }

    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initialSchedule == null) tr("Add Medicine Reminder") else tr("Edit Medicine Reminder"),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = medName,
                    onValueChange = { medName = it },
                    label = { Text(tr("Medicine Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = patName,
                    onValueChange = { patName = it },
                    label = { Text(tr("Patient Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = dosage,
                        onValueChange = { dosage = it },
                        label = { Text(tr("Dosage")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = frequency,
                        onValueChange = { frequency = it },
                        label = { Text(tr("Frequency")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(tr("Select reminder slots"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)

                MedicineScheduleStore.defaultSlotTimes.keys.forEach { slot ->
                    val cfg = slotConfigMap[slot]!!
                    var timePickerDialogSlot by remember { mutableStateOf<String?>(null) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = cfg.enabled,
                                onCheckedChange = { checked ->
                                    slotConfigMap[slot] = cfg.copy(enabled = checked)
                                }
                            )
                            Text(slot, style = MaterialTheme.typography.bodyMedium)
                        }

                        TextButton(
                            onClick = { timePickerDialogSlot = slot },
                            enabled = cfg.enabled
                        ) {
                            Text("%02d:%02d".format(cfg.hour, cfg.minute))
                        }
                    }

                    if (timePickerDialogSlot != null) {
                        val slotState = rememberTimePickerState(initialHour = cfg.hour, initialMinute = cfg.minute)
                        AlertDialog(
                            onDismissRequest = { timePickerDialogSlot = null },
                            text = { TimePicker(state = slotState) },
                            confirmButton = {
                                Button(onClick = {
                                    slotConfigMap[slot] = cfg.copy(hour = slotState.hour, minute = slotState.minute)
                                    timePickerDialogSlot = null
                                }) { Text(tr("OK")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { timePickerDialogSlot = null }) { Text(tr("Cancel")) }
                            }
                        )
                    }
                }

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (medName.trim().isEmpty()) {
                        errorMsg = "Medicine name is required."
                        return@Button
                    }
                    if (slotConfigMap.values.none { it.enabled }) {
                        errorMsg = "Select at least one reminder slot."
                        return@Button
                    }
                    onConfirm(
                        MedicineSchedule(
                            medicineName = medName.trim(),
                            patientName = patName.trim(),
                            dosage = dosage.trim(),
                            frequency = frequency.trim(),
                            slots = slotConfigMap.toMap()
                        )
                    )
                }
            ) {
                Text(tr("Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppointmentDialog(
    initialAppointment: AppointmentSchedule? = null,
    onConfirm: (AppointmentSchedule) -> Unit,
    onDismiss: () -> Unit
) {
    var doctorName by remember { mutableStateOf(initialAppointment?.doctorName ?: "") }
    var place by remember { mutableStateOf(initialAppointment?.place ?: "") }
    var recurrence by remember { mutableStateOf(initialAppointment?.recurrence ?: "None") }
    var date by remember { mutableStateOf(initialAppointment?.date ?: "") }
    var time by remember { mutableStateOf(initialAppointment?.time ?: "") }

    var hour by remember { mutableIntStateOf(initialAppointment?.hour ?: 9) }
    var minute by remember { mutableIntStateOf(initialAppointment?.minute ?: 0) }

    val recurrenceOptions = listOf("None", "Daily", "Weekly", "Monthly", "3 Months", "6 Months", "1 Year")
    var recurrenceExpanded by remember { mutableStateOf(false) }

    var showTimePicker by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialAppointment == null) tr("Add Appointment") else tr("Edit Appointment"), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = doctorName,
                    onValueChange = { doctorName = it },
                    label = { Text(tr("Doctor Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text(tr("Place / Clinic Name")) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Recurrence selector dropdown
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        onClick = { recurrenceExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = tr("Recurrence"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = recurrence,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Icon(
                                imageVector = if (recurrenceExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = recurrenceExpanded,
                        onDismissRequest = { recurrenceExpanded = false }
                    ) {
                        recurrenceOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    recurrence = opt
                                    recurrenceExpanded = false
                                }
                            )
                        }
                    }
                }

                // Date field (acts as Start Date for recurring)
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(if (recurrence == "None") tr("Date (YYYY-MM-DD)") else tr("Start Date (YYYY-MM-DD)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier.matchParentSize().clickable {
                            val cal = Calendar.getInstance()
                            android.app.DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    date = "%04d-%02d-%02d".format(y, m + 1, d)
                                },
                                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = if (time.isNotEmpty()) time else "%02d:%02d".format(hour, minute),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(tr("Time (HH:MM)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier.matchParentSize().clickable {
                            showTimePicker = true
                        }
                    )
                }

                if (showTimePicker) {
                    val timeState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
                    AlertDialog(
                        onDismissRequest = { showTimePicker = false },
                        text = { TimePicker(state = timeState) },
                        confirmButton = {
                            Button(onClick = {
                                hour = timeState.hour
                                minute = timeState.minute
                                time = "%02d:%02d".format(hour, minute)
                                showTimePicker = false
                            }) { Text(tr("OK")) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTimePicker = false }) { Text(tr("Cancel")) }
                        }
                    )
                }

                errorMsg?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (doctorName.trim().isEmpty()) {
                        errorMsg = "Doctor name is required."
                        return@Button
                    }
                    if (place.trim().isEmpty()) {
                        errorMsg = "Place is required."
                        return@Button
                    }
                    var finalDate = date.trim()
                    if (finalDate.isEmpty()) {
                        if (recurrence == "None") {
                            errorMsg = "Date is required."
                            return@Button
                        } else {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            finalDate = sdf.format(java.util.Date())
                        }
                    }
                    onConfirm(
                        AppointmentSchedule(
                            id = initialAppointment?.id ?: UUID.randomUUID().toString(),
                            doctorName = doctorName.trim(),
                            place = place.trim(),
                            isRecurring = recurrence != "None",
                            recurrence = recurrence,
                            date = finalDate,
                            time = "%02d:%02d".format(hour, minute),
                            hour = hour,
                            minute = minute
                        )
                    )
                }
            ) {
                Text(tr("Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        }
    )
}

@Composable
private fun AppointmentCard(
    appointment: AppointmentSchedule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Dr. ${appointment.doctorName}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(appointment.place, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    val recLabel = when (appointment.recurrence) {
                        "None", "" -> if (appointment.isRecurring) "Daily" else "One-off"
                        else -> appointment.recurrence
                    }
                    val dateStr = if (recLabel == "One-off") appointment.date else "$recLabel starting ${appointment.date}"
                    Text("$dateStr at ${appointment.time}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = tr("Edit Appointment"), tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = tr("Delete Appointment"), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

