package com.healthdecoder.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The privacy policy makes claims about what the app does. Those claims went out of date silently:
 * it promised a server-side restore that no code performs, and described two features that are
 * switched off. A policy that overstates what is collected is a Play Data Safety mismatch; one
 * that understates it is worse.
 *
 * These tests tie the policy text to the switches that decide whether it is true, so re-enabling a
 * feature fails the build until the wording is revisited.
 */
class LegalContentAccuracyTest {

    private val policy: String by lazy {
        File("src/main/java/com/healthdecoder/app/ui/LegalContent.kt").readText()
    }

    @Test
    fun `the policy does not promise a server-side copy of medical records`() {
        // NetworkModule declares uploadReport/updateReport, but nothing calls them — records never
        // leave the device. Claiming otherwise tells users a restore exists that does not.
        assertTrue(
            "the policy must state that reports stay on the device",
            policy.contains("Your reports never leave your device")
        )
        assertFalse(
            "the policy again claims reports are stored on the backend",
            policy.contains("your profile and reports (so you can")
        )
    }

    @Test
    fun `the policy does not promise that AI providers never train on submitted content`() {
        // README's own launch checklist flags the Gemini free tier as permitting Google to use
        // submissions to improve its products. Promising otherwise is a privacy claim we cannot keep.
        assertFalse(
            "the policy again promises providers do not train on user data",
            policy.contains("do not use it to train models on your data")
        )
    }

    @Test
    fun `location wording matches whether discovery is actually enabled`() {
        val saysNoLocation = policy.contains("does not use or request your location")
        assertTrue(
            "DISCOVERY_ENABLED is ${FeatureFlags.DISCOVERY_ENABLED} but the privacy policy still " +
                "says the app does not use location — re-enabling discovery means location is " +
                "collected again, and this section has to be rewritten before shipping it",
            saysNoLocation != FeatureFlags.DISCOVERY_ENABLED
        )
    }

    @Test
    fun `gmail wording matches whether inbox scanning is actually enabled`() {
        val saysNoEmailAccess = policy.contains("does not read your email")
        assertTrue(
            "GMAIL_SYNC_ENABLED is ${FeatureFlags.GMAIL_SYNC_ENABLED} but the privacy policy still " +
                "says the app does not read email — re-enabling inbox scanning means it does, and " +
                "this section has to be rewritten before shipping it",
            saysNoEmailAccess != FeatureFlags.GMAIL_SYNC_ENABLED
        )
    }

    @Test
    fun `the unreviewed-draft warning is still present`() {
        // Correcting the factual errors does not make this a lawyer-reviewed policy, and it must
        // not be mistaken for one just because it now reads as accurate.
        assertTrue(
            "the draft/not-lawyer-reviewed warning was removed — correcting facts is not legal review",
            policy.contains("NOT REVIEWED BY A LAWYER")
        )
    }
}
