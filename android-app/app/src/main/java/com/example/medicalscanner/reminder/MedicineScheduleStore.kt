package com.example.medicalscanner.reminder

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken

data class SlotConfig(
    val enabled: Boolean = false,
    val hour: Int = 8,
    val minute: Int = 0
)

data class MedicineSchedule(
    val medicineName: String,
    val patientName: String,
    val dosage: String,
    val frequency: String,
    val slots: Map<String, SlotConfig>,
    // Days of the week the medicine is taken (Calendar.DAY_OF_WEEK: 1=Sun .. 7=Sat).
    // null or empty means EVERY day — used for weekly scripts like "Wed & Sat only"
    // (e.g. Tolvaptan). Nullable so schedules saved before this field existed still load.
    val daysOfWeek: List<Int>? = null
)

/** True if this schedule's medicine is due on [dayOfWeek] (Calendar.DAY_OF_WEEK). Every day when no
 *  specific days are set. */
fun MedicineSchedule.runsOn(dayOfWeek: Int): Boolean =
    daysOfWeek.isNullOrEmpty() || daysOfWeek!!.contains(dayOfWeek)

object MedicineScheduleStore {
    private const val PREFS_NAME = "medicine_schedules"
    private const val KEY_SCHEDULES = "schedules_v1"
    // Medicines the user explicitly deleted a reminder for. TodaysMedicinesTab re-seeds a reminder
    // for every active medicine on each load; without this, a deleted reminder would keep coming
    // back because the medicine still exists in a report. Deleting marks it here; explicitly
    // (re)adding via upsert clears it.
    private const val KEY_DISMISSED = "dismissed_meds_v1"
    private val gson = GsonBuilder().create()

    private fun dismissKey(medicineName: String, patientName: String) =
        "${medicineName.trim().lowercase()}|${patientName.trim().lowercase()}"

    private fun loadDismissed(context: Context): MutableSet<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DISMISSED, null)
            ?: return mutableSetOf()
        return try {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type)?.toMutableSet() ?: mutableSetOf()
        } catch (e: Exception) { mutableSetOf() }
    }

    private fun saveDismissed(context: Context, set: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DISMISSED, gson.toJson(set)).apply()
    }

    /** True if the user deleted this medicine's reminder and hasn't re-added it — don't auto-seed it. */
    fun isDismissed(context: Context, medicineName: String, patientName: String): Boolean =
        loadDismissed(context).contains(dismissKey(medicineName, patientName))

    /** Un-dismiss a medicine so it can be auto-seeded again — called when a NEW scan brings a fresh
     *  prescription of it, so a previously-deleted reminder legitimately comes back for the new script. */
    fun clearDismissed(context: Context, medicineName: String, patientName: String) {
        val set = loadDismissed(context)
        if (set.remove(dismissKey(medicineName, patientName))) saveDismissed(context, set)
    }

    val defaultSlotTimes = mapOf(
        "Morning"   to Pair(8,  0),
        "Afternoon" to Pair(13, 0),
        "Evening"   to Pair(18, 0),
        "Night"     to Pair(22, 0)
    )

    fun loadAll(context: Context): List<MedicineSchedule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MedicineSchedule>>() {}.type
            gson.fromJson<List<MedicineSchedule>>(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun saveAll(context: Context, schedules: List<MedicineSchedule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SCHEDULES, gson.toJson(schedules)).apply()
    }

    /** Removes a medicine's reminder schedule and remembers it as dismissed so it isn't auto-re-seeded. */
    fun delete(context: Context, medicineName: String, patientName: String) {
        saveAll(context, loadAll(context).filterNot { it.matches(medicineName, patientName) })
        saveDismissed(context, loadDismissed(context).apply { add(dismissKey(medicineName, patientName)) })
    }

    /** Deletes ALL saved reminder schedules (and the dismissed list). */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_SCHEDULES).remove(KEY_DISMISSED).apply()
    }

    fun upsert(context: Context, schedule: MedicineSchedule) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.matches(schedule.medicineName, schedule.patientName) }
        if (idx >= 0) list[idx] = schedule else list.add(schedule)
        saveAll(context, list)
        // Explicitly adding/editing a reminder un-dismisses it.
        saveDismissed(context, loadDismissed(context).apply { remove(dismissKey(schedule.medicineName, schedule.patientName)) })
    }

    /**
     * Renames a patient's medicine reminder schedule (keeping its slot times/dosage) when the
     * medicine is renamed, so the reminder keeps firing under the corrected name. Optionally
     * refreshes the shown [dosage]/[frequency]. If a schedule already exists under [newName],
     * the old one is dropped in its favour (the corrected name wins, no duplicate reminders).
     * No-op when the medicine has no schedule. Caller must run MedicineReminderManager.scheduleAll.
     */
    fun rename(
        context: Context,
        patientName: String,
        oldName: String,
        newName: String,
        dosage: String? = null,
        frequency: String? = null
    ) {
        if (newName.isBlank() || oldName.equals(newName, ignoreCase = true)) {
            // Same name — just refresh dosage/frequency if given.
            if (dosage != null || frequency != null) {
                val list = loadAll(context).toMutableList()
                val idx = list.indexOfFirst { it.matches(oldName, patientName) }
                if (idx >= 0) list[idx] = list[idx].copy(
                    dosage = dosage ?: list[idx].dosage,
                    frequency = frequency ?: list[idx].frequency
                )
                saveAll(context, list)
            }
            return
        }
        val list = loadAll(context).toMutableList()
        val oldIdx = list.indexOfFirst { it.matches(oldName, patientName) }
        if (oldIdx < 0) return
        val renamed = list[oldIdx].copy(
            medicineName = newName,
            dosage = dosage ?: list[oldIdx].dosage,
            frequency = frequency ?: list[oldIdx].frequency
        )
        // Drop any pre-existing schedule under the new name so we don't end up with two.
        list.removeAll { it.matches(newName, patientName) }
        val insertAt = list.indexOfFirst { it.matches(oldName, patientName) }
        if (insertAt >= 0) list[insertAt] = renamed else list.add(renamed)
        saveAll(context, list)
    }

    /**
     * Re-keys a patient's reminder schedules when two mis-scanned name variants are merged. If the
     * same medicine already has a schedule under [newName], the old one is dropped in its favour so
     * the merge can't leave two reminders for one medicine. Caller runs MedicineReminderManager.scheduleAll.
     */
    fun renamePatient(context: Context, oldName: String, newName: String) {
        if (oldName.equals(newName, ignoreCase = true)) return
        val list = loadAll(context).toMutableList()
        val result = mutableListOf<MedicineSchedule>()
        for (s in list) {
            if (!s.patientName.equals(oldName, ignoreCase = true)) { result.add(s); continue }
            val moved = s.copy(patientName = newName)
            // Drop any existing schedule for the same medicine already under the new patient name.
            result.removeAll { it.matches(moved.medicineName, newName) }
            if (result.none { it.matches(moved.medicineName, newName) }) result.add(moved)
        }
        saveAll(context, result)
    }

    fun autoSeedIfAbsent(
        context: Context,
        medicineName: String,
        patientName: String,
        dosage: String,
        frequency: String,
        activeSlots: List<String>,
        daysOfWeek: List<Int> = emptyList()
    ) {
        if (loadAll(context).any { it.matches(medicineName, patientName) }) return
        if (isDismissed(context, medicineName, patientName)) return // user deleted it — don't bring it back
        val slots = defaultSlotTimes.mapValues { (slot, hm) ->
            SlotConfig(enabled = activeSlots.contains(slot), hour = hm.first, minute = hm.second)
        }
        // Seed directly (not via upsert, which would clear the dismissed flag — irrelevant here since
        // we've already confirmed it isn't dismissed, but keeps the auto-seed path self-contained).
        val list = loadAll(context).toMutableList()
        list.add(MedicineSchedule(medicineName, patientName, dosage, frequency, slots, daysOfWeek.ifEmpty { null }))
        saveAll(context, list)
    }

    private fun MedicineSchedule.matches(name: String, patient: String) =
        medicineName.equals(name, ignoreCase = true) &&
        patientName.equals(patient, ignoreCase = true)
}
