package io.github.aimindseye.sablehealthbridge

import io.github.aimindseye.sablehealthbridge.core.OverlayInfo
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.Stage
import io.github.aimindseye.sablehealthbridge.core.StageType
import io.github.aimindseye.sablehealthbridge.core.normalizeOverlays
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

private const val MAX_OVERLAY_COUNT = 20000

private data class ParsedOverlays(
    val stages: List<Stage>,
    val unexpectedTypes: Int,
)

internal fun applySleepOverlays(
    sleep: JSONObject,
    primary: SleepSession,
): SleepSession {
    val awake = overlayStages(sleep, "shortAwakenings", StageType.AWAKE)
    val outOfBed = overlayStages(sleep, "outOfBedSegments", StageType.OUT_OF_BED)
    if (awake.stages.isEmpty() && outOfBed.stages.isEmpty()) return primary
    val normalized =
        try {
            normalizeOverlays(primary, awake.stages + outOfBed.stages)
        } catch (_: IllegalArgumentException) {
            throw Hold("Invalid sleep overlay bounds or stage limit; no import")
        }
    val summary = sleep.optJSONObject("summary")
    return primary.copy(
        stages = normalized,
        overlayInfo =
            OverlayInfo(
                awake.stages.size,
                outOfBed.stages.size,
                primary.asleepMinutes(),
                summaryMinutes(summary, "minutesAsleep"),
                summaryMinutes(summary, "minutesAwake"),
                awake.unexpectedTypes,
            ),
    )
}

private fun summaryMinutes(
    summary: JSONObject?,
    name: String,
): Long? = summary?.optLong(name, -1)?.takeIf { it >= 0 }

private fun overlayStages(
    sleep: JSONObject,
    name: String,
    type: StageType,
): ParsedOverlays {
    if (!sleep.has(name)) return ParsedOverlays(emptyList(), 0)
    val raw = checkedOverlayArray(sleep, name)
    val entries = (0 until raw.length()).map { raw.optJSONObject(it) ?: throw Hold("Invalid sleep overlay entry") }
    // The collection defines the interval semantics. The child type is redundant metadata.
    val unexpected = entries.count { type == StageType.AWAKE && it.optString("type") != "AWAKE" }
    val stages = entries.map { parseOverlay(it, type) }
    return ParsedOverlays(stages, unexpected)
}

private fun checkedOverlayArray(
    sleep: JSONObject,
    name: String,
): JSONArray {
    val raw = sleep.optJSONArray(name) ?: throw Hold("Invalid sleep overlay array")
    if (raw.length() > MAX_OVERLAY_COUNT) throw Hold("Sleep overlay count exceeds limit")
    return raw
}

private fun parseOverlay(
    item: JSONObject,
    type: StageType,
): Stage {
    try {
        return Stage(
            Instant.parse(item.optString("startTime")),
            Instant.parse(item.optString("endTime")),
            type,
        )
    } catch (_: DateTimeParseException) {
        throw Hold("Invalid sleep overlay timestamp")
    }
}
