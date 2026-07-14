package com.example.medicalscanner

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Main : NavKey

@Serializable
data object IPConfig : NavKey

@Serializable
data object Scan : NavKey

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

@Serializable
data object Reminders : NavKey

@Serializable
data object PendingTests : NavKey
