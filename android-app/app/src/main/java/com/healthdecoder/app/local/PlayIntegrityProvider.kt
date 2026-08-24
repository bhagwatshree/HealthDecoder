package com.healthdecoder.app.local

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.util.concurrent.TimeUnit

/**
 * Obtains a Play Integrity token, which the backend exchanges for proof that this is a genuine,
 * Play-installed build of the app on a real device — see `backend/attestation.js`.
 *
 * BEST EFFORT BY DESIGN. Returns null on any failure, and [DeviceIdentity] still registers without
 * it. That is deliberate: the backend only enforces attestation when
 * ATTESTATION_ENFORCE_ANDROID=true, so during rollout a device that cannot produce a token (no Play
 * services, a sideloaded build, Google's service briefly down) keeps working exactly as before.
 * Failing hard here would strand users to protect against an abuse vector, which is the wrong
 * trade for a health app people depend on.
 *
 * The token is bound to the caller's deviceId through `requestHash`, so one minted for a device
 * cannot be replayed to register a different one.
 */
object PlayIntegrityProvider {
    private const val TAG = "PlayIntegrity"

    /** Blocking — call from a background thread, matching DeviceIdentity's style. */
    fun tokenFor(context: Context, nonce: String): String? {
        val cloudProjectNumber = runCatching {
            context.getString(
                context.resources.getIdentifier(
                    "play_integrity_cloud_project_number", "string", context.packageName
                )
            ).toLongOrNull()
        }.getOrNull()

        return try {
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val requestBuilder = IntegrityTokenRequest.builder().setNonce(nonce)
            // Only set when configured — passing 0 makes the request fail outright.
            cloudProjectNumber?.let { requestBuilder.setCloudProjectNumber(it) }

            val response = Tasks.await(
                manager.requestIntegrityToken(requestBuilder.build()),
                20, TimeUnit.SECONDS
            )
            response.token()
        } catch (e: Exception) {
            // Expected on emulators, sideloaded builds and devices without Play services. Logged
            // at debug so it never looks like an error in a normal install.
            Log.d(TAG, "Integrity token unavailable: ${e.message}")
            null
        }
    }
}
