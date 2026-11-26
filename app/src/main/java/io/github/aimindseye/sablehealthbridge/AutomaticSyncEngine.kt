package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.AutomaticSleepAction
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SyncJournalState
import io.github.aimindseye.sablehealthbridge.core.automaticSleepAction
import kotlinx.coroutines.CancellationException
import java.time.Instant

internal data class SyncConsent(
    val generation: String,
    val salt: String,
    val binding: String,
    val minutes: Int,
    val initial: Boolean,
)

internal enum class SyncStatus {
    OFF, READY, RUNNING, COMPLETE, PAUSED, AUTH_REQUIRED, ACCOUNT_CHANGED,
    PERMISSION_REQUIRED, UNAVAILABLE, FAILED, WRITE_UNCERTAIN,
}

internal data class SyncReport(
    val status: SyncStatus = SyncStatus.OFF,
    val imported: Int = 0,
    val verified: Int = 0,
    val held: Int = 0,
    val finishedAt: Long = 0,
)

internal class SyncHalt(val status: SyncStatus) : Exception()

internal interface AutomaticSyncPorts {
    suspend fun guard(consent: SyncConsent)

    suspend fun authorize(): ProbeAuthorization

    suspend fun identity(access: String, salt: String): String

    suspend fun batch(access: String, initial: Boolean): SleepBatch

    suspend fun inspect(session: SleepSession): Decision

    fun journal(session: SleepSession): SyncJournalState

    fun confirm(session: SleepSession)

    suspend fun insert(session: SleepSession, decision: Decision): Boolean
}

internal suspend fun runAutomaticSync(
    consent: SyncConsent,
    ports: AutomaticSyncPorts,
    now: Instant,
): SyncReport {
    var report = SyncReport(SyncStatus.COMPLETE)
    try {
        ports.guard(consent)
        val access = silentAccess(ports.authorize())
        if (ports.identity(access, consent.salt) != consent.binding) throw SyncHalt(SyncStatus.ACCOUNT_CHANGED)
        val batch = ports.batch(access, consent.initial)
        report = report.copy(held = batch.heldCount)
        for (session in batch.sessions) {
            ports.guard(consent)
            report = reconcileAutomatic(session, ports, now, report)
        }
        ports.guard(consent)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (stop: SyncHalt) {
        report = report.copy(status = stop.status)
    } catch (_: ProbeStop) {
        report = report.copy(status = SyncStatus.AUTH_REQUIRED)
    } catch (_: SecurityException) {
        report = report.copy(status = SyncStatus.PERMISSION_REQUIRED)
    } catch (_: Exception) {
        report = report.copy(status = SyncStatus.FAILED)
    }
    return report
}

private suspend fun reconcileAutomatic(
    session: SleepSession,
    ports: AutomaticSyncPorts,
    now: Instant,
    report: SyncReport,
): SyncReport {
    val decision = ports.inspect(session)
    val action = automaticSleepAction(session, now, decision, ports.journal(session))
    return when (action) {
        AutomaticSleepAction.INSERT_NEW -> {
            if (!ports.insert(session, decision)) throw SyncHalt(SyncStatus.WRITE_UNCERTAIN)
            ports.confirm(session)
            report.copy(imported = report.imported + 1)
        }
        AutomaticSleepAction.SKIP_VERIFIED -> {
            ports.confirm(session)
            report.copy(verified = report.verified + 1)
        }
        else -> report.copy(held = report.held + 1)
    }
}
