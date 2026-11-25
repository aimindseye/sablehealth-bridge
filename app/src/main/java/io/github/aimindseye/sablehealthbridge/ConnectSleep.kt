package io.github.aimindseye.sablehealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.ExistingSleep
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import io.github.aimindseye.sablehealthbridge.core.confirmedDecision
import io.github.aimindseye.sablehealthbridge.core.decide
import java.time.Duration
import java.time.Instant

internal val SLEEP_PERMISSIONS =
    setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
    )

private const val HISTORY_DAYS = 7L
private const val MAX_HEALTH_PAGES = 30

internal data class SleepPage(
    val records: List<ExistingSleep>,
    val next: String?,
)

internal interface SleepStore {
    fun available(): Boolean

    suspend fun granted(): Set<String>

    suspend fun read(
        from: Instant,
        until: Instant,
        cursor: String?,
    ): SleepPage

    suspend fun insert(record: SleepSessionRecord)

    suspend fun delete(id: String)
}

internal class ConnectSleep(
    private val store: SleepStore,
) {
    constructor(context: Context) : this(AndroidSleepStore(context))

    fun available(): Boolean = store.available()

    private suspend fun requireAccess() {
        if (!available()) throw Hold("Health Connect unavailable or needs update")
        if (!store.granted().containsAll(SLEEP_PERMISSIONS)) {
            throw Hold("Grant sleep read and write access")
        }
    }

    suspend fun inspect(session: SleepSession): Decision {
        requireAccess()
        return decide(session, existing(store, session))
    }

    suspend fun apply(
        session: SleepSession,
        preview: Decision,
    ): String {
        requireAccess()
        val fresh = decide(session, existing(store, session))
        if (!confirmedDecision(preview, fresh)) throw Hold("Health Connect changed; preview again")
        requireAccess()
        when (fresh.action) {
            Action.INSERT, Action.UPDATE -> store.insert(toRecord(session, fresh.version))
            Action.REMOVE_OWN_DUPLICATE -> store.delete(requireNotNull(fresh.ownId))
            else -> throw Hold("No eligible change")
        }
        // No blind retries: an interrupted IPC may already have committed.
        val verified = inspect(session).action
        return when (verified) {
            Action.ALREADY_IMPORTED -> "Imported and verified"
            Action.EXTERNAL_MATCH -> "External sleep verified; Bridge copy absent"
            else -> "Change submitted; review Health Connect before retrying"
        }
    }
}

private const val HEALTH_PAGE_SIZE = 100

private class AndroidSleepStore(
    private val context: Context,
) : SleepStore {
    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    override fun available(): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    override suspend fun granted(): Set<String> = client().permissionController.getGrantedPermissions()

    override suspend fun read(
        from: Instant,
        until: Instant,
        cursor: String?,
    ): SleepPage {
        val request =
            ReadRecordsRequest(
                SleepSessionRecord::class,
                TimeRangeFilter.between(from, until),
                pageSize = HEALTH_PAGE_SIZE,
                pageToken = cursor,
            )
        val response = client().readRecords(request)
        return SleepPage(response.records.map(::existingRecord), response.pageToken)
    }

    override suspend fun insert(record: SleepSessionRecord) {
        client().insertRecords(listOf(record))
    }

    override suspend fun delete(id: String) {
        client().deleteRecords(SleepSessionRecord::class, listOf(id), emptyList())
    }
}

private suspend fun existing(
    store: SleepStore,
    session: SleepSession,
): List<ExistingSleep> {
    val from = session.start.minus(Duration.ofDays(HISTORY_DAYS))
    val until = session.end.plus(Duration.ofDays(1)).coerceAtMost(Instant.now().plusSeconds(1))
    val found = mutableListOf<ExistingSleep>()
    val seen = mutableSetOf<String>()
    var token: String? = null
    repeat(MAX_HEALTH_PAGES) {
        val page = store.read(from, until, token)
        found.addAll(page.records)
        val next = page.next ?: return found
        if (!seen.add(next)) throw Hold("Health Connect pagination repeated")
        token = next
    }
    throw Hold("Health Connect read incomplete; no write")
}

internal fun existingRecord(record: SleepSessionRecord): ExistingSleep {
    val stages =
        record.stages
            .mapNotNull { item ->
                fromHealthStage(item.stage)?.let { Stage(item.startTime, item.endTime, it) }
            }.sortedBy { it.start }
    return ExistingSleep(
        record.metadata.id,
        record.metadata.dataOrigin.packageName,
        record.metadata.clientRecordId,
        record.metadata.clientRecordVersion,
        record.startTime,
        record.endTime,
        stages.takeIf { it.size == record.stages.size },
    )
}

internal fun toRecord(
    session: SleepSession,
    version: Long,
): SleepSessionRecord =
    SleepSessionRecord(
        startTime = session.start,
        startZoneOffset = session.startOffset,
        endTime = session.end,
        endZoneOffset = session.endOffset,
        metadata =
            Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = session.key,
                clientRecordVersion = version,
            ),
        title = "Google wearable sleep via Sable Health Bridge",
        stages = session.stages.map { SleepSessionRecord.Stage(it.start, it.end, healthStage(it.type)) },
    )

private fun healthStage(stage: StageType): Int =
    when (stage) {
        StageType.AWAKE -> SleepSessionRecord.STAGE_TYPE_AWAKE
        StageType.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        StageType.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        StageType.REM -> SleepSessionRecord.STAGE_TYPE_REM
    }

private fun fromHealthStage(stage: Int): StageType? =
    when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> StageType.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> StageType.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> StageType.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> StageType.REM
        else -> null
    }
