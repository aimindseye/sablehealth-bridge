package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

internal fun testSleep(): SleepSession {
    val start = Instant.parse("2025-01-01T23:00:00Z")
    val stages =
        StageType.entries.mapIndexed { index, type ->
            Stage(start.plusSeconds(index * 600L), start.plusSeconds((index + 1) * 600L), type)
        }
    return SleepSession("fixture", start, stages.last().end, null, null, stages)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeControllerTest {
    private val service = TestServices()
    private val controller = BridgeController(CoroutineScope(Dispatchers.Unconfined), service)

    @Test
    fun rejectedScopeAndAuthorizationFailureClearSession() {
        controller.preview()
        check(controller.screen.message == "Connect Google Health first")
        controller.authorize()
        controller.authorize()
        check(service.authorizations == 1 && controller.screen.busy)
        controller.acceptAuthorization(listOf(SLEEP_SCOPE, "unapproved"), "fixture")
        check(!controller.screen.connected && !controller.screen.busy)
        connect()
        controller.authorizationFailed("Google authorization cancelled")
        check(!controller.screen.connected && controller.screen.message.endsWith("cancelled"))
        controller.preview()
        check(service.reads == 0)
    }

    @Test
    fun authorizationLaunchErrorAndClear() {
        service.failure = IllegalStateException("private exception")
        controller.authorize()
        check(!controller.screen.busy && !controller.screen.connected)
        check(!controller.screen.message.contains("private exception"))
        service.failure = null
        connect()
        controller.forget()
        check(!controller.screen.connected)
        controller.preview()
        check(service.reads == 0)
    }

    @Test
    fun permissionUnavailableDeniedGrantedAndLaunchFailure() {
        service.available = false
        controller.requestHealth()
        check(service.permissionCalls == 0 && !controller.screen.busy)
        service.available = true
        controller.requestHealth()
        controller.requestHealth()
        check(service.permissionCalls == 1 && controller.screen.busy)
        controller.permissionsResult(false)
        check(!controller.screen.permissions && !controller.screen.busy)
        controller.requestHealth()
        controller.permissionsResult(true)
        check(controller.screen.permissions)
        service.failure = IllegalStateException("private exception")
        controller.requestHealth()
        check(!controller.screen.permissions && !controller.screen.busy)
    }

    @Test
    fun previewThenExplicitImportAndRecheck() {
        connect()
        controller.applyPreview()
        check(service.writes == 0)
        controller.preview()
        check(controller.screen.preview?.session == service.session)
        check(service.reads == 1 && service.inspections == 1 && service.writes == 0)
        controller.applyPreview()
        check(service.reads == 2 && service.writes == 1)
        check(controller.screen.preview == null && !controller.screen.busy)
        check(controller.screen.message == "Imported and verified")
        controller.applyPreview()
        check(service.writes == 1)
    }

    @Test
    fun staleGoogleAndNonWritablePreviewNeverWrite() {
        connect()
        controller.preview()
        service.session = service.session.copy(key = "changed")
        controller.applyPreview()
        check(controller.screen.message.contains("changed"))
        check(service.writes == 0)
        service.decision = Decision(Action.CONFLICT)
        controller.preview()
        controller.applyPreview()
        check(
            service.writes == 0 &&
                controller.screen.preview
                    ?.decision
                    ?.action == Action.CONFLICT,
        )
    }

    @Test
    fun holdAndUnknownErrorsAreSafeAndInvalidatePreview() {
        connect()
        service.failure = Hold("Grant sleep access")
        controller.preview()
        check(controller.screen.message == "Grant sleep access" && controller.screen.preview == null)
        service.failure = null
        controller.preview()
        service.writeFailure = IllegalStateException("private payload")
        controller.applyPreview()
        check(service.writes == 1 && controller.screen.preview == null && !controller.screen.busy)
        check(!controller.screen.message.contains("private payload"))
        controller.applyPreview()
        check(service.writes == 1)
    }

    @Test
    fun busyPreventsRepeatedActionsAndCancellationClearsProgress() {
        connect()
        val pending = CompletableDeferred<Unit>()
        service.pending = pending
        controller.preview()
        check(controller.screen.busy)
        controller.preview()
        controller.applyPreview()
        controller.requestHealth()
        controller.authorize()
        controller.forget()
        check(service.reads == 1 && service.writes == 0 && service.permissionCalls == 0)
        check(controller.screen.connected)
        pending.cancel()
        check(!controller.screen.busy && controller.screen.preview == null)
        controller.forget(force = true)
        check(!controller.screen.connected)
    }

    private fun connect() = controller.acceptAuthorization(listOf(SLEEP_SCOPE), "fixture-token")
}

private class TestServices : BridgeServices {
    var session = testSleep()
    var decision = Decision(Action.INSERT)
    var authorizations = 0
    var permissionCalls = 0
    var reads = 0
    var inspections = 0
    var writes = 0
    var available = true
    var failure: Exception? = null
    var writeFailure: Exception? = null
    var pending: CompletableDeferred<Unit>? = null

    override fun authorize() {
        authorizations++
        failure?.let { throw it }
    }

    override fun healthAvailable(): Boolean = available

    override fun requestPermissions() {
        permissionCalls++
        failure?.let { throw it }
    }

    override suspend fun latest(access: String): SleepSession {
        check(access == "fixture-token")
        reads++
        pending?.await()
        failure?.let { throw it }
        return session
    }

    override suspend fun inspect(session: SleepSession): Decision {
        check(session == this.session)
        inspections++
        return decision
    }

    override suspend fun apply(
        session: SleepSession,
        decision: Decision,
    ): String {
        check(session == this.session && decision == this.decision)
        writes++
        writeFailure?.let { throw it }
        return "Imported and verified"
    }
}
