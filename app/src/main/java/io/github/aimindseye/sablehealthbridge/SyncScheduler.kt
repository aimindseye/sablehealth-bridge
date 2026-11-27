package io.github.aimindseye.sablehealthbridge

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal const val SYNC_PERIODIC_WORK = "sbr-auto-sync-periodic"
internal const val SYNC_NOW_WORK = "sbr-auto-sync-now"
private const val SCHEDULER_TIMEOUT = 30L

internal interface SyncScheduler {
    suspend fun schedule(
        consent: SyncConsent,
        replace: Boolean,
    )

    suspend fun now(consent: SyncConsent)

    suspend fun cancel()
}

private val connectedNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

internal class AndroidSyncScheduler(
    private val context: Context,
) : SyncScheduler {
    private val manager get() = WorkManager.getInstance(context.applicationContext)

    override suspend fun schedule(
        consent: SyncConsent,
        replace: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val request =
                PeriodicWorkRequestBuilder<AutomaticSyncWorker>(consent.minutes.toLong(), TimeUnit.MINUTES)
                    .setInitialDelay(consent.minutes.toLong(), TimeUnit.MINUTES)
                    .setConstraints(connectedNetwork)
                    .setInputData(workDataOf(SYNC_GENERATION to consent.generation))
                    .build()
            val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
            val operation = manager.enqueueUniquePeriodicWork(SYNC_PERIODIC_WORK, policy, request)
            operation.result.get(SCHEDULER_TIMEOUT, TimeUnit.SECONDS)
        }
    }

    override suspend fun now(consent: SyncConsent) {
        withContext(Dispatchers.IO) {
            val request =
                OneTimeWorkRequestBuilder<AutomaticSyncWorker>()
                    .setConstraints(connectedNetwork)
                    .setInputData(workDataOf(SYNC_GENERATION to consent.generation))
                    .build()
            val operation = manager.enqueueUniqueWork(SYNC_NOW_WORK, ExistingWorkPolicy.KEEP, request)
            operation.result.get(SCHEDULER_TIMEOUT, TimeUnit.SECONDS)
        }
    }

    override suspend fun cancel() {
        withContext(Dispatchers.IO) {
            manager.cancelUniqueWork(SYNC_PERIODIC_WORK).result.get(SCHEDULER_TIMEOUT, TimeUnit.SECONDS)
            manager.cancelUniqueWork(SYNC_NOW_WORK).result.get(SCHEDULER_TIMEOUT, TimeUnit.SECONDS)
        }
    }
}
