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
class ConnectSleepTest {
    private val session = testSleep()
    private val store = TestStore()
    private val bridge = ConnectSleep(store)

    @Test
    fun importAndRepeat() =
        runBlocking {
            val decision = bridge.inspect(session)
            check(decision.action == Action.INSERT && store.inserts == 0)
            check(bridge.apply(session, decision) == "Imported and verified")
            check(store.inserts == 1 && store.deletes.isEmpty())
            check(bridge.inspect(session).action == Action.ALREADY_IMPORTED)
            check(runCatching { bridge.apply(session, decision) }.exceptionOrNull() is Hold)
            check(store.inserts == 1)
        }

    @Test
    fun permissionsAndAvailabilityFailBeforeReads() =
        runBlocking {
            store.ready = false
            check(runCatching { bridge.inspect(session) }.exceptionOrNull() is Hold)
            store.ready = true
            store.permissions = emptySet()
            check(runCatching { bridge.apply(session, Decision(Action.INSERT)) }.exceptionOrNull() is Hold)
            check(store.reads == 0 && store.inserts == 0)
        }

    @Test
    fun foreignMatchArrivesAfterPreview() =
        runBlocking {
            val decision = bridge.inspect(session)
            store.records = listOf(external())
            check(runCatching { bridge.apply(session, decision) }.exceptionOrNull() is Hold)
            check(bridge.inspect(session).action == Action.EXTERNAL_MATCH)
            check(store.inserts == 0 && store.deletes.isEmpty())
        }

    @Test
    fun duplicateRemovalOnlyTargetsOwnedRecord() =
        runBlocking {
            bridge.apply(session, bridge.inspect(session))
            store.records = store.records + external()
            val decision = bridge.inspect(session)
            check(decision.action == Action.REMOVE_OWN_DUPLICATE)
            check(bridge.apply(session, decision) == "External sleep verified; Bridge copy absent")
            check(store.deletes == listOf("own") && store.records == listOf(external()))
        }

    @Test
    fun correctionUsesNextVersionAndStageMapping() =
        runBlocking {
            bridge.apply(session, bridge.inspect(session))
            val correctedStages = session.stages.map { it.copy(type = StageType.DEEP) }
            val corrected = session.copy(stages = correctedStages)
            val decision = bridge.inspect(corrected)
            check(decision.action == Action.UPDATE && decision.version == 1L)
            check(bridge.apply(corrected, decision) == "Imported and verified")
            check(store.records.single().version == 1L)
            check(store.records.single().stages == correctedStages)
        }

    @Test
    fun repeatedAndIncompletePaginationNeverWrite() =
        runBlocking {
            store.repeatCursor = true
            check(runCatching { bridge.inspect(session) }.exceptionOrNull() is Hold)
            store.repeatCursor = false
            store.unbounded = true
            check(runCatching { bridge.apply(session, Decision(Action.INSERT)) }.exceptionOrNull() is Hold)
            check(store.inserts == 0 && store.deletes.isEmpty())
        }

    @Test
    fun uncertainCommitDoesNotRetry() =
        runBlocking {
            store.persist = false
            val result = bridge.apply(session, bridge.inspect(session))
            check(result.startsWith("Change submitted"))
            check(store.inserts == 1)
            store.failure = SecurityException()
            val failure = runCatching { bridge.apply(session, Decision(Action.INSERT)) }.exceptionOrNull()
            check(failure is SecurityException)
            check(store.inserts == 2)
        }

    private fun external(): ExistingSleep =
        ExistingSleep(
            "foreign",
            "fixture.writer",
            null,
            0,
            session.start,
            session.end,
            session.stages,
        )
}

private class TestStore : SleepStore {
    var ready = true
    var permissions = SLEEP_PERMISSIONS
    var records = emptyList<ExistingSleep>()
    var reads = 0
    var inserts = 0
    var repeatCursor = false
    var unbounded = false
    var persist = true
    var failure: Exception? = null
    val deletes = mutableListOf<String>()

    override fun available(): Boolean = ready

    override suspend fun granted(): Set<String> = permissions

    override suspend fun read(
        from: Instant,
        until: Instant,
        cursor: String?,
    ): SleepPage {
        reads++
        check(from < until)
        val next =
            when {
                repeatCursor -> "repeat"
                unbounded -> reads.toString()
                else -> null
            }
        return SleepPage(records, next)
    }

    override suspend fun insert(record: SleepSessionRecord) {
        inserts++
        failure?.let { throw it }
        if (persist) {
            val own = existingRecord(record).copy(id = "own", origin = BRIDGE_PACKAGE)
            records = records.filterNot { it.id == "own" } + own
        }
    }

    override suspend fun delete(id: String) {
        deletes.add(id)
        records = records.filterNot { it.id == id }
    }
}
