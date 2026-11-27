package io.github.aimindseye.sablehealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal const val PROBE_WORK = "sbr2a-read-only"
internal const val PROBE_SALT = "probe_salt"
internal const val PROBE_BINDING = "probe_binding"
internal const val PROBE_RESULT = "probe_result"
private const val AUTH_TIMEOUT_SECONDS = 30L
internal val PROBE_PERMISSIONS = SLEEP_PERMISSIONS + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

internal fun probeAuthorizationRequest(): AuthorizationRequest =
    AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SLEEP_SCOPE))).build()

internal fun backgroundReadAvailable(context: Context): Boolean =
    HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE &&
        HealthConnectClient.getOrCreate(context).features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

internal fun readAccountBinding(
    access: String,
    salt: String,
    fetch: (String) -> JSONObject = { fetchIdentity(it) },
): String = accountBinding(fetch(access).getString("healthUserId"), salt)

class BackgroundProbeWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            executeProbeWork(inputData) { AndroidProbePorts(applicationContext) }
        }
}

internal suspend fun executeProbeWork(
    input: Data,
    ports: () -> ProbePorts,
): ListenableWorker.Result {
    val binding = input.getString(PROBE_BINDING).orEmpty()
    val salt = input.getString(PROBE_SALT).orEmpty()
    val outcome =
        if (binding.matches(Regex("[a-f0-9]{64}")) && salt.matches(Regex("[a-f0-9-]{36}"))) {
            runProbe(binding, salt, ports())
        } else {
            ProbeOutcome.READ_NOT_CONFIRMED
        }
    val output = workDataOf(PROBE_RESULT to outcome.name)
    return if (outcome == ProbeOutcome.PASS_READ_ONLY) {
        ListenableWorker.Result.success(output)
    } else {
        ListenableWorker.Result.failure(output)
    }
}

private class AndroidProbePorts(
    private val applicationContext: Context,
) : ProbePorts {
    override suspend fun visible(): Boolean =
        withContext(Dispatchers.Main) {
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }

    override suspend fun checkPermissions() {
        if (!backgroundReadAvailable(applicationContext)) throw ProbeStop(ProbeOutcome.FEATURE_UNAVAILABLE)
        val granted = HealthConnectClient.getOrCreate(applicationContext).permissionController.getGrantedPermissions()
        if (!granted.containsAll(PROBE_PERMISSIONS)) throw ProbeStop(ProbeOutcome.PERMISSION_REQUIRED)
    }

    override suspend fun authorize(): ProbeAuthorization {
        val result =
            Tasks.await(
                Identity.getAuthorizationClient(applicationContext).authorize(probeAuthorizationRequest()),
                AUTH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        // Never open a resolution intent from a worker.
        return ProbeAuthorization(result.hasResolution(), result.grantedScopes.orEmpty(), result.accessToken)
    }

    override suspend fun identity(
        access: String,
        salt: String,
    ): String = readAccountBinding(access, salt)

    override suspend fun inspect(access: String) {
        val session =
            retrySleepRead {
                readLatest(Instant.now(), ZoneId.systemDefault()) { url -> fetchPage(url, access) }
            }
        ConnectSleep(applicationContext).inspect(session)
    }
}
