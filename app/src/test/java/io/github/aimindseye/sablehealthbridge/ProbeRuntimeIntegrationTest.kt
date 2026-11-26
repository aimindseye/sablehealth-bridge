package io.github.aimindseye.sablehealthbridge

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.InitializationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val WAIT_SECONDS = 10L
private const val POLL_MILLIS = 10L
private const val POLL_LIMIT = 1000

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeRuntimeIntegrationTest {
    @Test
    fun workerEntryRejectsMalformedInput() {
        val context = RuntimeEnvironment.getApplication()
        val worker = TestListenableWorkerBuilder<BackgroundProbeWorker>(context, inputData = Data.EMPTY).build()
        val expected = ListenableWorker.Result.failure(workDataOf(PROBE_RESULT to "READ_NOT_CONFIRMED"))
        check(awaitProbeResult(worker) == expected)
    }

    @Test
    fun workerStopsBeforeSdkAccessWhileAppVisible() {
        initializeProcessLifecycle()
        withActivity { activity ->
            shadowOf(Looper.getMainLooper()).idle()
            check(
                ProcessLifecycleOwner
                    .get()
                    .lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED),
            )
            val salt = "feaf7402-5d0c-43ab-a472-06ae3d3b820d"
            val binding = accountBinding("synthetic-user", salt)
            val data = workDataOf(PROBE_SALT to salt, PROBE_BINDING to binding)
            val context = activity.applicationContext
            val worker = TestListenableWorkerBuilder<BackgroundProbeWorker>(context, inputData = data).build()
            val expected = ListenableWorker.Result.failure(workDataOf(PROBE_RESULT to "NOT_BACKGROUND"))
            check(awaitProbeResult(worker) == expected)
        }
    }

    @Test
    fun workerRejectsUnavailableBackgroundFeature() {
        val context = RuntimeEnvironment.getApplication()
        check(
            !ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED),
        )
        check(!backgroundReadAvailable(context))
        val salt = "feaf7402-5d0c-43ab-a472-06ae3d3b820d"
        val binding = accountBinding("synthetic-user", salt)
        val input = workDataOf(PROBE_SALT to salt, PROBE_BINDING to binding)
        val worker = TestListenableWorkerBuilder<BackgroundProbeWorker>(context, inputData = input).build()
        val expected = ListenableWorker.Result.failure(workDataOf(PROBE_RESULT to "FEATURE_UNAVAILABLE"))
        check(awaitProbeResult(worker) == expected)
    }

    @Test
    fun realSchedulerReplacesAndCancelsDelayedReadOnlyWork() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val manager = WorkManager.getInstance(context)
        try {
            withActivity { activity ->
                verifyReplacement(activity.platform, manager)
            }
        } finally {
            manager.cancelAllWork().result.get(WAIT_SECONDS, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test
    fun permissionLauncherRequestsOnlyDeclaredAccessAndHandlesDenial() {
        withActivity { activity ->
            shadowOf(activity.application).denyPermissions(*PROBE_PERMISSIONS.toTypedArray())
            activity.platform.requestPermissions()
            val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
            check(request.requestedPermissions.toSet() == PROBE_PERMISSIONS)
            val result = IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED }
            val response =
                Intent()
                    .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
                    .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, result)
            check(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, response))
            check(activity.message == "Permission required" && !activity.queued)
        }
    }

    @Test
    fun consentLauncherUsesOnlySuppliedResolutionAndHandlesCancellation() {
        withActivity { activity ->
            val grant = ProbeAuthorization(true, emptyList(), null)
            check(runCatching { activity.platform.decodeConsent(Intent()) }.isFailure)
            check(runCatching { activity.platform.launchConsent(ProbeGrant(grant)) }.isFailure)
            val intent = Intent(activity, PrivacyActivity::class.java)
            val pending = PendingIntent.getActivity(activity, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            try {
                val request = IntentSenderRequest.Builder(pending).build()
                activity.platform.launchConsent(ProbeGrant(grant, request))
                val launched = requireNotNull(shadowOf(activity).lastIntentSenderRequest)
                check(launched.intentSender == pending.intentSender)
                val handled =
                    activity.activityResultRegistry.dispatchResult(
                        launched.requestCode,
                        Activity.RESULT_CANCELED,
                        null,
                    )
                check(handled)
                check(!activity.queued && activity.message.contains("cancelled"))
            } finally {
                pending.cancel()
            }
        }
    }

    @Test
    fun authorizationRequestNeverRequestsOfflineCredentials() {
        val request = probeAuthorizationRequest()
        check(request.requestedScopes.map { it.scopeUri }.toSet() == setOf(SLEEP_SCOPE))
        check(!request.isOfflineAccessRequested)
        check(!request.isForceCodeForRefreshToken)
    }

    private fun withActivity(block: (BackgroundProbeActivity) -> Unit) {
        val lifecycle = Robolectric.buildActivity(BackgroundProbeActivity::class.java).setup()
        try {
            block(lifecycle.get())
        } finally {
            lifecycle.pause().stop().destroy()
        }
    }
}

private fun initializeProcessLifecycle() {
    val application = RuntimeEnvironment.getApplication()
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    if (lifecycle.currentState == Lifecycle.State.INITIALIZED) {
        // Robolectric does not always start manifest providers before activities.
        val authority = "${application.packageName}.androidx-startup"
        Robolectric.setupContentProvider(InitializationProvider::class.java, authority)
    }
    shadowOf(Looper.getMainLooper()).idle()
    check(lifecycle.currentState == Lifecycle.State.CREATED) {
        "Process lifecycle startup not confirmed: ${lifecycle.currentState}"
    }
}

private fun awaitProbeResult(worker: BackgroundProbeWorker): ListenableWorker.Result {
    val executor = Executors.newSingleThreadExecutor()
    val future = executor.submit<ListenableWorker.Result> { runBlocking { worker.doWork() } }
    try {
        repeat(POLL_LIMIT) {
            shadowOf(Looper.getMainLooper()).idle()
            try {
                return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Pump the main dispatcher while the worker checks process visibility.
            }
        }
        error("Foreground guard did not finish")
    } finally {
        if (!future.isDone) future.cancel(true)
        executor.shutdownNow()
    }
}

private fun verifyReplacement(
    platform: ProbePlatform,
    manager: WorkManager,
) {
    // Both requests are delayed and contain invalid input: no network or health I/O.
    val first = delayedWork()
    val second = delayedWork()
    val updates = platform.observe(second.id)
    val observations = LinkedBlockingQueue<ProbeObservation>()
    val observer =
        Observer<ProbeObservation?> { info ->
            if (info != null) observations.add(info)
        }
    updates.observeForever(observer)
    try {
        platform.enqueue(first) { error("First enqueue failed") }
        check(state(manager, first.id) == WorkInfo.State.ENQUEUED)
        platform.enqueue(second) { error("Replacement enqueue failed") }
        val replaced = manager.getWorkInfoById(first.id).get(WAIT_SECONDS, TimeUnit.SECONDS)
        check(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        check(state(manager, second.id) == WorkInfo.State.ENQUEUED)
        awaitObservation(observations, WorkInfo.State.ENQUEUED)
        platform.cancel()
        check(state(manager, second.id) == WorkInfo.State.CANCELLED)
        val cancelled = awaitObservation(observations, WorkInfo.State.CANCELLED)
        check(cancelled.result == null)
    } finally {
        updates.removeObserver(observer)
    }
}

private fun delayedWork() =
    OneTimeWorkRequestBuilder<BackgroundProbeWorker>()
        .setInitialDelay(75, TimeUnit.MINUTES)
        .build()

private fun state(
    manager: WorkManager,
    id: UUID,
): WorkInfo.State {
    val info = manager.getWorkInfoById(id).get(WAIT_SECONDS, TimeUnit.SECONDS)
    return requireNotNull(info).state
}

private fun awaitObservation(
    observations: LinkedBlockingQueue<ProbeObservation>,
    expected: WorkInfo.State,
): ProbeObservation {
    repeat(POLL_LIMIT) {
        shadowOf(Looper.getMainLooper()).idle()
        val info = observations.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)
        if (info?.state == expected) return info
    }
    error("Work observation not confirmed")
}
