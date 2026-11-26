package io.github.aimindseye.sablehealthbridge

import androidx.activity.ComponentActivity

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
