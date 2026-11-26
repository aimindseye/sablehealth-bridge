package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SourceSleepInfo
import io.github.aimindseye.sablehealthbridge.core.SourceSleepSummary
import io.github.aimindseye.sablehealthbridge.core.SourceStageSummary
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import io.github.aimindseye.sablehealthbridge.core.sessionKey
import io.github.aimindseye.sablehealthbridge.core.validSession
import org.json.JSONObject
import java.time.Instant

private const val MAX_PRIMARY_STAGES = 20000
private const val MAX_SUMMARY_STAGES = 100
internal val sleepTypes = setOf("STAGES", "CLASSIC", "SLEEP_TYPE_UNSPECIFIED")
internal val sourceStageTypes =
    setOf("AWAKE", "LIGHT", "DEEP", "REM", "ASLEEP", "RESTLESS", "SLEEP_STAGE_TYPE_UNSPECIFIED")
internal val processingStates =
    setOf(
        "STAGES_STATE_UNSPECIFIED",
        "SUCCEEDED",
        "REJECTED_COVERAGE",
        "REJECTED_MAX_GAP",
        "REJECTED_START_GAP",
        "REJECTED_END_GAP",
        "REJECTED_NAP",
        "REJECTED_SERVER",
        "TIMEOUT",
        "PROCESSING_INTERNAL_ERROR",
    )

internal fun knownLabel(
    item: JSONObject?,
    field: String,
    known: Set<String>,
): String {
    val raw = item?.opt(field)
    return when {
        raw == null || raw == JSONObject.NULL -> "MISSING"
        raw is String && raw.isBlank() -> "MISSING"
        raw is String && raw in known -> raw
        else -> "OTHER"
    }
}

internal fun parseSleep(
    point: JSONObject,
    now: Instant,
): SleepSession {
    val sleep = point.getJSONObject("sleep")
    val metadata = sleep.optJSONObject("metadata")
    requireParsed(metadata?.opt("processed") != false, "Sleep is still processing")
    val interval = sleepInterval(point)
    val start = Instant.parse(interval.getString("startTime"))
    val end = Instant.parse(interval.getString("endTime"))
    val name = sourceName(point)
    requireParsed(name.isNotBlank(), "Sleep identity missing; no import")
    val source = parseSourceInfo(sleep, metadata).withAttribution(point.optJSONObject("dataSource"))
    val session =
        SleepSession(
            sessionKey(name, start, end),
            start,
            end,
            offset(interval, "startUtcOffset"),
            offset(interval, "endUtcOffset"),
            source.primaryStages,
            sourceInfo = source,
        )
    requireParsed(validSession(session, now), "Sleep session or primary stage bounds invalid")
    return applySleepOverlays(sleep, session)
}

private fun parseSourceInfo(
    sleep: JSONObject,
    metadata: JSONObject?,
): SourceSleepInfo {
    val stages = primaryStages(sleep)
    return SourceSleepInfo(
        knownLabel(sleep, "type", sleepTypes),
        knownLabel(metadata, "stagesStatus", processingStates),
        metadata?.opt("processed") as? Boolean,
        metadata?.optBoolean("mainSleep") == true,
        metadata?.optBoolean("nap") == true,
        metadata?.optBoolean("manuallyEdited") == true,
        optionalTime(sleep, "createTime"),
        optionalTime(sleep, "updateTime"),
        parseSummary(sleep.optJSONObject("summary")),
        stages.map { it.first },
        stages.map { it.second },
    )
}

private fun primaryStages(sleep: JSONObject): List<Pair<Stage, String>> {
    if (sleep.isNull("stages")) return emptyList()
    val raw = sleep.optJSONArray("stages") ?: throw Hold("Invalid primary stages array")
    if (raw.length() > MAX_PRIMARY_STAGES) throw Hold("Primary stage count exceeds limit")
    return (0 until raw.length())
        .map { parsePrimaryStage(raw.getJSONObject(it)) }
        .sortedBy { it.first.start }
}

private fun parsePrimaryStage(item: JSONObject): Pair<Stage, String> {
    val sourceType = knownLabel(item, "type", sourceStageTypes)
    val type =
        when (sourceType) {
            "AWAKE" -> StageType.AWAKE
            "LIGHT" -> StageType.LIGHT
            "DEEP" -> StageType.DEEP
            "REM" -> StageType.REM
            "ASLEEP" -> StageType.ASLEEP
            else -> StageType.UNKNOWN
        }
    val start = Instant.parse(item.getString("startTime"))
    val end = Instant.parse(item.getString("endTime"))
    return Stage(start, end, type) to sourceType
}

private fun optionalTime(
    item: JSONObject,
    field: String,
): Instant? = runCatching { Instant.parse(item.optString(field)) }.getOrNull()

private fun parseSummary(summary: JSONObject?): SourceSleepSummary? {
    if (summary == null) return null
    return SourceSleepSummary(
        sourceNumber(summary, "minutesAsleep"),
        sourceNumber(summary, "minutesAwake"),
        sourceNumber(summary, "minutesInSleepPeriod"),
        sourceNumber(summary, "minutesToFallAsleep"),
        sourceNumber(summary, "minutesAfterWakeUp"),
        parseStageSummaries(summary),
    )
}

private fun parseStageSummaries(summary: JSONObject): List<SourceStageSummary> {
    val raw = summary.optJSONArray("stagesSummary") ?: return emptyList()
    if (raw.length() > MAX_SUMMARY_STAGES) return emptyList()
    return (0 until raw.length()).mapNotNull { index ->
        val item = raw.optJSONObject(index) ?: return@mapNotNull null
        SourceStageSummary(
            knownLabel(item, "type", sourceStageTypes),
            sourceNumber(item, "minutes"),
            sourceNumber(item, "count"),
        )
    }
}

private fun sourceNumber(
    item: JSONObject,
    field: String,
): Long? = item.opt(field)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

private fun requireParsed(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw Hold(message)
}

private val recordingMethods =
    setOf(
        "MANUAL", "PASSIVELY_MEASURED", "ACTIVELY_MEASURED",
        "DERIVED", "UNKNOWN", "RECORDING_METHOD_UNSPECIFIED",
    )
private val formFactors =
    setOf(
        "FITNESS_BAND", "WATCH", "PHONE", "RING", "CHEST_STRAP", "SCALE",
        "TABLET", "HEAD_MOUNTED", "SMART_DISPLAY", "FORM_FACTOR_UNSPECIFIED",
    )

private fun SourceSleepInfo.withAttribution(source: JSONObject?): SourceSleepInfo {
    requireParsed(source?.optString("platform") != "HEALTH_CONNECT", "Health Connect source; no round-trip import")
    return copy(
        recordingMethod = knownLabel(source, "recordingMethod", recordingMethods),
        formFactor = knownLabel(source?.optJSONObject("device"), "formFactor", formFactors),
    )
}
