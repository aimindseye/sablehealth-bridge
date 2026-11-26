package io.github.aimindseye.sablehealthbridge

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.random.Random

private const val MAX_ATTEMPTS = 3
private const val BASE_DELAY_MS = 500L
private const val MAX_JITTER_MS = 250L

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_RATE_LIMITED = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_BAD_GATEWAY = 502
private const val HTTP_UNAVAILABLE = 503
private const val HTTP_GATEWAY_TIMEOUT = 504

internal fun apiStatusFailure(status: Int): Hold =
    when (status) {
        HTTP_UNAUTHORIZED -> Hold("API HTTP 401: reconnect Google Health")
        HTTP_FORBIDDEN -> Hold("API HTTP 403: check Google account access")
        HTTP_RATE_LIMITED, HTTP_SERVER_ERROR, HTTP_BAD_GATEWAY, HTTP_UNAVAILABLE, HTTP_GATEWAY_TIMEOUT ->
            Hold("API HTTP $status: temporary service error", retryable = true)
        else -> Hold("API HTTP $status")
    }

/** Retry only reads, never imports. A failed complete request cannot authorize a write. */
internal suspend fun <T> retrySleepRead(
    pause: suspend (Long) -> Unit = { delay(it) },
    jitter: () -> Long = { Random.nextLong(MAX_JITTER_MS) },
    read: suspend () -> T,
): T {
    repeat(MAX_ATTEMPTS - 1) { attempt ->
        try {
            return read()
        } catch (failure: Hold) {
            if (!failure.retryable) throw failure
        } catch (_: IOException) {
            // Temporary connection or read failure. No raw exception text is retained.
        }
        pause(BASE_DELAY_MS * (1L shl attempt) + jitter())
    }
    return read()
}
