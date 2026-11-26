package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.StageType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepImportBoundaryTest {
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun nonoverlappingNapsDoNotBlockMainSleep() {
        val main = fixtureSleepPoint()
        val before = nap("2025-01-01T22:00:00Z", "2025-01-01T23:00:00Z")
        val after = nap("2025-01-02T00:00:00Z", "2025-01-02T01:00:00Z")
        val selected = readLatest(now, ZoneOffset.UTC) { page(before, after, main) }
        check(selected == parseSleep(main, now))
    }

    @Test
    fun newestMainSleepSelectedAcrossDifferentWakeDates() {
        val main = fixtureSleepPoint()
        val old = fixtureSleepPoint().put("dataPointName", "fixture/old")
        old.getJSONObject("sleep").remove("stages")
        val interval = old.getJSONObject("sleep").getJSONObject("interval")
        interval.put("startTime", "2024-12-31T23:00:00Z")
        interval.put("endTime", "2025-01-01T00:00:00Z")
        val selected = readLatest(now, ZoneOffset.UTC) { page(old, main) }
        check(selected == parseSleep(main, now))
    }

    @Test
    fun completedSleepWithEmptyOverlayArraysPreservesOffsetAndStages() {
        val candidate = fixtureSleepPoint()
        val sleep = candidate.getJSONObject("sleep")
        sleep.put("shortAwakenings", JSONArray())
        sleep.put("outOfBedSegments", JSONArray())
        val interval = sleep.getJSONObject("interval")
        interval.put("startUtcOffset", "-18000s")
        interval.put("endUtcOffset", "-14400s")
        val stages = sleep.getJSONArray("stages")
        sleep.put("stages", JSONArray().put(stages.getJSONObject(1)).put(stages.getJSONObject(0)))
        val parsed = parseSleep(candidate, now)
        check(parsed.startOffset == ZoneOffset.ofHours(-5))
        check(parsed.endOffset == ZoneOffset.ofHours(-4))
        check(parsed.stages.map { it.type } == listOf(StageType.AWAKE, StageType.REM))
        check(parsed.asleepMinutes() == 50L)
    }

    @Test
    fun unfinishedSleepIsDeferredAndClassicIsAccepted() {
        val pending = fixtureSleepPoint()
        pending.getJSONObject("sleep").getJSONObject("metadata").put("processed", false)
        val failure = runCatching { parseSleep(pending, now) }.exceptionOrNull()
        check(failure is Hold && failure.label == "Sleep is still processing")
        val classic = fixtureSleepPoint()
        classic.getJSONObject("sleep").put("type", "CLASSIC")
        check(parseSleep(classic, now).sourceInfo?.type == "CLASSIC")
    }

    @Test
    fun missingStagesAndUnknownStageAreNeverInvented() {
        val empty = fixtureSleepPoint()
        empty.getJSONObject("sleep").put("stages", JSONArray())
        check(parseSleep(empty, now).stages.isEmpty())
        val unknown = fixtureSleepPoint()
        val stages = unknown.getJSONObject("sleep").getJSONArray("stages")
        stages.getJSONObject(0).put("type", "UNSUPPORTED_FIXTURE_STAGE")
        check(parseSleep(unknown, now).stages.first().type == StageType.UNKNOWN)
    }

    @Test
    fun futureWakeDateOutsideRequestIsRefused() {
        val future = fixtureSleepPoint()
        val interval = future.getJSONObject("sleep").getJSONObject("interval")
        interval.put("endTime", "2025-01-04T00:00:00Z")
        val failure = runCatching { readLatest(now, ZoneOffset.UTC) { page(future) } }.exceptionOrNull()
        check(failure is Hold && failure.label == "No eligible completed sleep in recent days")
    }

    private fun page(vararg points: JSONObject): JSONObject = JSONObject().put("dataPoints", JSONArray(points.toList()))

    private fun nap(
        start: String,
        end: String,
    ): JSONObject {
        val point = fixtureSleepPoint().put("dataPointName", "fixture/$start")
        val sleep = point.getJSONObject("sleep")
        sleep.remove("stages")
        sleep.getJSONObject("metadata").put("mainSleep", false).put("nap", true)
        val interval = sleep.getJSONObject("interval")
        interval.put("startTime", start)
        interval.put("endTime", end)
        return point
    }
}
