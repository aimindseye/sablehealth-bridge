package io.github.aimindseye.sablehealthbridge

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
class SleepApiEdgeTest {
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun missingOffsetsAndAlternateIdentity() {
        val candidate = fixtureSleepPoint()
        candidate.remove("dataPointName")
        candidate.put("name", "fixture/alternate")
        val interval = candidate.getJSONObject("sleep").getJSONObject("interval")
        interval.remove("startUtcOffset")
        interval.remove("endUtcOffset")
        val parsed = parseSleep(candidate, now)
        assertEquals(null, parsed.startOffset)
        assertEquals(null, parsed.endOffset)
        assertTrue(parsed.key.startsWith("sb-r1-"))
    }

    @Test
    fun rejectsMissingIdentityAndMalformedOverlayButAcceptsNonMain() {
        val missing = fixtureSleepPoint().apply { remove("dataPointName") }
        val nap = fixtureSleepPoint().apply { getJSONObject("sleep").getJSONObject("metadata").put("mainSleep", false) }
        val overlay = fixtureSleepPoint()
        overlay.getJSONObject("sleep").put("outOfBedSegments", JSONArray().put(JSONObject()))
        check(parseSleep(nap, now).sourceInfo?.mainSleep == false)
        for (candidate in listOf(missing, overlay)) {
            assertTrue(runCatching { parseSleep(candidate, now) }.exceptionOrNull() is Hold)
        }
    }

    @Test
    fun identicalDuplicateMainRecordsAreDeduplicated() {
        val points = JSONArray().put(fixtureSleepPoint()).put(fixtureSleepPoint())
        val batch = readSleepBatch(now, ZoneOffset.UTC) { JSONObject().put("dataPoints", points) }
        check(batch.sessions.size == 1 && batch.heldCount == 0)
    }

    @Test
    fun automaticInitialWindowAndRecurringWindowDiffer() {
        val date = Instant.parse("2025-01-10T12:00:00Z")
        val points = JSONArray().put(fixtureSleepPoint())
        val page = JSONObject().put("dataPoints", points)
        check(readSyncBatch(date, ZoneOffset.UTC, true) { page }.sessions.size == 1)
        check(readSyncBatch(date, ZoneOffset.UTC, false) { page }.sessions.isEmpty())
    }

    @Test
    fun overlappingNapAndOutOfWindowSleepAreRejected() {
        val nap = fixtureSleepPoint().apply { getJSONObject("sleep").getJSONObject("metadata").put("mainSleep", false) }
        val error =
            runCatching {
                val points = JSONArray().put(fixtureSleepPoint()).put(nap)
                readLatest(now, ZoneOffset.UTC) { JSONObject().put("dataPoints", points) }
            }.exceptionOrNull()
        assertTrue(error is Hold)
        val future = Instant.parse("2025-01-10T12:00:00Z")
        val outside =
            runCatching {
                val points = JSONArray().put(fixtureSleepPoint())
                readLatest(future, ZoneOffset.UTC) { JSONObject().put("dataPoints", points) }
            }.exceptionOrNull()
        assertTrue(outside is Hold)
    }
}
