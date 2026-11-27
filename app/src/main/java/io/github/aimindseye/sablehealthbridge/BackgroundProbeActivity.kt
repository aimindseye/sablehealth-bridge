package io.github.aimindseye.sablehealthbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val SHORT_DELAY = 2L
private const val LONG_DELAY = 75L

internal class ProbeGrant(
    val authorization: ProbeAuthorization,
    val resolution: IntentSenderRequest? = null,
)

internal data class ProbeObservation(
    val state: WorkInfo.State,
    val result: String?,
)

/** Retains only the scheduled work identifier, never the authorization token. */
internal interface ProbeWorkIdStore {
    fun restoreId(): String?

    fun saveId(id: UUID): Boolean
}

/** Only SDK effects are abstracted; the activity owns all scheduling decisions. */
internal interface ProbePlatform : ProbeWorkIdStore {
    fun available(): Boolean

    fun requestPermissions()

    fun authorize(
        success: (ProbeGrant) -> Unit,
        failure: () -> Unit,
    )

    fun launchConsent(grant: ProbeGrant)

    fun decodeConsent(data: Intent): ProbeGrant

    suspend fun binding(
        access: String,
        salt: String,
    ): String

    fun enqueue(
        work: OneTimeWorkRequest,
        failure: () -> Unit,
    )

    fun observe(id: UUID): LiveData<ProbeObservation?>

    fun cancel()
}

class BackgroundProbeActivity : ComponentActivity() {
    internal var platform: ProbePlatform = AndroidProbePlatform(this)
    internal var message by mutableStateOf("Read-only test. No sleep records will be written or deleted.")
        private set
    internal var busy by mutableStateOf(false)
        private set
    internal var queued by mutableStateOf(false)
        private set
    private var pendingDelay: Long? = null
    private var observation: LiveData<ProbeObservation?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform.restoreId()?.let { id ->
            runCatching { UUID.fromString(id) }.onSuccess(::observe)
        }
        setContent {
            BridgeTheme {
                ProbeScreen(
                    message,
                    busy,
                    queued,
                    ProbeActions(::requestPermissions, ::start, ::cancel, ::finish),
                )
            }
        }
    }

    internal fun permissionResult(granted: Set<String>) {
        busy = false
        message = if (granted.containsAll(PROBE_PERMISSIONS)) "Background access granted" else "Permission required"
    }

    internal fun consentResult(
        resultCode: Int,
        data: Intent?,
    ) {
        if (resultCode == RESULT_OK && pendingDelay != null) {
            runCatching { platform.decodeConsent(requireNotNull(data)) }
                .onSuccess(::accept)
                .onFailure { fail("Google authorization not confirmed") }
        } else {
            fail("Authorization cancelled; no work scheduled")
        }
    }

    internal fun requestPermissions() {
        if (busy || queued) return
        busy = true
        runCatching {
            if (platform.available()) {
                platform.requestPermissions()
            } else {
                fail("Background Health Connect access unavailable")
            }
        }.onFailure { fail("Unable to open permissions") }
    }

    internal fun start(delay: Long) {
        if (busy || queued || delay !in setOf(SHORT_DELAY, LONG_DELAY)) return
        busy = true
        pendingDelay = delay
        message = "Checking Google authorization…"
        runCatching {
            platform.authorize(
                { grant -> resolveProbeGrant(grant, platform, ::accept, ::fail) },
                { fail("Google authorization unavailable") },
            )
        }.onFailure { fail("Google authorization unavailable") }
    }

    private fun accept(grant: ProbeGrant) {
        val delay = pendingDelay ?: return fail("Restart the test")
        lifecycleScope.launch {
            try {
                val access = silentAccess(grant.authorization)
                val salt = UUID.randomUUID().toString()
                val binding = platform.binding(access, salt)
                schedule(delay, salt, binding)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fail("Account binding or scheduling not confirmed")
            }
        }
    }

    private fun schedule(
        delay: Long,
        salt: String,
        binding: String,
    ) {
        val work =
            OneTimeWorkRequestBuilder<BackgroundProbeWorker>()
                .setInitialDelay(delay, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(PROBE_SALT to salt, PROBE_BINDING to binding))
                .build()
        check(platform.saveId(work.id))
        pendingDelay = null
        busy = false
        queued = true
        message = "Scheduled; leave the app with Home. Do not force stop."
        runCatching {
            observe(work.id)
            platform.enqueue(work) {
                queued = false
                fail("Work scheduling failed")
            }
        }.onFailure { queued = false }
            .getOrThrow()
    }

    private fun observe(id: UUID) {
        observation?.removeObservers(this)
        observation =
            platform.observe(id).also { data ->
                data.observe(this) { info ->
                    if (info != null) {
                        queued = !info.state.isFinished
                        message = probeStatus(info.state, info.result)
                    } else if (!busy && !queued) {
                        message = "No retained test result"
                    }
                }
            }
    }

    internal fun cancel() {
        if (!queued) return
        runCatching {
            platform.cancel()
            message = "Cancellation requested"
        }.onFailure { fail("Cancellation not confirmed") }
    }

    private fun fail(label: String) {
        pendingDelay = null
        busy = false
        message = label
    }
}

private fun resolveProbeGrant(
    grant: ProbeGrant,
    platform: ProbePlatform,
    accept: (ProbeGrant) -> Unit,
    fail: (String) -> Unit,
) {
    if (grant.authorization.needsResolution) {
        runCatching { platform.launchConsent(grant) }
            .onFailure { fail("Unable to open Google consent") }
    } else {
        accept(grant)
    }
}

internal fun probeStatus(
    state: WorkInfo.State,
    result: String?,
): String =
    when (state) {
        WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED ->
            ProbeOutcome.entries.firstOrNull { it.name == result }?.name ?: ProbeOutcome.READ_NOT_CONFIRMED.name
        WorkInfo.State.CANCELLED -> "Cancelled"
        WorkInfo.State.RUNNING -> "Background test running"
        else -> "Scheduled; leave the app with Home. Do not force stop."
    }

internal data class ProbeActions(
    val permissions: () -> Unit,
    val start: (Long) -> Unit,
    val cancel: () -> Unit,
    val back: () -> Unit,
)

@Composable
internal fun ProbeScreen(
    message: String,
    busy: Boolean,
    queued: Boolean,
    actions: ProbeActions,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Background test", style = MaterialTheme.typography.titleMedium)
            Text("SB-R2A · Read only", style = MaterialTheme.typography.labelSmall)
            Text(message, modifier = Modifier.testTag("probe-status"), style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = actions.permissions,
                enabled = !busy && !queued,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Background permissions") }
            Button(
                onClick = { actions.start(SHORT_DELAY) },
                enabled = !busy && !queued,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-short"),
            ) { Text("Test in 2 minutes") }
            Button(
                onClick = { actions.start(LONG_DELAY) },
                enabled = !busy && !queued,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("probe-long"),
            ) { Text("Test in 75 minutes") }
            TextButton(onClick = actions.cancel, enabled = queued, modifier = Modifier.testTag("probe-cancel")) {
                Text("Cancel test")
            }
            TextButton(onClick = actions.back) { Text("Back") }
        }
    }
}
