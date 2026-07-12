package com.example.medicalscanner.reminder

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class AppointmentSchedule(
    val id: String = UUID.randomUUID().toString(),
    val doctorName: String,
    val date: String, // format: "YYYY-MM-DD"
    val time: String, // format: "HH:MM"
    val place: String,
    val isRecurring: Boolean = false, // legacy flag
    val recurrence: String = "None", // "None", "Daily", "Weekly", "Monthly", "3 Months", "6 Months", "1 Year"
    val hour: Int,
    val minute: Int
)

object AppointmentStore {
    private const val PREFS_NAME = "appointment_schedules"
    private const val KEY_APPOINTMENTS = "appointments_v1"
    private val gson = GsonBuilder().create()

    fun loadAll(context: Context): List<AppointmentSchedule> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APPOINTMENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppointmentSchedule>>() {}.type
            gson.fromJson<List<AppointmentSchedule>>(json, type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun saveAll(context: Context, list: List<AppointmentSchedule>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_APPOINTMENTS, gson.toJson(list)).apply()
    }

    fun upsert(context: Context, appointment: AppointmentSchedule) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == appointment.id }
        if (idx >= 0) list[idx] = appointment else list.add(appointment)
        saveAll(context, list)
    }

    fun delete(context: Context, id: String) {
        saveAll(context, loadAll(context).filterNot { it.id == id })
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_APPOINTMENTS).apply()
    }
}
