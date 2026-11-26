package io.github.aimindseye.sablehealthbridge.core

import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SleepOverlaysTest {
    private val start = Instant.parse("2026-11-01T05:00:00Z")
    private val base =
        SleepSession(
            "fixture",
            start,
            start.plusSeconds(60),
            ZoneOffset.ofHours(-4),
            ZoneOffset.ofHours(-5),
            listOf(stage(0, 30, StageType.LIGHT), stage(30, 60, StageType.REM)),
        )

    @Test
    fun unionsAwakeAndOutOfBedAcrossPrimaryBoundaries() {
        val overlays =
            listOf(
                stage(35, 50, StageType.OUT_OF_BED),
                stage(10, 40, StageType.AWAKE),
                stage(20, 35, StageType.AWAKE),
                stage(25, 45, StageType.OUT_OF_BED),
            )
        val expected =
            listOf(
                stage(0, 10, StageType.LIGHT),
                stage(10, 25, StageType.AWAKE),
                stage(25, 30, StageType.OUT_OF_BED),
                stage(30, 50, StageType.OUT_OF_BED),
                stage(50, 60, StageType.REM),
            )
        val actual = normalizeOverlays(base, overlays)
        check(actual == expected)
        check(actual == normalizeOverlays(base, overlays.reversed() + overlays))
        check(validSession(base.copy(stages = actual), base.end))
    }

    @Test
    fun preservesBaseBoundariesAndEmptyInput() {
        check(normalizeOverlays(base, emptyList()) == base.stages)
        val awake = base.copy(stages = listOf(stage(0, 60, StageType.AWAKE)))
        check(normalizeOverlays(awake, listOf(stage(10, 20, StageType.AWAKE))) == awake.stages)
        val full = normalizeOverlays(base, listOf(stage(0, 60, StageType.OUT_OF_BED)))
        check(full == listOf(stage(0, 30, StageType.OUT_OF_BED), stage(30, 60, StageType.OUT_OF_BED)))
        val touching = listOf(stage(10, 20, StageType.AWAKE), stage(20, 30, StageType.AWAKE))
        check(normalizeOverlays(base, touching) == listOf(stage(0, 10, StageType.LIGHT), stage(10, 30, StageType.AWAKE), base.stages.last()))
    }

    @Test
    fun rejectsInvalidOverlayAndBaseBounds() {
        val invalid =
            listOf(
                stage(-1, 10, StageType.AWAKE),
                stage(50, 61, StageType.OUT_OF_BED),
                stage(20, 20, StageType.AWAKE),
                stage(20, 10, StageType.AWAKE),
                stage(0, 10, StageType.REM),
            )
        invalid.forEach {
            check(runCatching { normalizeOverlays(base, listOf(it)) }.exceptionOrNull() is IllegalArgumentException)
        }
        check(runCatching { normalizeOverlays(base.copy(stages = emptyList()), emptyList()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun capsInputAndOutputAndCountsOnlyAsleepTime() {
        val excessive = List(MAX_STAGES + 1) { stage(0, 1, StageType.AWAKE) }
        check(runCatching { normalizeOverlays(base, excessive) }.exceptionOrNull() is IllegalArgumentException)
        val end = start.plusSeconds(40000)
        val long = base.copy(end = end, stages = listOf(Stage(start, end, StageType.LIGHT)))
        val split = List(MAX_STAGES / 2 + 1) { index -> stage(index * 3L + 1, index * 3L + 2, StageType.AWAKE) }
        check(runCatching { normalizeOverlays(long, split) }.exceptionOrNull() is IllegalArgumentException)
        val minutes =
            base.copy(
                end = start.plusSeconds(180),
                stages =
                    listOf(
                        stage(0, 60, StageType.OUT_OF_BED),
                        stage(60, 120, StageType.AWAKE),
                        stage(120, 180, StageType.REM),
                    ),
            )
        check(minutes.asleepMinutes() == 1L && minutes.minutes(StageType.OUT_OF_BED) == 1L)
    }

    @Test
    fun aggregatesFractionalIntervalsBeforeRounding() {
        val middle = start.plusMillis(30500)
        val session =
            base.copy(
                end = start.plusSeconds(61),
                stages = listOf(Stage(start, middle, StageType.REM), Stage(middle, start.plusSeconds(61), StageType.REM)),
            )
        check(session.asleepMinutes() == 1L && session.minutes(StageType.REM) == 1L)
    }

    private fun stage(
        from: Long,
        to: Long,
        type: StageType,
    ): Stage = Stage(start.plusSeconds(from), start.plusSeconds(to), type)
}
