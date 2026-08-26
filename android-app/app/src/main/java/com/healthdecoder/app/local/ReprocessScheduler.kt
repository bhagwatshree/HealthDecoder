package com.healthdecoder.app.local

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Runs report reprocessing (single row, whole document, or a whole date range) via
 * [BackgroundTasks] instead of a screen's own rememberCoroutineScope(), so navigating away —
 * switching bottom-nav tabs, backing out of a report, scrolling a document's card off-screen —
 * doesn't silently cancel a run that can take real time across several AI calls. State lives
 * here rather than in the calling composable's own remember{} for the same reason: that state
 * would reset the instant its composable left composition, making a still-running background
 * task look like it had silently stopped even though the coroutine itself kept going.
 */
object ReprocessScheduler {
    // Per-document/per-row reprocess — keyed by report id so independent documents (or a single
    // row reprocessed on its own) can run at the same time without one's spinner clobbering
    // another's, regardless of which screen happens to be on top when each finishes.
    private val busyIds = mutableStateMapOf<String, Boolean>()
    fun isBusy(reportId: String): Boolean = busyIds[reportId] == true

    /** Reprocesses every sibling section of the document [reportId] belongs to. */
    fun runDocument(context: Context, reportId: String, onDone: () -> Unit = {}) {
        if (busyIds[reportId] == true) return
        busyIds[reportId] = true
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            try {
                LocalRepository.reprocessDocumentGroup(appContext, reportId)
            } finally {
                busyIds[reportId] = false
            }
            onDone()
        }
    }

    /** Reprocesses only the one report row [reportId] — mirrors [LocalRepository.reprocessReport]. */
    fun runSingleReport(context: Context, reportId: String, onDone: (com.healthdecoder.app.model.MedicalReport?) -> Unit = {}) {
        if (busyIds[reportId] == true) return
        busyIds[reportId] = true
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            val updated = try {
                LocalRepository.reprocessReport(appContext, reportId)
            } finally {
                busyIds[reportId] = false
            }
            onDone(updated)
        }
    }

    // Bulk (date-range) reprocess — one run at a time, with global progress any screen can watch.
    var bulkBusy by mutableStateOf(false)
        private set
    var bulkProgress by mutableStateOf(0 to 0)
        private set

    fun runBulk(context: Context, period: String?, onDone: () -> Unit = {}) {
        if (bulkBusy) return
        bulkBusy = true
        bulkProgress = 0 to 0
        val appContext = context.applicationContext
        BackgroundTasks.launch {
            try {
                LocalRepository.reprocessInRange(appContext, period) { done, total ->
                    bulkProgress = done to total
                }
            } finally {
                bulkBusy = false
            }
            onDone()
        }
    }
}
