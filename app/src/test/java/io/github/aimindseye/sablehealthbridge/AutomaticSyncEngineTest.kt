package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SLEEP_SCOPE
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SourceSleepInfo
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import io.github.aimindseye.sablehealthbridge.core.SyncJournalState
import io.github.aimindseye.sablehealthbridge.core.sessionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant

internal val syncFixtureStart: Instant = Instant.parse("2026-11-26T05:00:00Z")
internal val syncFixtureEnd: Instant = syncFixtureStart.plusSeconds(3600)

internal fun syncFixture(): SleepSession {
    val stages = listOf(Stage(syncFixtureStart, syncFixtureEnd, StageType.LIGHT))
    val source =
        SourceSleepInfo(
            "STAGES",
            "SUCCEEDED",
            true,
            true,
            false,
            false,
            syncFixtureStart,
            syncFixtureEnd,
            null,
            stages,
            listOf("LIGHT"),
        )
    return SleepSession(
        sessionKey("fixture/automatic", syncFixtureStart, syncFixtureEnd),
        syncFixtureStart,
        syncFixtureEnd,
        null,
        null,
        stages,
        sourceInfo = source,
    )
}

internal fun syncConsent(initial: Boolean = true): SyncConsent =
    SyncConsent(
        "fixture-generation",
        "feaf7402-5d0c-43ab-a472-06ae3d3b820d",
        "a".repeat(64),
        DEFAULT_SYNC_MINUTES,
        initial,
    )

class AutomaticSyncEngineTest {
    @Test
    fun firstRunImportsOnceThenRecognizesExactMatchAndDeletedRecord() =
        runBlocking {
            val ports = FixtureSyncPorts()
            val first = runAutomaticSync(syncConsent(), ports, syncFixtureEnd)
            check(first.status == SyncStatus.COMPLETE && first.imported == 1 && first.held == 2)
            check(ports.calls == 1 && ports.confirmed == SyncJournalState.PREVIOUSLY_CONFIRMED)
            ports.action = Action.ALREADY_IMPORTED
            val repeated = runAutomaticSync(syncConsent(false), ports, syncFixtureEnd)
            check(repeated.verified == 1 && ports.calls == 1 && !ports.initial)
            ports.action = Action.INSERT
            val missing = runAutomaticSync(syncConsent(false), ports, syncFixtureEnd)
            check(missing.held == 3 && ports.calls == 1)
        }

    @Test
    fun authorizationAndAccountAndPermissionFailuresNeverReachRecords() =
        runBlocking {
            val ports = FixtureSyncPorts()
            ports.grant = ProbeAuthorization(true, emptyList(), null)
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.AUTH_REQUIRED)
            ports.grant = ProbeAuthorization(false, setOf("other"), "fixture")
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.AUTH_REQUIRED)
            ports.grant = ProbeAuthorization(false, setOf(SLEEP_SCOPE), "fixture")
            ports.binding = "b".repeat(64)
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.ACCOUNT_CHANGED)
            ports.binding = syncConsent().binding
            ports.stop = SyncHalt(SyncStatus.PERMISSION_REQUIRED)
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.PERMISSION_REQUIRED)
            ports.stop = SecurityException()
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.PERMISSION_REQUIRED)
            ports.stop = Hold("API HTTP 401")
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.AUTH_REQUIRED)
            ports.stop = Hold("API HTTP 403")
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.AUTH_REQUIRED)
            ports.stop = Hold("Other API error")
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.FAILED)
            ports.stop = IllegalStateException()
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).status == SyncStatus.FAILED)
            check(ports.reads == 0 && ports.calls == 0)
        }

    @Test
    fun uncertainWriteIsNeverRetriedAndConflictAndUpdateAreHeld() =
        runBlocking {
            val ports = FixtureSyncPorts()
            ports.verify = false
            val result = runAutomaticSync(syncConsent(), ports, syncFixtureEnd)
            check(result.status == SyncStatus.WRITE_UNCERTAIN && ports.calls == 1)
            check(ports.confirmed == SyncJournalState.UNRESOLVED_ATTEMPT)
            ports.verify = true
            val next = runAutomaticSync(syncConsent(false), ports, syncFixtureEnd)
            check(next.held == 3 && ports.calls == 1)
            ports.confirmed = SyncJournalState.UNSEEN
            for (action in listOf(Action.UPDATE, Action.CONFLICT, Action.REMOVE_OWN_DUPLICATE)) {
                ports.action = action
                check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).held == 3)
            }
            ports.action = Action.EXTERNAL_MATCH
            check(runAutomaticSync(syncConsent(), ports, syncFixtureEnd).verified == 1)
            check(ports.calls == 1)
        }

    @Test
    fun cancellationPropagatesWithoutWrite() =
        runBlocking {
            val ports = FixtureSyncPorts()
            ports.stop = CancellationException()
            val failure = runCatching { runAutomaticSync(syncConsent(), ports, syncFixtureEnd) }.exceptionOrNull()
            check(failure is CancellationException && ports.calls == 0)
        }
}

internal class FixtureSyncPorts : AutomaticSyncPorts {
    var action = Action.INSERT
    var confirmed = SyncJournalState.UNSEEN
    var grant = ProbeAuthorization(false, setOf(SLEEP_SCOPE), "fixture")
    var binding = syncConsent().binding
    var stop: Exception? = null
    var verify = true
    var calls = 0
    var reads = 0
    var initial = false

    override suspend fun guard(consent: SyncConsent) {
        stop?.let { throw it }
    }

    override suspend fun authorize(): ProbeAuthorization = grant

    override suspend fun identity(
        access: String,
        salt: String,
    ): String = binding

    override suspend fun batch(
        access: String,
        initial: Boolean,
    ): SleepBatch {
        reads++
        this.initial = initial
        return SleepBatch(listOf(syncFixture()), 2)
    }

    override suspend fun inspect(session: SleepSession): Decision = Decision(action)

    override fun journal(session: SleepSession): SyncJournalState = confirmed

    override fun confirm(session: SleepSession) {
        confirmed = SyncJournalState.PREVIOUSLY_CONFIRMED
    }

    override suspend fun insert(
        session: SleepSession,
        decision: Decision,
    ): Boolean {
        calls++
        confirmed = SyncJournalState.UNRESOLVED_ATTEMPT
        return verify
    }
}
