package io.github.aimindseye.sablehealthbridge.core

import java.time.Instant
import java.util.TreeMap

/**
 * Health Connect has one stage per instant. Out-of-bed takes precedence over
 * short awake intervals, then the original stage. Never sum overlapping overlays.
 */
fun normalizeOverlays(
    session: SleepSession,
    overlays: List<Stage>,
): List<Stage> {
    require(validSession(session, session.end))
    require(overlays.size <= MAX_STAGES)
    if (overlays.isEmpty()) return session.stages
    val events = TreeMap<Instant, OverlayDelta>()
    overlays.forEach { overlay ->
        require(overlay.start >= session.start && overlay.end <= session.end && overlay.start < overlay.end)
        val delta =
            when (overlay.type) {
                StageType.AWAKE -> OverlayDelta(awake = 1)
                StageType.OUT_OF_BED -> OverlayDelta(outOfBed = 1)
                else -> throw IllegalArgumentException("Unsupported overlay stage")
            }
        events[overlay.start] = events.getOrDefault(overlay.start, OverlayDelta()) + delta
        events[overlay.end] = events.getOrDefault(overlay.end, OverlayDelta()) - delta
    }
    val sweep = OverlaySweep(events)
    val stages = session.stages.flatMap(sweep::split)
    require(stages.size <= MAX_STAGES)
    return stages
}

private data class OverlayDelta(
    val awake: Int = 0,
    val outOfBed: Int = 0,
) {
    operator fun plus(other: OverlayDelta): OverlayDelta =
        OverlayDelta(awake + other.awake, outOfBed + other.outOfBed)

    operator fun minus(other: OverlayDelta): OverlayDelta =
        OverlayDelta(awake - other.awake, outOfBed - other.outOfBed)

    fun effective(primary: StageType): StageType =
        when {
            outOfBed > 0 -> StageType.OUT_OF_BED
            awake > 0 -> StageType.AWAKE
            else -> primary
        }
}

private class OverlaySweep(
    private val events: TreeMap<Instant, OverlayDelta>,
) {
    private var active = OverlayDelta()

    fun split(stage: Stage): List<Stage> {
        val parts = mutableListOf<Stage>()
        var start = stage.start
        // Include events at the start, but leave events at the end for the next stage.
        events.subMap(stage.start, true, stage.end, false).forEach { (at, delta) ->
            if (start < at) appendFragment(parts, Stage(start, at, active.effective(stage.type)))
            active += delta
            start = at
        }
        appendFragment(parts, Stage(start, stage.end, active.effective(stage.type)))
        return parts
    }
}

private fun appendFragment(
    parts: MutableList<Stage>,
    fragment: Stage,
) {
    val previous = parts.lastOrNull()
    if (previous != null && previous.type == fragment.type) {
        parts[parts.lastIndex] = previous.copy(end = fragment.end)
    } else {
        parts.add(fragment)
    }
}
