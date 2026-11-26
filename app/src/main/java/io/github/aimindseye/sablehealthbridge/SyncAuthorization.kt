package io.github.aimindseye.sablehealthbridge

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity

/** Only the explicit foreground opt-in flow may open consent or permissions. */
internal class SyncAuthorization(
    private val activity: ComponentActivity,
    private val accepted: (ProbeAuthorization) -> Unit,
    private val failed: (SyncStatus) -> Unit,
) : SyncConsentRequest {
    private val auth by lazy { Identity.getAuthorizationClient(activity) }
    private val consent =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                runCatching { auth.getAuthorizationResultFromIntent(requireNotNull(result.data)) }
                    .onSuccess(::resolve)
                    .onFailure { failed(SyncStatus.AUTH_REQUIRED) }
            } else {
                failed(SyncStatus.AUTH_REQUIRED)
            }
        }
    private val permissions =
        activity.registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(PROBE_PERMISSIONS)) authorize() else failed(SyncStatus.PERMISSION_REQUIRED)
        }

    override fun request() {
        runCatching {
            if (backgroundReadAvailable(activity)) {
                permissions.launch(PROBE_PERMISSIONS)
            } else {
                failed(SyncStatus.UNAVAILABLE)
            }
        }.onFailure { failed(SyncStatus.PERMISSION_REQUIRED) }
    }

    private fun authorize() {
        runCatching {
            auth
                .authorize(probeAuthorizationRequest())
                .addOnSuccessListener(activity, ::resolve)
                .addOnFailureListener(activity) { failed(SyncStatus.AUTH_REQUIRED) }
        }.onFailure { failed(SyncStatus.AUTH_REQUIRED) }
    }

    private fun resolve(result: AuthorizationResult) {
        if (result.hasResolution()) {
            runCatching { consent.launch(IntentSenderRequest.Builder(requireNotNull(result.pendingIntent)).build()) }
                .onFailure { failed(SyncStatus.AUTH_REQUIRED) }
        } else {
            accepted(ProbeAuthorization(false, result.grantedScopes.orEmpty(), result.accessToken))
        }
    }
}

internal fun interface SyncConsentRequest {
    fun request()
}

internal fun interface SyncAuthorizationFactory {
    fun create(
        activity: ComponentActivity,
        accepted: (ProbeAuthorization) -> Unit,
        failed: (SyncStatus) -> Unit,
    ): SyncConsentRequest
}
