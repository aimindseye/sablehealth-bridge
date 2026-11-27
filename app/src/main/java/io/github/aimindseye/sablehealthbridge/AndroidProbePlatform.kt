package io.github.aimindseye.sablehealthbridge

import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PROBE_PREFS = "sbr2a_probe"
private const val WORK_ID = "work_id"

internal class AndroidProbePlatform(
    private val activity: BackgroundProbeActivity,
) : ProbePlatform,
    ProbeWorkIdStore by AndroidProbeWorkIdStore(activity) {
    private val manager by lazy { WorkManager.getInstance(activity.applicationContext) }
    private val auth by lazy { Identity.getAuthorizationClient(activity) }
    private val permissions =
        activity.registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            activity.permissionResult(granted)
        }
    private val consent =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            activity.consentResult(result.resultCode, result.data)
        }

    override fun available(): Boolean = backgroundReadAvailable(activity)

    override fun requestPermissions() = permissions.launch(PROBE_PERMISSIONS)

    override fun authorize(
        success: (ProbeGrant) -> Unit,
        failure: () -> Unit,
    ) {
        auth
            .authorize(probeAuthorizationRequest())
            .addOnSuccessListener(activity) { success(toGrant(it)) }
            .addOnFailureListener(activity) { failure() }
    }

    override fun launchConsent(grant: ProbeGrant) = consent.launch(requireNotNull(grant.resolution))

    override fun decodeConsent(data: Intent): ProbeGrant = toGrant(auth.getAuthorizationResultFromIntent(data))

    override suspend fun binding(
        access: String,
        salt: String,
    ): String = withContext(Dispatchers.IO) { readAccountBinding(access, salt) }

    override fun enqueue(
        work: OneTimeWorkRequest,
        failure: () -> Unit,
    ) {
        val operation = manager.enqueueUniqueWork(PROBE_WORK, ExistingWorkPolicy.REPLACE, work)
        operation.result.addListener(
            { runCatching { operation.result.get() }.onFailure { failure() } },
            ContextCompat.getMainExecutor(activity),
        )
    }

    override fun observe(id: UUID): LiveData<ProbeObservation?> =
        manager.getWorkInfoByIdLiveData(id).map { info ->
            info?.let { ProbeObservation(it.state, it.outputData.getString(PROBE_RESULT)) }
        }

    override fun cancel() {
        manager.cancelUniqueWork(PROBE_WORK)
    }

    private fun toGrant(result: AuthorizationResult): ProbeGrant =
        ProbeGrant(
            ProbeAuthorization(result.hasResolution(), result.grantedScopes.orEmpty(), result.accessToken),
            result.pendingIntent?.let { IntentSenderRequest.Builder(it).build() },
        )
}

private class AndroidProbeWorkIdStore(
    private val context: Context,
) : ProbeWorkIdStore {
    private val prefs by lazy { context.getSharedPreferences(PROBE_PREFS, Context.MODE_PRIVATE) }

    override fun restoreId(): String? = prefs.getString(WORK_ID, null)

    override fun saveId(id: UUID): Boolean {
        // Synchronous success is required before enqueue; KTX edit returns Unit.
        val editor = prefs.edit()
        editor.putString(WORK_ID, id.toString())
        return editor.commit()
    }
}
