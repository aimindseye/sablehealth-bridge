package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.SourceSleepSummary
import io.github.aimindseye.sablehealthbridge.core.SourceStageSummary
import org.json.JSONObject

private const val MAX_SUMMARY_STAGES = 100

internal fun parseSummary(summary: JSONObject?): SourceSleepSummary? {
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
    val raw = summary.optJSONArray("stagesSummary")?.takeIf { it.length() <= MAX_SUMMARY_STAGES } ?: return emptyList()
    return (0 until raw.length()).mapNotNull { index ->
        raw.optJSONObject(index)?.let(::parseStageSummary)
    }
}

private fun parseStageSummary(item: JSONObject): SourceStageSummary =
    SourceStageSummary(
        knownLabel(item, "type", sourceStageTypes),
        sourceNumber(item, "minutes"),
        sourceNumber(item, "count"),
    )

private fun sourceNumber(
    item: JSONObject,
    field: String,
): Long? = item.opt(field)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
