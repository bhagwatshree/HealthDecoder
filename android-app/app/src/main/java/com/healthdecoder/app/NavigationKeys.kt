package com.healthdecoder.app

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Main : NavKey

@Serializable
data object IPConfig : NavKey

@Serializable
data class Scan(val initialImagePath: String? = null) : NavKey

@Serializable
data class ReportDetail(val reportId: String, val highlightParam: String? = null) : NavKey

@Serializable
data class DetailedAnalysis(val reportId: String) : NavKey

@Serializable
data object Compare : NavKey

@Serializable
data class Chat(val contextHint: String? = null) : NavKey

@Serializable
data object Trends : NavKey

@Serializable
data object Login : NavKey

@Serializable
data class Register(val msisdn: String? = null) : NavKey

@Serializable
data object Account : NavKey

@Serializable
data object Records : NavKey

@Serializable
data object MedicationTracker : NavKey

// focus = "medicines" (Medication Reminders) or "appointments" (Doctor Appointments) — the Home
// screen offers each as its own tile, both landing on this screen scoped to the chosen section.
@Serializable
data class Reminders(val focus: String = "medicines") : NavKey

@Serializable
data object PendingTests : NavKey

@Serializable
data class Discovery(val category: String = "lab_tests", val query: String? = null) : NavKey

@Serializable
data object LiveVision : NavKey

@Serializable
data class DoctorBrief(val patientName: String) : NavKey
