package io.github.aimindseye.sablehealthbridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTransportTest {
    private val url = "$ENDPOINT?fixture=1"
    private val now = Instant.parse("2025-01-02T12:00:00Z")

    @Test
    fun identityUsesOnlyFixedEndpointAndClosesResources() {
        val connection = FixtureConnection(200, "{\"healthUserId\":\"synthetic-user\"}".toByteArray())
        val result =
            fetchIdentity("fixture-token") { requested ->
                check(requested == "https://health.googleapis.com/v4/users/me/identity")
                connection
            }
        check(result.getString("healthUserId") == "synthetic-user")
        check(connection.requestMethod == "GET" && !connection.instanceFollowRedirects)
        check(connection.disconnected && connection.streamClosed)
        val refused = FixtureConnection(403, byteArrayOf())
        val error = runCatching { fetchIdentity("fixture-token") { refused } }.exceptionOrNull()
        check(error is Hold && error.label == "API HTTP 403: check Google account access")
        check(!error.retryable)
        check(refused.disconnected && !refused.streamOpened)
    }

    @Test
    fun authorizedGetAndResourceCleanup() {
        val connection = FixtureConnection(200, "{\"dataPoints\":[]}".toByteArray())
        val json =
            fetchPage(url, "fixture-token") { requested ->
                check(requested == url)
                connection
            }
        check(json.getJSONArray("dataPoints").length() == 0)
        check(connection.requestMethod == "GET" && !connection.instanceFollowRedirects)
        check(connection.connectTimeout == 30000 && connection.readTimeout == 30000)
        check(!connection.useCaches && connection.disconnected && connection.streamClosed)
        check(connection.getRequestProperty("Authorization") == "Bearer fixture-token")
        check(connection.getRequestProperty("Accept") == "application/json")
    }

    @Test
    fun refusesNonGoogleEndpointBeforeOpeningConnection() {
        val error =
            runCatching {
                fetchPage("https://example.invalid/", "fixture-token") { error("unexpected network") }
            }.exceptionOrNull()
        check(error is IllegalArgumentException)
    }

    @Test
    fun redirectAndAuthorizationFailureNeverReadResponse() {
        for (status in listOf(302, 401, 403, 429, 503)) {
            val connection = FixtureConnection(status, byteArrayOf())
            val error = runCatching { fetchPage(url, "fixture-token") { connection } }.exceptionOrNull()
            check(error is Hold && error.label.startsWith("API HTTP $status"))
            check(connection.disconnected && !connection.streamOpened)
        }
    }

    @Test
    fun malformedAndOversizedResponsesCloseResources() {
        val payloads = listOf("not-json".toByteArray(), ByteArray(8 * 1024 * 1024 + 1))
        payloads.forEach { payload ->
            val connection = FixtureConnection(200, payload)
            check(runCatching { fetchPage(url, "fixture-token") { connection } }.isFailure)
            check(connection.disconnected && connection.streamClosed)
        }
    }

    @Test
    fun readFailureDisconnectsAndDoesNotRetry() {
        val connection = FixtureConnection(200, byteArrayOf(), broken = true)
        var opens = 0
        val error =
            runCatching {
                fetchPage(url, "fixture-token") {
                    opens++
                    connection
                }
            }.exceptionOrNull()
        check(error is IOException && opens == 1)
        check(connection.disconnected && connection.streamClosed)
    }

    @Test
    fun rejectsErrorOversizedPageAndLongCursor() {
        val pages =
            listOf(
                JSONObject().put("error", JSONObject()),
                JSONObject().put("dataPoints", JSONArray(List(26) { JSONObject() })),
                JSONObject().put("nextPageToken", "a".repeat(8193)),
            )
        for (page in pages) {
            check(runCatching { readPages(now.minusSeconds(60), now) { page } }.exceptionOrNull() is Hold)
        }
    }

    @Test
    fun uniqueCursorPageLimitIsFailClosed() {
        var calls = 0
        val error =
            runCatching {
                readPages(now.minusSeconds(60), now) { JSONObject().put("nextPageToken", (++calls).toString()) }
            }.exceptionOrNull()
        check(error is Hold && calls == 20)
    }

    @Test
    fun cursorAndTimezoneWindowAreEncoded() {
        val query = sleepQuery(now.minusSeconds(60), now, "a+b/c=")
        check(query.contains("pageToken=a%2Bb%2Fc%3D"))
        check(!sleepQuery(now.minusSeconds(60), now, "").contains("pageToken"))
        val error = runCatching { readLatest(now, ZoneOffset.UTC) { JSONObject() } }.exceptionOrNull()
        check(error is Hold)
    }
}

private class FixtureConnection(
    private val status: Int,
    private val body: ByteArray,
    private val broken: Boolean = false,
) : HttpURLConnection(URL("$ENDPOINT?fixture=1")) {
    var disconnected = false
    var streamOpened = false
    var streamClosed = false

    override fun connect() = Unit

    override fun usingProxy(): Boolean = false

    override fun disconnect() {
        disconnected = true
    }

    override fun getResponseCode(): Int = status

    override fun getInputStream(): InputStream {
        streamOpened = true
        return object : ByteArrayInputStream(body) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (broken) throw IOException("fixture read failure")
                return super.read(b, off, len)
            }

            override fun close() {
                streamClosed = true
                super.close()
            }
        }
    }
}
