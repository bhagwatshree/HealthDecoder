package com.healthdecoder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Healthcare discovery answers from a hard-coded provider table, and its Book action shows a
 * "Booking Request Sent" confirmation without making any network call. Until that is replaced with
 * verified live data and a real booking API, no path through the app may reach it.
 *
 * The defect this guards against was not that discovery existed — it was that the disable switch
 * covered one entry point (three Home tiles) while two others stayed live, so the feature LOOKED
 * disabled. That is invisible in review and impossible to catch by reading any single file, so it
 * is asserted mechanically here instead: every navigation into Discovery must sit behind the flag.
 *
 * Source-level rather than behavioural because the entry points are Compose UI, which needs an
 * instrumented device; this runs on every JVM test run instead of only when someone remembers.
 */
class DiscoveryGatingTest {

    private val uiDir = File("src/main/java/com/healthdecoder/app/ui")

    /** Files that legitimately navigate to Discovery, and must each be flag-gated. */
    private val entryPointFiles = listOf(
        "HomeScreen.kt",             // Find Doctors / Labs / Hospitals tiles
        "DashboardComponents.kt",    // "Find Lab Centers" on a pending test
        "ReportDetailScreen.kt"      // "Find Nearby" on a specialist recommendation
    )

    @Test
    fun `discovery is disabled by default`() {
        assertFalse(
            "DISCOVERY_ENABLED must default to false while the provider data is simulated",
            FeatureFlags.DISCOVERY_ENABLED
        )
    }

    @Test
    fun `every screen that opens discovery gates it behind the feature flag`() {
        for (name in entryPointFiles) {
            val file = File(uiDir, name)
            assertTrue("missing $name — did it move?", file.exists())
            val text = file.readText()
            assertTrue(
                "$name navigates to Discovery but never mentions DISCOVERY_ENABLED — a new " +
                    "entry point was added without gating it",
                text.contains("DISCOVERY_ENABLED")
            )
        }
    }

    @Test
    fun `no other screen navigates to discovery without the flag`() {
        // Catches an entry point added to a file nobody thought to list above.
        val offenders = (uiDir.walkTopDown() + File("src/main/java/com/healthdecoder/app").walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "DiscoveryScreen.kt" && it.name != "NavigationKeys.kt" && it.name != "Navigation.kt" }
            .filter { f ->
                val t = f.readText()
                t.contains("onNavigateToDiscovery(") && !t.contains("DISCOVERY_ENABLED")
            }
            .map { it.name }
            .distinct()
            .toList()

        assertEquals("these navigate to Discovery without checking DISCOVERY_ENABLED: $offenders",
            emptyList<String>(), offenders)
    }

    @Test
    fun `the booking flow still carries its do-not-ship warning`() {
        // The dialog claims a booking succeeded while making no request. It is kept only because
        // the screen is unreachable; the warning is what stops it being revived by accident.
        val text = File(uiDir, "DiscoveryScreen.kt").readText()
        assertTrue(
            "the booking dialog's DO NOT SHIP warning was removed — it must be rebuilt against a " +
                "real booking API before discovery is enabled",
            text.contains("DO NOT SHIP THIS AS-IS")
        )
    }
}
