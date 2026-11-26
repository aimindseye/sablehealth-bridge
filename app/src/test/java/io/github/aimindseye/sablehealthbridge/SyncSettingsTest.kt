package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SyncJournalState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSettingsTest {
    private val prefs = syncPreferences(RuntimeEnvironment.getApplication())
    private val settings = SyncSettings(prefs)
    private val fixture = syncConsent()

    @Test
    fun defaultOffExplicitEnablePauseAndSameAccountReenable() {
        check(settings.current() == null && settings.report().status == SyncStatus.OFF)
        val salt = settings.salt()
        val first = settings.enable(salt, fixture.binding, 30)
        check(first.initial && settings.isCurrent(first) && settings.salt() == salt)
        val complete = SyncReport(SyncStatus.COMPLETE, 1, 2, 3, 123)
        settings.record(first, complete)
        check(settings.report() == complete && settings.current()?.initial == false)
        check(settings.history() == listOf(complete))
        settings.record(first, complete.copy(status = SyncStatus.AUTH_REQUIRED))
        check(settings.current() == null && settings.report().status == SyncStatus.AUTH_REQUIRED)
        check(runCatching { settings.enable(salt, "b".repeat(64), 30) }.exceptionOrNull() is SyncHalt)
        val second = settings.enable(salt, fixture.binding, 60)
        check(second.generation != first.generation && second.minutes == 60 && !second.initial)
        settings.record(first, complete)
        check(settings.report().status == SyncStatus.READY)
        settings.stop()
        check(settings.current() == null && settings.report().status == SyncStatus.OFF)
    }

    @Test
    fun invalidConsentAndPersistentJournalFailClosed() {
        check(runCatching { settings.enable("bad", fixture.binding, 30) }.isFailure)
        check(runCatching { settings.enable(fixture.salt, "bad", 30) }.isFailure)
        check(runCatching { settings.enable(fixture.salt, fixture.binding, 3) }.isFailure)
        val ledger = SyncJournal(prefs)
        val session = syncFixture()
        check(ledger.state(session) == SyncJournalState.UNSEEN)
        ledger.reserve(session)
        check(SyncJournal(prefs).state(session) == SyncJournalState.UNRESOLVED_ATTEMPT)
        check(runCatching { ledger.reserve(session) }.isFailure)
        ledger.confirm(session)
        check(SyncJournal(prefs).state(session) == SyncJournalState.PREVIOUSLY_CONFIRMED)
        settings.stop()
        check(ledger.state(session) == SyncJournalState.PREVIOUSLY_CONFIRMED)
        check(runCatching { ledger.state(session.copy(key = "invalid")) }.isFailure)
    }

    @Test
    fun boundedHistoryRejectsMalformedRowsAndKeepsNewestFirst() {
        val rows = (1L..8L).map { SyncReport(SyncStatus.COMPLETE, 1, 2, 3, it) }
        val encoded = rows.fold("") { raw, report -> appendSyncHistory(parseSyncHistory(raw), report) }
        check(parseSyncHistory(encoded) == rows.takeLast(5).reversed())
        check(parseSyncHistory("bad\nCOMPLETE|0|1|2|3\nCOMPLETE|1|-1|2|3").isEmpty())
        check(parseSyncHistory("COMPLETE|1|0|2|3").single().held == 3)
    }

    @Test
    fun workerGenerationAndExecutionResultArePersisted() =
        runBlocking {
            val noWork = executeSyncWork(settings, null, { error("must not run") })
            check(noWork.status == SyncStatus.OFF)
            val consent = settings.enable(fixture.salt, fixture.binding, 30)
            val stale = executeSyncWork(settings, "stale", { error("must not run") })
            check(stale.status == SyncStatus.OFF)
            val ports = FixtureSyncPorts()
            val report = executeSyncWork(settings, consent.generation, { ports }, syncFixtureEnd)
            check(report.status == SyncStatus.COMPLETE && settings.report() == report)
            check(settings.current()?.initial == false && report.imported == 1)
        }
}
