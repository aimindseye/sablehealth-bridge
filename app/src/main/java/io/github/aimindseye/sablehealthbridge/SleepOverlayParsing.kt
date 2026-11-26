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
    val normalized = checkedNormalize(primary, outOfBed.stages)
    val shortOnly = primary.copy(stages = emptyList())
    val shortTimeline = checkedNormalize(shortOnly, awake.stages)
    val summary = primary.sourceInfo?.summary
    val source = primary.sourceInfo?.copy(shortAwakenings = awake.stages, outOfBedSegments = outOfBed.stages)
    return primary.copy(
        stages = normalized,
        sourceInfo = source,
        overlayInfo =
            OverlayInfo(
                awake.stages.size,
                outOfBed.stages.size,
                primary.asleepMinutes(),
                summary?.minutesAsleep,
                summary?.minutesAwake,
                awake.unexpectedTypes,
                shortOnly.copy(stages = shortTimeline).minutes(StageType.AWAKE),
            ),
    )
}

private fun checkedNormalize(
    primary: SleepSession,
    overlays: List<Stage>,
): List<Stage> =
    try {
        normalizeOverlays(primary, overlays)
    } catch (_: IllegalArgumentException) {
        throw Hold("Invalid sleep overlay bounds or stage limit; no import")
    }

private fun overlayStages(
    sleep: JSONObject,
    name: String,
    type: StageType,
): ParsedOverlays {
    if (sleep.isNull(name)) return ParsedOverlays(emptyList(), 0)
    val raw = checkedOverlayArray(sleep, name)
    val entries = (0 until raw.length()).map { raw.optJSONObject(it) ?: throw Hold("Invalid sleep overlay entry") }
    // The collection defines the interval semantics. The child type is redundant metadata.
    val unexpected = entries.count { type == StageType.AWAKE && it.optString("type") != "AWAKE" }
    val stages = entries.map { parseOverlay(it, type) }.sortedWith(compareBy({ it.start }, { it.end }))
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
