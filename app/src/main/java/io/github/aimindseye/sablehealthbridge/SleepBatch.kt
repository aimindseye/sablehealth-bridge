package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.sessionKey
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

private const val HISTORY_LOOKBACK = 13L
private const val PROBE_LOOKBACK = 2L
private const val SYNC_LOOKBACK = 6L

internal data class SleepBatch(
    val sessions: List<SleepSession>,
    val heldCount: Int = 0,
    val diagnostics: String = "",
)

private data class SourceBounds(
    val start: Instant,
    val end: Instant,
)

private data class ParsedPoint(
    val key: String?,
    val bounds: SourceBounds?,
    val session: SleepSession?,
)

internal fun readLatest(
    now: Instant,
    zone: ZoneId,
    fetch: (String) -> JSONObject,
): SleepSession {
    val batch = readSleepWindow(now, zone, PROBE_LOOKBACK, fetch)
    return batch.sessions.firstOrNull() ?: throw Hold("No eligible completed sleep in recent days")
}

internal fun readSleepBatch(
    now: Instant,
    zone: ZoneId,
    fetch: (String) -> JSONObject,
): SleepBatch = readSleepWindow(now, zone, HISTORY_LOOKBACK, fetch)

internal fun readSyncBatch(
    now: Instant,
    zone: ZoneId,
    initial: Boolean,
    fetch: (String) -> JSONObject,
): SleepBatch = readSleepWindow(now, zone, if (initial) HISTORY_LOOKBACK else SYNC_LOOKBACK, fetch)

private fun readSleepWindow(
    now: Instant,
    zone: ZoneId,
    lookback: Long,
    fetch: (String) -> JSONObject,
): SleepBatch {
    val today = now.atZone(zone).toLocalDate()
    val lo = today.minusDays(lookback).atStartOfDay(zone).toInstant()
    val hi = today.plusDays(1).atStartOfDay(zone).toInstant()
    val points = readPages(lo, hi, fetch)
    val parsed = points.map { parsePoint(it, now, lo, hi) }
    val unique = parsed.filter { it.session != null }.distinct() + parsed.filter { it.session == null }
    val sessions = eligibleSessions(unique)
    val order = compareByDescending<SleepSession> { it.sourceInfo?.mainSleep == true }.thenByDescending { it.end }
    return SleepBatch(sessions.sortedWith(order), unique.size - sessions.size, schemaReport(points))
}

private fun parsePoint(
    point: JSONObject,
    now: Instant,
    lo: Instant,
    hi: Instant,
): ParsedPoint {
    val bounds = pointBounds(point)
    val name = sourceName(point)
    val key = if (name.isBlank()) null else sessionKey(name, Instant.EPOCH, Instant.EPOCH)
    val session = if (bounds != null && bounds.end >= lo && bounds.end < hi) safeParse(point, now) else null
    return ParsedPoint(key, bounds, session)
}

private fun pointBounds(point: JSONObject): SourceBounds? =
    runCatching {
        val interval = sleepInterval(point)
        val start = Instant.parse(interval.getString("startTime"))
        val end = endTime(point)
        SourceBounds(start, end).takeIf { start < end }
    }.getOrNull()

private fun safeParse(
    point: JSONObject,
    now: Instant,
): SleepSession? =
    try {
        parseSleep(point, now)
    } catch (_: Hold) {
        null
    } catch (_: JSONException) {
        null
    } catch (_: DateTimeParseException) {
        null
    }

private fun eligibleSessions(parsed: List<ParsedPoint>): List<SleepSession> =
    parsed.mapNotNull { item ->
        val session = item.session ?: return@mapNotNull null
        val conflict = parsed.any { other -> other !== item && conflictsWith(session, other) }
        session.takeUnless { conflict }
    }

private fun conflictsWith(
    session: SleepSession,
    other: ParsedPoint,
): Boolean {
    val bounds = other.bounds
    return session.key == other.key || (bounds != null && bounds.start < session.end && bounds.end > session.start)
}
