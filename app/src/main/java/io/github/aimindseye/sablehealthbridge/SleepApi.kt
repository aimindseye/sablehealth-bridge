package io.github.aimindseye.sablehealthbridge

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

internal const val ENDPOINT = "https://health.googleapis.com/v4/users/me/dataTypes/sleep/dataPoints:reconcile"
private const val IDENTITY_ENDPOINT = "https://health.googleapis.com/v4/users/me/identity"
private const val FAMILY = "users/me/dataSourceFamilies/google-wearables"
private const val MAX_BYTES = 8 * 1024 * 1024
private const val MAX_PAGES = 20
private const val PAGE_SIZE = 25
private const val TIMEOUT = 30000
private const val HTTP_OK = 200
private const val BUFFER_SIZE = 8192
private const val MAX_CURSOR_LENGTH = 8192

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
    return fetchGoogle(url, token, open)
}

internal fun fetchIdentity(
    token: String,
    open: (String) -> HttpURLConnection = ::openGoogleConnection,
): JSONObject = fetchGoogle(IDENTITY_ENDPOINT, token, open)

private fun fetchGoogle(
    url: String,
    token: String,
    open: (String) -> HttpURLConnection,
): JSONObject {
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
        if (status != HTTP_OK) throw apiStatusFailure(status)
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
            repeat(records.length()) { points.add(records.optJSONObject(it) ?: JSONObject()) }
        }
        cursor = if (page.has("nextPageToken")) page.getString("nextPageToken") else ""
        if (cursor.isEmpty()) return points
        requireData(cursor.length <= MAX_CURSOR_LENGTH && seen.add(cursor), "Invalid API pagination")
    }
    throw Hold("API page limit reached")
}
