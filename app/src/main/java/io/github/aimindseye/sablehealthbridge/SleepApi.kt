package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import io.github.aimindseye.sablehealthbridge.core.sessionKey
import io.github.aimindseye.sablehealthbridge.core.validSession
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

internal const val ENDPOINT = "https://health.googleapis.com/v4/users/me/dataTypes/sleep/dataPoints:reconcile"
private const val FAMILY = "users/me/dataSourceFamilies/google-wearables"
private const val MAX_BYTES = 8 * 1024 * 1024
private const val MAX_PAGES = 20
private const val PAGE_SIZE = 25
private const val TIMEOUT = 30000
private const val HTTP_OK = 200
private const val BUFFER_SIZE = 8192
private const val MAX_CURSOR_LENGTH = 8192
private const val MAX_STAGE_COUNT = 20000

private fun requireData(
    valid: Boolean,
    message: String,
) {
    if (!valid) throw Hold(message)
}

internal fun sleepQuery(
    lo: Instant,
    hi: Instant,
    cursor: String,
): String {
    val filter = "sleep.interval.end_time >= \"$lo\" AND sleep.interval.end_time < \"$hi\""
    val params =
        mapOf(
            "filter" to filter,
            "dataSourceFamily" to FAMILY,
            "pageSize" to PAGE_SIZE.toString(),
        )
    val all = if (cursor.isBlank()) params else params + ("pageToken" to cursor)
    val query =
        all.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    return "$ENDPOINT?$query"
}

internal fun fetchPage(
    url: String,
    token: String,
    open: (String) -> HttpURLConnection = ::openGoogleConnection,
): JSONObject {
    require(url.startsWith("$ENDPOINT?"))
    val connection = open(url)
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        val status = connection.responseCode
        requireData(status == HTTP_OK, "API HTTP $status")
        val bytes = connection.inputStream.use(::readBounded)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}

private fun openGoogleConnection(url: String): HttpURLConnection = URL(url).openConnection() as HttpURLConnection

private fun readBounded(input: InputStream): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        requireData(output.size() + count <= MAX_BYTES, "API response too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun readLatest(
    now: Instant,
    zone: ZoneId,
    fetch: (String) -> JSONObject,
): SleepSession {
    val today = now.atZone(zone).toLocalDate()
    val lo = today.minusDays(2).atStartOfDay(zone).toInstant()
    val hi = today.plusDays(1).atStartOfDay(zone).toInstant()
    val points = readPages(lo, hi, fetch)
    val candidates =
        points.filter { point ->
            val metadata = point.getJSONObject("sleep").getJSONObject("metadata")
            metadata.optBoolean("mainSleep")
        }
    val latest =
        candidates.maxByOrNull(::endTime)
            ?: throw Hold("No main sleep in the last three days")
    val end = endTime(latest)
    requireData(end >= lo && end < hi, "Sleep outside requested dates")
    val day = end.atZone(zone).toLocalDate()
    requireData(
        candidates.count { endTime(it).atZone(zone).toLocalDate() == day } == 1,
        "Multiple main sleep records; no import",
    )
    val session = parseSleep(latest, now)
    val otherOverlap =
        points.filter { it !== latest }.any { point ->
            val interval = point.getJSONObject("sleep").getJSONObject("interval")
            Instant.parse(interval.getString("startTime")) < session.end && endTime(point) > session.start
        }
    requireData(!otherOverlap, "Overlapping API sleep records; no import")
    return session
}

internal fun readPages(
    lo: Instant,
    hi: Instant,
    fetch: (String) -> JSONObject,
): List<JSONObject> {
    val points = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var cursor = ""
    repeat(MAX_PAGES) {
        val page = fetch(sleepQuery(lo, hi, cursor))
        requireData(!page.has("error"), "API response error")
        val records = if (page.has("dataPoints")) page.getJSONArray("dataPoints") else null
        if (records != null) {
            requireData(records.length() <= PAGE_SIZE, "API page too large")
            repeat(records.length()) { points.add(records.getJSONObject(it)) }
        }
        cursor = if (page.has("nextPageToken")) page.getString("nextPageToken") else ""
        if (cursor.isEmpty()) return points
        requireData(cursor.length <= MAX_CURSOR_LENGTH && seen.add(cursor), "Invalid API pagination")
    }
    throw Hold("API page limit reached")
}

internal fun parseSleep(
    point: JSONObject,
    now: Instant,
): SleepSession {
    val sleep = point.getJSONObject("sleep")
    val meta = sleep.getJSONObject("metadata")
    val finalized = meta.optBoolean("processed") && meta.optString("stagesStatus") == "SUCCEEDED"
    val detailedMain = sleep.getString("type") == "STAGES" && meta.optBoolean("mainSleep")
    requireData(finalized && detailedMain, "Sleep is not finalized with detailed stages")
    // Do not silently discard overlay semantics we have not yet qualified.
    requireData(
        !hasOverlays(sleep, "shortAwakenings") && !hasOverlays(sleep, "outOfBedSegments"),
        "Sleep contains overlays; import not yet supported",
    )
    val interval = sleep.getJSONObject("interval")
    val start = Instant.parse(interval.getString("startTime"))
    val end = Instant.parse(interval.getString("endTime"))
    val raw = sleep.getJSONArray("stages")
    requireData(raw.length() in 1..MAX_STAGE_COUNT, "Invalid sleep stage count")
    val stages =
        (0 until raw.length())
            .map { index ->
                val item = raw.getJSONObject(index)
                Stage(
                    Instant.parse(item.getString("startTime")),
                    Instant.parse(item.getString("endTime")),
                    StageType.valueOf(item.getString("type")),
                )
            }.sortedBy { it.start }
    val name = point.optString("dataPointName").ifBlank { point.optString("name") }
    requireData(name.isNotBlank(), "Sleep identity missing; no import")
    val session =
        SleepSession(
            sessionKey(name, start, end),
            start,
            end,
            offset(interval, "startUtcOffset"),
            offset(interval, "endUtcOffset"),
            stages,
        )
    requireData(validSession(session, now), "Sleep stages incomplete, overlapping or invalid")
    return session
}
