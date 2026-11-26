package io.github.aimindseye.sablehealthbridge.core

import org.junit.Test
import java.time.Instant

class SleepSourcePolicyTest {
    private val start = Instant.parse("2026-11-01T05:00:00Z")
    private val end = start.plusSeconds(3600)
    private val stage = Stage(start, end, StageType.LIGHT)
    private val session = SleepSession("fixture-source", start, end, null, null, listOf(stage))
    private val owned = ExistingSleep("owned", BRIDGE_PACKAGE, session.key, 3, start, end, session.stages)

    @Test
    fun editedBoundsRequireExplicitEditAndRevisionWithoutForeignOverlap() {
        val revised = session.copy(end = end.plusSeconds(60))
        val source = sourceInfo()
        val noEdit = revised.copy(sourceInfo = source)
        val noRevision = revised.copy(sourceInfo = source.copy(manuallyEdited = true, updatedAt = null))
        val edited = revised.copy(sourceInfo = source.copy(manuallyEdited = true))
        for (candidate in listOf(revised, noEdit, noRevision)) {
            check(decide(candidate, listOf(owned)).action == Action.CONFLICT)
        }
        check(decide(edited, listOf(owned)) == Decision(Action.UPDATE, owned.id, 4))
        val external = owned.copy(id = "external", origin = "example.external", clientId = null)
        check(decide(edited, listOf(owned, external)).action == Action.CONFLICT)
    }

    @Test
    fun sessionOnlyCannotDowngradeDetailedRecordAndBoundsStayValid() {
        val basic = session.copy(stages = emptyList())
        check(decide(basic, listOf(owned)).action == Action.CONFLICT)
        val unavailable = owned.copy(stages = null)
        check(decide(basic, listOf(unavailable)) == Decision(Action.UPDATE, owned.id, 4))
        check(decide(basic, listOf(owned.copy(stages = emptyList()))).action == Action.ALREADY_IMPORTED)
        val tooEarly = stage.copy(start = start.minusSeconds(1))
        val tooLate = stage.copy(end = end.plusSeconds(1))
        check(!validSession(session.copy(stages = listOf(tooEarly)), end))
        check(!validSession(session.copy(stages = listOf(tooLate)), end))
        check(validSession(basic, end))
    }

    @Test
    fun sourceSummaryAndAwakeningsSurviveTimelineNormalization() {
        val source = sourceInfo()
        val awake = Stage(start.plusSeconds(60), start.plusSeconds(120), StageType.AWAKE)
        val out = Stage(start.plusSeconds(180), start.plusSeconds(240), StageType.OUT_OF_BED)
        val sourceWithOverlays = source.copy(shortAwakenings = listOf(awake), outOfBedSegments = listOf(out))
        val summary = requireNotNull(sourceWithOverlays.summary)
        check(summary.minutesAsleep == 60L && summary.minutesAwake == 0L)
        check(summary.minutesInSleepPeriod == 60L && summary.minutesToFallAsleep == null)
        check(summary.minutesAfterWakeUp == null)
        val detail = summary.stages.single()
        check(detail.type == "LIGHT" && detail.minutes == 60L && detail.count == 1L)
        val imported = session.copy(stages = normalizeOverlays(session, listOf(out)), sourceInfo = sourceWithOverlays)
        val info = OverlayInfo(1, 1, session.asleepMinutes(), summary.minutesAsleep, summary.minutesAwake, 1, 1)
        val preview = imported.copy(overlayInfo = info)
        check(preview.asleepMinutes() == 59L && preview.minutes(StageType.OUT_OF_BED) == 1L)
        check(preview.sourceInfo?.primaryStages == listOf(stage))
        check(preview.sourceInfo?.shortAwakenings == listOf(awake))
        check(preview.sourceInfo?.outOfBedSegments == listOf(out))
        check(preview.overlayInfo?.shortAwakeningMinutes == 1L)
        check(preview.overlayInfo?.unexpectedAwakeningTypes == 1)
    }

    @Test
    fun unknownAndAwakeInBedAreNotCountedAsSleep() {
        val types = StageType.entries
        val stages = types.mapIndexed { index, type ->
            Stage(start.plusSeconds(index * 60L), start.plusSeconds((index + 1) * 60L), type)
        }
        val mixed = session.copy(stages = stages)
        check(validSession(mixed, end))
        check(mixed.asleepMinutes() == 4L)
        check(mixed.minutes(StageType.UNKNOWN) == 1L && mixed.minutes(StageType.AWAKE_IN_BED) == 1L)
    }

    private fun sourceInfo(): SourceSleepInfo =
        SourceSleepInfo(
            type = "STAGES",
            stagesStatus = "SUCCEEDED",
            processed = true,
            mainSleep = true,
            nap = false,
            manuallyEdited = false,
            createdAt = start,
            updatedAt = end,
            summary = SourceSleepSummary(60, 0, 60, null, null, listOf(SourceStageSummary("LIGHT", 60, 1))),
            primaryStages = listOf(stage),
            sourceStageTypes = listOf("LIGHT"),
        )
}
