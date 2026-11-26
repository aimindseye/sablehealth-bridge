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

internal fun applySleepOverlays(
    sleep: JSONObject,
    primary: SleepSession,
): SleepSession {
    val awake = overlayStages(sleep, "shortAwakenings", StageType.AWAKE)
    val outOfBed = overlayStages(sleep, "outOfBedSegments", StageType.OUT_OF_BED)
    if (awake.isEmpty() && outOfBed.isEmpty()) return primary
    val normalized =
        try {
            normalizeOverlays(primary, awake + outOfBed)
        } catch (_: IllegalArgumentException) {
            throw Hold("Invalid sleep overlay bounds or stage limit; no import")
        }
    val summary = sleep.optJSONObject("summary")
    return primary.copy(
        stages = normalized,
        overlayInfo =
            OverlayInfo(
                awake.size,
                outOfBed.size,
                primary.asleepMinutes(),
                summaryMinutes(summary, "minutesAsleep"),
                summaryMinutes(summary, "minutesAwake"),
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
): List<Stage> {
    if (!sleep.has(name)) return emptyList()
    val raw = checkedOverlayArray(sleep, name)
    return (0 until raw.length()).map { index ->
        val item = raw.optJSONObject(index) ?: throw Hold("Invalid sleep overlay entry")
        parseOverlay(item, type)
    }
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
    if (type == StageType.AWAKE && item.optString("type") != "AWAKE") {
        throw Hold("Invalid short awakening type")
    }
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
