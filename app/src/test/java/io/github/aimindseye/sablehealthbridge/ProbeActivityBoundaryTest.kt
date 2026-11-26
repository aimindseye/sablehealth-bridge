package io.github.aimindseye.sablehealthbridge

import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeActivityBoundaryTest {
    @Test
    fun permissionsUnavailableDeniedGrantedAndLaunchFailure() {
        withActivity { activity, platform ->
            platform.supported = false
            activity.requestPermissions()
            check(!activity.busy && platform.permissionCalls == 0)
            check(activity.message.contains("unavailable"))
            platform.supported = true
            platform.permissionThrows = true
            activity.requestPermissions()
            check(!activity.busy && activity.message.contains("Unable"))
            platform.permissionThrows = false
            activity.requestPermissions()
            activity.requestPermissions()
            check(activity.busy && platform.permissionCalls == 2)
            activity.permissionResult(emptySet())
            check(!activity.busy && activity.message == "Permission required")
            activity.requestPermissions()
            activity.permissionResult(PROBE_PERMISSIONS)
            check(!activity.busy && activity.message == "Background access granted")
        }
    }

    @Test
    fun schedulesOnceAndObservesCompletionAndCancellation() {
        withActivity { activity, platform ->
            activity.start(3)
            check(platform.authorizations == 0)
            activity.start(2)
            activity.start(75)
            check(platform.authorizations == 1 && activity.busy)
            platform.authorized()
            settle()
            check(!activity.busy && activity.queued)
            check(platform.work?.id == platform.saved && platform.saved == platform.observed)
            check(platform.bindingCalls == 1 && platform.enqueues == 1)
            activity.start(75)
            activity.requestPermissions()
            check(platform.authorizations == 1 && platform.permissionCalls == 0)
            platform.publish(WorkInfo.State.RUNNING)
            check(activity.message == "Background test running")
            platform.cancelThrows = true
            activity.cancel()
            check(activity.message == "Cancellation not confirmed")
            platform.cancelThrows = false
            activity.cancel()
            check(activity.message == "Cancellation requested" && platform.cancellations == 2)
            platform.publish(WorkInfo.State.CANCELLED)
            check(!activity.queued && activity.message == "Cancelled")
            activity.cancel()
            check(platform.cancellations == 2)
            activity.start(75)
            platform.authorized()
            settle()
            platform.publish(WorkInfo.State.SUCCEEDED, "PASS_READ_ONLY")
            check(!activity.queued && activity.message == "PASS_READ_ONLY")
            check(platform.enqueues == 2)
        }
    }

    @Test
    fun handlesConsentCancellationResolutionAndDecoderFailure() {
        withActivity { activity, platform ->
            activity.consentResult(Activity.RESULT_OK, Intent())
            check(!activity.busy && !activity.queued && platform.enqueues == 0)
            activity.start(2)
            platform.authorized(resolution = true)
            check(platform.consents == 1 && activity.busy)
            activity.consentResult(Activity.RESULT_CANCELED, null)
            check(!activity.busy && platform.enqueues == 0)
            activity.start(2)
            platform.consentThrows = true
            platform.authorized(resolution = true)
            check(!activity.busy && activity.message.contains("consent"))
            platform.consentThrows = false
            activity.start(2)
            platform.authorized(resolution = true)
            activity.consentResult(Activity.RESULT_OK, null)
            check(!activity.busy && platform.enqueues == 0)
            activity.start(2)
            platform.authorized(resolution = true)
            platform.decodeThrows = true
            activity.consentResult(Activity.RESULT_OK, Intent())
            check(!activity.busy && platform.enqueues == 0)
            platform.decodeThrows = false
            activity.start(2)
            platform.authorized(resolution = true)
            activity.consentResult(Activity.RESULT_OK, Intent())
            settle()
            check(activity.queued && platform.enqueues == 1)
        }
    }

    @Test
    fun rejectsAuthorizationAndBindingFailuresWithoutScheduling() {
        withActivity { activity, platform ->
            platform.authThrows = true
            activity.start(2)
            check(!activity.busy && activity.message.contains("unavailable"))
            platform.authThrows = false
            activity.start(2)
            platform.authFailure?.invoke()
            check(!activity.busy && platform.enqueues == 0)
            activity.start(2)
            platform.authorized(scopes = emptyList())
            settle()
            check(!activity.busy && platform.bindingCalls == 0)
            activity.start(2)
            platform.bindingThrows = true
            platform.authorized()
            settle()
            check(!activity.busy && platform.enqueues == 0)
            platform.bindingThrows = false
            platform.saveSucceeds = false
            activity.start(2)
            platform.authorized()
            settle()
            check(!activity.busy && !activity.queued && platform.enqueues == 0)
        }
    }

    @Test
    fun schedulingFailuresDoNotLeaveUiBusy() {
        withActivity { activity, platform ->
            platform.observeThrows = true
            activity.start(2)
            platform.authorized()
            settle()
            check(!activity.busy && !activity.queued && platform.enqueues == 0)
            platform.observeThrows = false
            platform.enqueueThrows = true
            activity.start(2)
            platform.authorized()
            settle()
            check(!activity.busy && !activity.queued)
            platform.enqueueThrows = false
            activity.start(2)
            platform.authorized()
            settle()
            platform.enqueueFailure?.invoke()
            check(!activity.busy && !activity.queued)
            check(activity.message == "Work scheduling failed")
        }
    }

    @Test
    fun restoresExistingWorkAndIgnoresInvalidId() {
        val id = UUID.randomUUID()
        withActivity(FakeProbePlatform(restored = id.toString())) { activity, platform ->
            check(platform.observed == id)
            platform.publish(WorkInfo.State.ENQUEUED)
            check(activity.queued)
            platform.publish(WorkInfo.State.FAILED, "AUTH_REQUIRED")
            check(!activity.queued && activity.message == "AUTH_REQUIRED")
            platform.updates.value = null
            check(activity.message == "No retained test result")
        }
        withActivity(FakeProbePlatform(restored = "invalid")) { activity, platform ->
            check(platform.observed == null && !activity.queued)
        }
    }

    @Test
    fun realPlatformPersistsOnlyWorkIdAndStartsWithoutScheduling() {
        val lifecycle = Robolectric.buildActivity(BackgroundProbeActivity::class.java)
        val activity = lifecycle.get()
        lifecycle.setup()
        try {
            val platform = activity.platform
            check(platform.restoreId() == null && !activity.queued)
            val id = UUID.randomUUID()
            check(platform.saveId(id))
            check(platform.restoreId() == id.toString())
            val values = activity.getSharedPreferences("sbr2a_probe", android.content.Context.MODE_PRIVATE).all
            check(values == mapOf("work_id" to id.toString()))
        } finally {
            lifecycle.pause().stop().destroy()
        }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idle()

    private fun withActivity(
        platform: FakeProbePlatform = FakeProbePlatform(),
        block: (BackgroundProbeActivity, FakeProbePlatform) -> Unit,
    ) {
        val lifecycle = Robolectric.buildActivity(BackgroundProbeActivity::class.java)
        val activity = lifecycle.get()
        activity.platform = platform
        lifecycle.setup()
        try {
            block(activity, platform)
        } finally {
            lifecycle.pause().stop().destroy()
        }
    }
}

private class FakeProbePlatform(
    private val restored: String? = null,
) : ProbePlatform {
    var supported = true
    var permissionThrows = false
    var authThrows = false
    var consentThrows = false
    var decodeThrows = false
    var bindingThrows = false
    var saveSucceeds = true
    var enqueueThrows = false
    var observeThrows = false
    var cancelThrows = false
    var permissionCalls = 0
    var authorizations = 0
    var consents = 0
    var bindingCalls = 0
    var enqueues = 0
    var cancellations = 0
    var work: OneTimeWorkRequest? = null
    var saved: UUID? = null
    var observed: UUID? = null
    var authFailure: (() -> Unit)? = null
    var enqueueFailure: (() -> Unit)? = null
    private var authSuccess: ((ProbeGrant) -> Unit)? = null
    val updates = MutableLiveData<ProbeObservation?>()

    override fun restoreId(): String? = restored

    override fun saveId(id: UUID): Boolean {
        saved = id
        return saveSucceeds
    }

    override fun available(): Boolean = supported

    override fun requestPermissions() {
        permissionCalls++
        check(!permissionThrows)
    }

    override fun authorize(
        success: (ProbeGrant) -> Unit,
        failure: () -> Unit,
    ) {
        authorizations++
        check(!authThrows)
        authSuccess = success
        authFailure = failure
    }

    fun authorized(
        resolution: Boolean = false,
        scopes: List<String> = listOf(SLEEP_SCOPE),
    ) {
        requireNotNull(authSuccess).invoke(ProbeGrant(ProbeAuthorization(resolution, scopes, "fixture")))
    }

    override fun launchConsent(grant: ProbeGrant) {
        consents++
        check(grant.authorization.needsResolution && !consentThrows)
    }

    override fun decodeConsent(data: Intent): ProbeGrant {
        check(!decodeThrows)
        return ProbeGrant(ProbeAuthorization(false, listOf(SLEEP_SCOPE), "fixture"))
    }

    override suspend fun binding(
        access: String,
        salt: String,
    ): String {
        bindingCalls++
        check(access == "fixture" && !bindingThrows)
        return accountBinding("synthetic-user", salt)
    }

    override fun enqueue(
        work: OneTimeWorkRequest,
        failure: () -> Unit,
    ) {
        enqueues++
        check(!enqueueThrows)
        this.work = work
        enqueueFailure = failure
    }

    override fun observe(id: UUID): MutableLiveData<ProbeObservation?> {
        check(!observeThrows)
        observed = id
        updates.value = null
        return updates
    }

    override fun cancel() {
        cancellations++
        check(!cancelThrows)
    }

    fun publish(
        state: WorkInfo.State,
        result: String? = null,
    ) {
        updates.value = ProbeObservation(state, result)
    }
}
