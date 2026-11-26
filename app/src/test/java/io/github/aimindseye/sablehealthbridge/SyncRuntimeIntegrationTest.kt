package io.github.aimindseye.sablehealthbridge

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

private const val SYNC_WAIT_SECONDS = 10L

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncRuntimeIntegrationTest {
    @Test
    fun workerWithNoConsentNeverCallsAndroidOrGoogleAccess() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val worker = TestListenableWorkerBuilder<AutomaticSyncWorker>(context, inputData = Data.EMPTY).build()
            check(worker.doWork() == ListenableWorker.Result.success())
        }

    @Test
    fun periodicWorkIsUniqueAndCanBeCancelled() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
            val manager = WorkManager.getInstance(context)
            try {
                val scheduler = AndroidSyncScheduler(context)
                val consent = syncConsent()
                scheduler.schedule(consent, replace = true)
                scheduler.schedule(consent, replace = false)
                val first = manager.getWorkInfosForUniqueWork(SYNC_PERIODIC_WORK)
                    .get(SYNC_WAIT_SECONDS, TimeUnit.SECONDS)
                check(first.count { !it.state.isFinished } == 1)
                check(first.single { !it.state.isFinished }.state == WorkInfo.State.ENQUEUED)
                scheduler.now(consent)
                scheduler.cancel()
                val after = manager.getWorkInfosForUniqueWork(SYNC_PERIODIC_WORK)
                    .get(SYNC_WAIT_SECONDS, TimeUnit.SECONDS)
                check(after.none { !it.state.isFinished })
            } finally {
                manager.cancelAllWork().result.get(SYNC_WAIT_SECONDS, TimeUnit.SECONDS)
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
        }
}
