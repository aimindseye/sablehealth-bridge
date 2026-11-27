package io.github.aimindseye.sablehealthbridge

import android.os.Looper
import androidx.activity.ComponentActivity
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCoordinatorTest {
    @Test
    fun consentSchedulesAndStopsAndRestoresOnlyCurrentGeneration() =
        withCoordinator { fixture ->
            val coordinator = fixture.coordinator
            coordinator.syncNow(restore = true)
            check(fixture.scheduler.calls.isEmpty())
            coordinator.chooseInterval(15)
            coordinator.chooseInterval(3)
            check(coordinator.state.minutes == 15)
            coordinator.enable()
            coordinator.enable()
            coordinator.chooseInterval(60)
            check(fixture.requests == 1 && coordinator.state.busy && coordinator.state.minutes == 15)
            fixture.accept()
            awaitSync { fixture.scheduler.calls.size == 2 && coordinator.state.enabled }
            check(fixture.scheduler.calls == listOf("replace", "now"))
            coordinator.chooseInterval(60)
            coordinator.enable()
            check(coordinator.state.minutes == 15 && fixture.requests == 1)
            coordinator.syncNow()
            awaitSync { fixture.scheduler.calls.size == 3 }
            coordinator.syncNow(restore = true)
            awaitSync { fixture.scheduler.calls.size == 5 }
            check(fixture.scheduler.calls.takeLast(2) == listOf("keep", "now"))
            coordinator.disable()
            awaitSync { !coordinator.state.enabled && !coordinator.state.busy }
            check(fixture.scheduler.calls.last() == "cancel")
            coordinator.disable()
            fixture.accept()
            check(!coordinator.state.enabled)
        }

    @Test
    fun rejectedAndChangedAccountAndSchedulingFailureNeverEnableWrites() =
        withCoordinator { fixture ->
            val coordinator = fixture.coordinator
            coordinator.enable()
            fixture.reject(SyncStatus.PERMISSION_REQUIRED)
            check(!coordinator.state.busy && coordinator.state.notice == SyncStatus.PERMISSION_REQUIRED)
            coordinator.enable()
            fixture.bindFailure = IllegalStateException()
            fixture.accept()
            awaitSync { coordinator.state.notice == SyncStatus.AUTH_REQUIRED && !coordinator.state.busy }
            check(!coordinator.state.enabled)
            fixture.bindFailure = SyncHalt(SyncStatus.ACCOUNT_CHANGED)
            coordinator.enable()
            fixture.accept()
            awaitSync { coordinator.state.notice == SyncStatus.ACCOUNT_CHANGED && !coordinator.state.busy }
            fixture.bindFailure = null
            fixture.scheduler.failSchedule = true
            coordinator.enable()
            fixture.accept()
            awaitSync { coordinator.state.notice == SyncStatus.FAILED && !coordinator.state.enabled }
            check(coordinator.state.report.status == SyncStatus.PAUSED)
            check(fixture.scheduler.calls == listOf("replace"))
        }

    @Test
    fun cancellationFailureLeavesConsentDisabled() =
        withCoordinator { fixture ->
            val coordinator = fixture.coordinator
            coordinator.enable()
            fixture.accept()
            awaitSync { coordinator.state.enabled && fixture.scheduler.calls.size == 2 }
            fixture.scheduler.failCancel = true
            coordinator.disable()
            awaitSync { coordinator.state.notice == SyncStatus.FAILED && !coordinator.state.busy }
            check(!coordinator.state.enabled)
            check(coordinator.state.report.status == SyncStatus.OFF)
        }

    private fun withCoordinator(block: (CoordinatorFixture) -> Unit) {
        val lifecycle = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val fixture = CoordinatorFixture(lifecycle.get())
        fixture.coordinator.observe()
        try {
            block(fixture)
        } finally {
            fixture.coordinator.unobserve()
            lifecycle.pause().stop().destroy()
        }
    }
}

private class CoordinatorFixture(
    activity: ComponentActivity,
) {
    val scheduler = FixtureScheduler()
    var requests = 0
    var bindFailure: Exception? = null
    private lateinit var accepted: (ProbeAuthorization) -> Unit
    private lateinit var failed: (SyncStatus) -> Unit
    val coordinator =
        SyncCoordinator(
            activity,
            scheduler,
            SyncAuthorizationFactory { _, success, failure ->
                accepted = success
                failed = failure
                SyncConsentRequest { requests++ }
            },
            bindAccount = { _, _ ->
                bindFailure?.let { throw it }
                syncConsent().binding
            },
        )

    fun accept() = accepted(ProbeAuthorization(false, setOf(SLEEP_SCOPE), "fixture"))

    fun reject(status: SyncStatus) = failed(status)
}

private class FixtureScheduler : SyncScheduler {
    val calls = mutableListOf<String>()
    var failSchedule = false
    var failCancel = false

    override suspend fun schedule(
        consent: SyncConsent,
        replace: Boolean,
    ) {
        calls.add(if (replace) "replace" else "keep")
        check(!failSchedule)
    }

    override suspend fun now(consent: SyncConsent) {
        calls.add("now")
    }

    override suspend fun cancel() {
        calls.add("cancel")
        check(!failCancel)
    }
}

private fun awaitSync(condition: () -> Boolean) {
    repeat(1000) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    error("Sync state not reached")
}
