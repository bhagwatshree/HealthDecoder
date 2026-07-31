package com.healthdecoder.app

/**
 * Build-time feature switches. Kept as `const` so a disabled feature's code is dead-stripped
 * by R8 in release builds rather than merely hidden.
 */
object FeatureFlags {

    /**
     * Phone-number (MSISDN) OTP sign-in — the Login / Register screens.
     *
     * OFF by default: each OTP sends a billed SMS, and the app is fully usable without an
     * account (records, reminders and AI analysis are all on-device). With this off, the app
     * opens straight to Home and never asks the user to sign in.
     *
     * Turn it back on by setting `PHONE_AUTH_ENABLED=true` in `local.properties`.
     */
    val PHONE_AUTH_ENABLED: Boolean = BuildConfig.PHONE_AUTH_ENABLED
}
