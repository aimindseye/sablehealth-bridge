package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.StageType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepApiTest {
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun parserAndRecordMapping() {
        val session = parseSleep(fixtureSleepPoint(), now)
        assertEquals(50L, session.asleepMinutes())
        assertEquals(10L, session.minutes(StageType.AWAKE))
        assertEquals(ZoneOffset.UTC, session.startOffset)
        val record = toRecord(session, 0)
        assertEquals(session.start, record.startTime)
        assertEquals(session.end, record.endTime)
        assertEquals(session.key, record.metadata.clientRecordId)
        assertEquals(session.stages, existingRecord(record).stages)
    }

    @Test(expected = Hold::class)
    fun overlaysAreNotDiscarded() {
        val candidate = fixtureSleepPoint()
        candidate.getJSONObject("sleep").put("shortAwakenings", JSONArray().put(JSONObject()))
        parseSleep(candidate, now)
    }

    @Test
    fun gapsRemainUnclassified() {
        val candidate = fixtureSleepPoint()
        candidate
            .getJSONObject("sleep")
            .getJSONArray("stages")
            .getJSONObject(1)
            .put("startTime", "2025-01-01T23:11:00Z")
        val session = parseSleep(candidate, now)
        check(session.asleepMinutes() == 49L && session.stages.size == 2)
    }

    @Test(expected = Hold::class)
    fun incompleteMetadataRejected() {
        val candidate = fixtureSleepPoint()
        candidate.getJSONObject("sleep").getJSONObject("metadata").put("processed", false)
        parseSleep(candidate, now)
    }

    @Test
    fun pagingAndQuery() {
        var calls = 0
        val session =
            readLatest(now, ZoneOffset.UTC) { url ->
                assertTrue(url.startsWith(ENDPOINT))
                assertTrue(url.contains("google-wearables"))
                calls++
                if (calls == 1) {
                    JSONObject().put("nextPageToken", "fixture-next")
                } else {
                    JSONObject().put("dataPoints", JSONArray().put(fixtureSleepPoint()))
                }
            }
        assertEquals(2, calls)
        assertEquals(50L, session.asleepMinutes())
    }

    @Test(expected = Hold::class)
    fun repeatedCursorRejected() {
        readLatest(now, ZoneOffset.UTC) { JSONObject().put("nextPageToken", "same") }
    }

    @Test(expected = Hold::class)
    fun missingSessionRejected() {
        readLatest(now, ZoneOffset.UTC) { JSONObject() }
    }
}

internal fun fixtureSleepPoint(): JSONObject =
    JSONObject(
        """
        {
            "dataPointName":"users/example/dataTypes/sleep/dataPoints/fixture",
            "sleep":{
              "interval":{"startTime":"2025-01-01T23:00:00Z","endTime":"2025-01-02T00:00:00Z",
                "startUtcOffset":"0s","endUtcOffset":"0s"},
              "type":"STAGES","metadata":{"processed":true,"mainSleep":true,"stagesStatus":"SUCCEEDED"},
              "stages":[{"startTime":"2025-01-01T23:00:00Z","endTime":"2025-01-01T23:10:00Z","type":"AWAKE"},
                {"startTime":"2025-01-01T23:10:00Z","endTime":"2025-01-02T00:00:00Z","type":"REM"}]
            }
        }
        """.trimIndent(),
    )
