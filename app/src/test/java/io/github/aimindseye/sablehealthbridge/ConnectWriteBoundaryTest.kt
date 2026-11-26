package io.github.aimindseye.sablehealthbridge

import androidx.health.connect.client.records.SleepSessionRecord
import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.BRIDGE_PACKAGE
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.ExistingSleep
import io.github.aimindseye.sablehealthbridge.core.StageType
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectWriteBoundaryTest {
    private val session = testSleep()
    private val store = BoundaryStore()
    private val bridge = ConnectSleep(store)

    @Test
    fun accessRevokedAfterFreshReadPreventsWrite() =
        runBlocking {
            store.revokeAfterRead = true
            val failure = runCatching { bridge.apply(session, Decision(Action.INSERT)) }.exceptionOrNull()
            check(failure is Hold && failure.label == "Grant sleep read and write access")
            check(store.inserts == 0 && store.deletes == 0 && store.reads == 1)
        }

    @Test
    fun validSecondPageIsConsultedBeforeImport() =
        runBlocking {
            store.secondPage = true
            val decision = bridge.inspect(session)
            check(decision.action == Action.EXTERNAL_MATCH && store.reads == 2)
            val failure = runCatching { bridge.apply(session, Decision(Action.INSERT)) }.exceptionOrNull()
            check(failure is Hold && store.inserts == 0 && store.deletes == 0)
        }

    @Test
    fun unspecifiedSleepingInProviderIsPreservedWithoutInventingDetail() {
        val record = toRecord(session, 0)
        val stageType = SleepSessionRecord.STAGE_TYPE_SLEEPING
        val unsupported =
            SleepSessionRecord(
                startTime = record.startTime,
                startZoneOffset = record.startZoneOffset,
                endTime = record.endTime,
                endZoneOffset = record.endZoneOffset,
                metadata = record.metadata,
                stages = listOf(SleepSessionRecord.Stage(record.startTime, record.endTime, stageType)),
            )
        val mapped = existingRecord(unsupported)
        val stage = requireNotNull(mapped.stages).single()
        check(stage.type == StageType.ASLEEP)
    }

    @Test
    fun readOnlyDecisionCannotTriggerImportEvenWhenFreshDecisionMatches() =
        runBlocking {
            store.secondPage = true
            val decision = bridge.inspect(session)
            check(decision.action == Action.EXTERNAL_MATCH)
            check(runCatching { bridge.apply(session, decision) }.exceptionOrNull() is Hold)
            check(store.inserts == 0 && store.deletes == 0)
        }
}

private class BoundaryStore : SleepStore {
    var revokeAfterRead = false
    var secondPage = false
    var reads = 0
    var inserts = 0
    var deletes = 0

    override fun available(): Boolean = true

    override suspend fun granted(): Set<String> = if (revokeAfterRead && reads > 0) emptySet() else SLEEP_PERMISSIONS

    override suspend fun read(
        from: Instant,
        until: Instant,
        cursor: String?,
    ): SleepPage {
        check(from < until)
        reads++
        return if (secondPage && cursor == null) {
            SleepPage(emptyList(), "second")
        } else if (secondPage) {
            check(cursor == "second")
            val session = testSleep()
            val external =
                ExistingSleep(
                    "external",
                    "fixture.writer",
                    null,
                    0,
                    session.start,
                    session.end,
                    session.stages,
                )
            check(external.origin != BRIDGE_PACKAGE)
            SleepPage(listOf(external), null)
        } else {
            SleepPage(emptyList(), null)
        }
    }

    override suspend fun insert(record: SleepSessionRecord) {
        inserts++
        error("Unexpected fixture write")
    }

    override suspend fun delete(id: String) {
        deletes++
        error("Unexpected fixture delete")
    }
}
