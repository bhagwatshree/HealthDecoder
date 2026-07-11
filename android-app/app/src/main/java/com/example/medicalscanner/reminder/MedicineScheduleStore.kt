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
    val slots: Map<String, SlotConfig>
)

object MedicineScheduleStore {
    private const val PREFS_NAME = "medicine_schedules"
    private const val KEY_SCHEDULES = "schedules_v1"
    private val gson = GsonBuilder().create()

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

    /** Removes a medicine's reminder schedule entirely. */
    fun delete(context: Context, medicineName: String, patientName: String) {
        saveAll(context, loadAll(context).filterNot { it.matches(medicineName, patientName) })
    }

    /** Deletes ALL saved reminder schedules. */
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_SCHEDULES).apply()
    }

    fun upsert(context: Context, schedule: MedicineSchedule) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.matches(schedule.medicineName, schedule.patientName) }
        if (idx >= 0) list[idx] = schedule else list.add(schedule)
        saveAll(context, list)
    }

    fun autoSeedIfAbsent(
        context: Context,
        medicineName: String,
        patientName: String,
        dosage: String,
        frequency: String,
        activeSlots: List<String>
    ) {
        if (loadAll(context).any { it.matches(medicineName, patientName) }) return
        val slots = defaultSlotTimes.mapValues { (slot, hm) ->
            SlotConfig(enabled = activeSlots.contains(slot), hour = hm.first, minute = hm.second)
        }
        upsert(context, MedicineSchedule(medicineName, patientName, dosage, frequency, slots))
    }

    private fun MedicineSchedule.matches(name: String, patient: String) =
        medicineName.equals(name, ignoreCase = true) &&
        patientName.equals(patient, ignoreCase = true)
}
