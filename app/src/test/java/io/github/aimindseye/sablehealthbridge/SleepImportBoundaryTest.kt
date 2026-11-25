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
        val old = fixtureSleepPoint()
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
    fun unfinalizedOrClassicSleepIsRefused() {
        val pending = fixtureSleepPoint()
        pending.getJSONObject("sleep").getJSONObject("metadata").put("stagesStatus", "PENDING")
        val classic = fixtureSleepPoint()
        classic.getJSONObject("sleep").put("type", "CLASSIC")
        for (point in listOf(pending, classic)) {
            val failure = runCatching { parseSleep(point, now) }.exceptionOrNull()
            check(failure is Hold && failure.label == "Sleep is not finalized with detailed stages")
        }
    }

    @Test
    fun missingStagesAndUnknownStageAreNeverInvented() {
        val empty = fixtureSleepPoint()
        empty.getJSONObject("sleep").put("stages", JSONArray())
        val missing = runCatching { parseSleep(empty, now) }.exceptionOrNull()
        check(missing is Hold && missing.label == "Invalid sleep stage count")
        val unknown = fixtureSleepPoint()
        val stages = unknown.getJSONObject("sleep").getJSONArray("stages")
        stages.getJSONObject(0).put("type", "UNSUPPORTED_FIXTURE_STAGE")
        check(runCatching { parseSleep(unknown, now) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun futureWakeDateOutsideRequestIsRefused() {
        val future = fixtureSleepPoint()
        val interval = future.getJSONObject("sleep").getJSONObject("interval")
        interval.put("endTime", "2025-01-04T00:00:00Z")
        val failure = runCatching { readLatest(now, ZoneOffset.UTC) { page(future) } }.exceptionOrNull()
        check(failure is Hold && failure.label == "Sleep outside requested dates")
    }

    private fun page(vararg points: JSONObject): JSONObject = JSONObject().put("dataPoints", JSONArray(points.toList()))

    private fun nap(
        start: String,
        end: String,
    ): JSONObject {
        val point = fixtureSleepPoint()
        val sleep = point.getJSONObject("sleep")
        sleep.getJSONObject("metadata").put("mainSleep", false)
        val interval = sleep.getJSONObject("interval")
        interval.put("startTime", start)
        interval.put("endTime", end)
        return point
    }
}
