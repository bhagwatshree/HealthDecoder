package com.healthdecoder.app.local

import androidx.compose.runtime.mutableIntStateOf

/**
 * A process-wide "the stored data changed" counter that Compose screens can key an effect on, so a
 * screen still sitting in the navigation back stack refreshes when it comes back into view.
 *
 * This exists because of a real defect. Home loads its family/patient picker once, in a
 * `LaunchedEffect(familyReload)`, and `familyReload` only moves when the family manager is used.
 * Scanning a report never touched it — and because Home is never removed from the back stack while
 * you scan, its effect did not re-run on return either. A scan that auto-detected a new patient
 * therefore left that patient missing from the picker for the rest of the session, and every
 * patient-scoped view filtered the new report out.
 *
 * Deliberately a single coarse counter rather than per-entity signals: the cost of an unnecessary
 * reload is a cheap local query, while the cost of a MISSED reload is a record the user believes
 * has been lost. Bump it from any write that changes what a list screen should show.
 *
 * Snapshot state is safe to write from a background thread, so repository code on Dispatchers.IO
 * can call [bump] directly.
 */
object DataChangeSignal {

    private val counter = mutableIntStateOf(0)

    /** Read this from a composable (e.g. as a `LaunchedEffect` key) to re-run on the next change. */
    val version: Int get() = counter.intValue

    fun bump() {
        counter.intValue++
    }
}
