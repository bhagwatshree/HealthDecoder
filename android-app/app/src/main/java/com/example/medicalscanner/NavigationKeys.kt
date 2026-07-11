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
data class ReportDetail(val reportId: String) : NavKey

@Serializable
data class DetailedAnalysis(val reportId: String) : NavKey

@Serializable
data object Compare : NavKey

@Serializable
data object Chat : NavKey

@Serializable
data object Trends : NavKey
