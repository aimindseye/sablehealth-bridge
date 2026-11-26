package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.StageType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepOverlayParsingTest {
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun preservesBothOverlayTypesAndReportedSummary() {
        val point = fixtureSleepPoint()
        val original = parseSleep(point, now)
        val sleep = point.getJSONObject("sleep")
        sleep.put("shortAwakenings", JSONArray().put(overlay("15", "25").put("type", "AWAKE")))
        sleep.put("outOfBedSegments", JSONArray().put(overlay("20", "35")))
        sleep.put("summary", JSONObject().put("minutesAsleep", "47").put("minutesAwake", "13"))
        val session = parseSleep(point, now)
        val info = requireNotNull(session.overlayInfo)
        check(info.shortAwakenings == 1 && info.outOfBedSegments == 1)
        check(info.primaryAsleepMinutes == 50L && info.reportedAsleepMinutes == 47L && info.reportedAwakeMinutes == 13L)
        check(session.key == original.key && session.startOffset == original.startOffset)
        check(session.asleepMinutes() == 30L)
        check(session.minutes(StageType.AWAKE) == 15L && session.minutes(StageType.OUT_OF_BED) == 15L)
        check(existingRecord(toRecord(session, 0)).stages == session.stages)
    }

    @Test
    fun missingOrIncompleteSummaryIsNotInvented() {
        val point = fixtureSleepPoint()
        val sleep = point.getJSONObject("sleep")
        sleep.put("outOfBedSegments", JSONArray().put(overlay("20", "35")))
        val absent = requireNotNull(parseSleep(point, now).overlayInfo)
        check(absent.reportedAsleepMinutes == null && absent.reportedAwakeMinutes == null)
        sleep.put("summary", JSONObject().put("minutesAsleep", -1).put("minutesAwake", 10))
        val partial = requireNotNull(parseSleep(point, now).overlayInfo)
        check(partial.reportedAsleepMinutes == null && partial.reportedAwakeMinutes == 10L)
    }

    @Test
    fun malformedOverlaysFailWithoutDiscardingData() {
        val cases =
            listOf(
                JSONObject().put("shortAwakenings", JSONObject()),
                JSONObject().put("shortAwakenings", JSONArray().put(42)),
                JSONObject().put("shortAwakenings", JSONArray().put(overlay("15", "25").put("type", "REM"))),
                JSONObject().put("outOfBedSegments", JSONArray().put(JSONObject())),
                JSONObject().put("outOfBedSegments", JSONArray().put(overlay("35", "20"))),
                JSONObject().put("outOfBedSegments", JSONArray().put(overlay("20", "99"))),
                JSONObject().put(
                    "outOfBedSegments",
                    JSONArray().put(overlay("20", "35").put("endTime", "2025-01-02T00:01:00Z")),
                ),
                JSONObject().put("outOfBedSegments", JSONArray(List(20001) { overlay("20", "21") })),
            )
        cases.forEach { fields ->
            val point = fixtureSleepPoint()
            val sleep = point.getJSONObject("sleep")
            fields.keys().forEach { key -> sleep.put(key, fields.get(key)) }
            check(runCatching { parseSleep(point, now) }.exceptionOrNull() is Hold)
        }
    }

    private fun overlay(
        from: String,
        to: String,
    ): JSONObject =
        JSONObject()
            .put("startTime", "2025-01-01T23:$from:00Z")
            .put("endTime", "2025-01-01T23:$to:00Z")
}
