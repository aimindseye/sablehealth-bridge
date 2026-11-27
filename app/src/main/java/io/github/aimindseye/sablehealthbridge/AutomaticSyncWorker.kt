package io.github.aimindseye.sablehealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.tasks.Tasks
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SyncJournalState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal const val SYNC_GENERATION = "sync_generation"
private const val SYNC_AUTH_TIMEOUT_SECONDS = 30L
private val syncRunLock = Mutex()

class AutomaticSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            syncRunLock.withLock {
                val prefs = syncPreferences(applicationContext)
                val settings = SyncSettings(prefs)
                val generation = inputData.getString(SYNC_GENERATION)
                val report =
                    executeSyncWork(
                        settings,
                        generation,
                        ports = { consent ->
                            AndroidAutomaticSyncPorts(applicationContext, settings, SyncJournal(prefs), consent)
                        },
                    )
                if (report.status in setOf(SyncStatus.FAILED, SyncStatus.WRITE_UNCERTAIN)) {
                    Result.failure()
                } else {
                    Result.success()
                }
            }
        }
}

internal suspend fun executeSyncWork(
    settings: SyncSettings,
    generation: String?,
    ports: (SyncConsent) -> AutomaticSyncPorts,
    now: Instant = Instant.now(),
): SyncReport {
    val consent = settings.current()
    if (consent == null || consent.generation != generation) return SyncReport(SyncStatus.OFF)
    settings.record(consent, settings.report().copy(status = SyncStatus.RUNNING))
    val result = runAutomaticSync(consent, ports(consent), now)
    val report = result.copy(finishedAt = Instant.now().toEpochMilli())
    settings.record(consent, report)
    return report
}

private class AndroidAutomaticSyncPorts(
    private val context: Context,
    private val settings: SyncSettings,
    private val ledger: SyncJournal,
    private val consent: SyncConsent,
) : AutomaticSyncPorts {
    private val health = ConnectSleep(context)

    override suspend fun guard(consent: SyncConsent) {
        currentCoroutineContext().ensureActive()
        if (!settings.isCurrent(consent)) throw SyncHalt(SyncStatus.PAUSED)
        if (!backgroundReadAvailable(context)) throw SyncHalt(SyncStatus.UNAVAILABLE)
        val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        if (!granted.containsAll(PROBE_PERMISSIONS)) throw SyncHalt(SyncStatus.PERMISSION_REQUIRED)
    }

    override suspend fun authorize(): ProbeAuthorization {
        val result =
            Tasks.await(
                Identity.getAuthorizationClient(context).authorize(probeAuthorizationRequest()),
                SYNC_AUTH_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
        return ProbeAuthorization(result.hasResolution(), result.grantedScopes.orEmpty(), result.accessToken)
    }

    override suspend fun identity(
        access: String,
        salt: String,
    ): String = retrySleepRead { readAccountBinding(access, salt) }

    override suspend fun batch(
        access: String,
        initial: Boolean,
    ): SleepBatch =
        retrySleepRead {
            readSyncBatch(Instant.now(), ZoneId.systemDefault(), initial) { url -> fetchPage(url, access) }
        }

    override suspend fun inspect(session: SleepSession): Decision = health.inspect(session)

    override fun journal(session: SleepSession): SyncJournalState = ledger.state(session)

    override fun confirm(session: SleepSession) = ledger.confirm(session)

    override suspend fun insert(
        session: SleepSession,
        decision: Decision,
    ): Boolean =
        try {
            health.insertAutomatically(session, decision) {
                guard(consent)
                ledger.reserve(session)
                guard(consent)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stop: SyncHalt) {
            throw stop
        } catch (denied: SecurityException) {
            throw denied
        } catch (_: Exception) {
            throw SyncHalt(SyncStatus.WRITE_UNCERTAIN)
        }
}
