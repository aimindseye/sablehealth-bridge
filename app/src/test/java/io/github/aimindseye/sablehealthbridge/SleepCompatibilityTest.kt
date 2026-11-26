package io.github.aimindseye.sablehealthbridge

import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.github.aimindseye.sablehealthbridge.core.StageType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepCompatibilityTest {
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun documentedAndFuturePrimaryTypesMapWithoutInventingAsleep() {
        val cases =
            mapOf(
                "AWAKE" to StageType.AWAKE,
                "LIGHT" to StageType.LIGHT,
                "DEEP" to StageType.DEEP,
                "REM" to StageType.REM,
                "ASLEEP" to StageType.ASLEEP,
                "RESTLESS" to StageType.UNKNOWN,
                "SLEEP_STAGE_TYPE_UNSPECIFIED" to StageType.UNKNOWN,
                "future-sensitive-value" to StageType.UNKNOWN,
                "" to StageType.UNKNOWN,
            )
        cases.forEach { (type, expected) ->
            val point = fixtureSleepPoint()
            val sleep = point.getJSONObject("sleep")
            sleep.getJSONArray("stages").getJSONObject(0).put("type", type)
            val parsed = parseSleep(point, now)
            check(parsed.stages.first().type == expected)
            check(existingRecord(toRecord(parsed, 0)).stages == parsed.stages)
        }
    }

    @Test
    fun allProcessingStatesAndOptionalFieldsSupportSessionOnly() {
        val statuses = processingStates + "FUTURE" + ""
        statuses.forEach { status ->
            val point = fixtureSleepPoint()
            val sleep = point.getJSONObject("sleep")
            sleep.remove("type")
            sleep.remove("stages")
            sleep.getJSONObject("metadata").put("stagesStatus", status).put("nap", true)
            val session = parseSleep(point, now)
            check(session.stages.isEmpty() && session.sourceInfo?.nap == true)
            check(existingRecord(toRecord(session, 0)).stages?.isEmpty() == true)
        }
        val point = fixtureSleepPoint()
        point.getJSONObject("sleep").remove("metadata")
        check(parseSleep(point, now).sourceInfo?.processed == null)
    }

    @Test
    fun reportsSourceSummaryWithoutOverlayAndPreservesRevision() {
        val point = fixtureSleepPoint()
        val sleep = point.getJSONObject("sleep")
        sleep.put("type", "CLASSIC")
        sleep.put("updateTime", "2025-01-02T01:00:00.123Z")
        val summary = JSONObject().put("minutesAsleep", "47").put("minutesAwake", 13)
        summary.put("minutesInSleepPeriod", "60").put("minutesToFallAsleep", -1)
        summary.put("minutesAfterWakeUp", 1.5)
        val item = JSONObject().put("type", "RESTLESS").put("minutes", "3").put("count", "2")
        summary.put("stagesSummary", JSONArray().put(item))
        sleep.put("summary", summary)
        val source = requireNotNull(parseSleep(point, now).sourceInfo)
        val metrics = requireNotNull(source.summary)
        check(metrics.minutesAsleep == 47L && metrics.minutesAwake == 13L)
        check(metrics.minutesInSleepPeriod == 60L && metrics.minutesToFallAsleep == null)
        check(metrics.minutesAfterWakeUp == null && metrics.stages.single().count == 2L)
        check(source.updatedAt == Instant.parse("2025-01-02T01:00:00.123Z"))
    }

    @Test
    fun validSessionsSurviveUnrelatedMalformedAndConflictingRecords() {
        val main = fixtureSleepPoint()
        val nap = fixtureSleepPoint().put("dataPointName", "fixture/nap")
        val sleep = nap.getJSONObject("sleep")
        sleep.remove("stages")
        sleep.getJSONObject("metadata").put("nap", true).put("mainSleep", false)
        val interval = sleep.getJSONObject("interval")
        interval.put("startTime", "2025-01-02T09:00:00Z").put("endTime", "2025-01-02T09:30:00Z")
        val other = fixtureSleepPoint().put("dataPointName", "fixture/overlap")
        val page = JSONObject().put("dataPoints", JSONArray().put(main).put(other).put(nap).put(JSONObject()))
        val batch = readSleepBatch(now, ZoneOffset.UTC) { page }
        check(batch.sessions.single().sourceInfo?.nap == true && batch.heldCount == 3)
    }

    @Test
    fun diagnosticNeverContainsRawNamesTimestampsOrCustomValues() {
        val point = fixtureSleepPoint().put("name", "sensitive-user")
        val sleep = point.getJSONObject("sleep")
        sleep.put("private-field", "private-value").put("type", "sensitive-type")
        sleep.put("shortAwakenings", JSONArray().put(JSONObject().put("type", "RESTLESS")))
        val report = schemaReport(listOf(point))
        check(report.contains("Sleep types: OTHER") && report.contains("Awakening types: RESTLESS"))
        listOf("sensitive", "private", "2025-01-01", "fixture").forEach { check(!report.contains(it)) }
        check(schemaReport(emptyList()).contains("Sleep types: NONE"))
    }

    @Test
    fun transientReadsRetryButPermanentErrorsDoNot() =
        runBlocking {
            val delays = mutableListOf<Long>()
            var calls = 0
            val result =
                retrySleepRead(pause = { delays.add(it) }, jitter = { 0L }) {
                    calls++
                    when (calls) {
                        1 -> throw apiStatusFailure(429)
                        2 -> throw IOException("private")
                        else -> "success"
                    }
                }
            check(result == "success" && delays == listOf(500L, 1000L))
            calls = 0
            val failure =
                runCatching {
                    retrySleepRead(pause = { error("no retry") }) {
                        calls++
                        throw apiStatusFailure(401)
                    }
                }.exceptionOrNull()
            check(failure is Hold && calls == 1)
        }

    @Test
    fun sourceAttributionDoesNotInventRecordingMethodOrRecycleHealthConnect() {
        val point = fixtureSleepPoint()
        val unknown = toRecord(parseSleep(point, now), 0).metadata
        check(unknown.recordingMethod == Metadata.RECORDING_METHOD_UNKNOWN)
        val source = JSONObject().put("recordingMethod", "PASSIVELY_MEASURED").put("platform", "FITBIT")
        source.put("device", JSONObject().put("formFactor", "FITNESS_BAND"))
        point.put("dataSource", source)
        val recorded = toRecord(parseSleep(point, now), 0).metadata
        check(recorded.recordingMethod == Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED)
        check(recorded.device?.type == Device.TYPE_FITNESS_BAND)
        source.put("recordingMethod", "MANUAL")
        check(toRecord(parseSleep(point, now), 0).metadata.recordingMethod == Metadata.RECORDING_METHOD_MANUAL_ENTRY)
        source.put("recordingMethod", "ACTIVELY_MEASURED")
        check(toRecord(parseSleep(point, now), 0).metadata.recordingMethod == Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
        source.put("platform", "HEALTH_CONNECT")
        check(runCatching { parseSleep(point, now) }.exceptionOrNull() is Hold)
    }

    @Test
    fun offsetsAcceptIntegralDurationButNotFractionalOrInvalidValues() {
        val interval = JSONObject().put("offset", "-14400.000s")
        check(offset(interval, "offset") == ZoneOffset.ofHours(-4))
        for (raw in listOf("3.5s", "99999999s", "bad", "123")) {
            interval.put("offset", raw)
            check(offset(interval, "offset") == null)
        }
    }
}
