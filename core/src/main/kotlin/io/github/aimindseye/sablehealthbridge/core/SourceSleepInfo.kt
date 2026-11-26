package io.github.aimindseye.sablehealthbridge.core

import java.time.Instant

data class SourceStageSummary(
    val type: String,
    val minutes: Long?,
    val count: Long?,
)

data class SourceSleepSummary(
    val minutesAsleep: Long?,
    val minutesAwake: Long?,
    val minutesInSleepPeriod: Long?,
    val minutesToFallAsleep: Long?,
    val minutesAfterWakeUp: Long?,
    val stages: List<SourceStageSummary>,
)

/** In-memory source details, never written to files or diagnostics. */
data class SourceSleepInfo(
    val type: String,
    val stagesStatus: String,
    val processed: Boolean?,
    val mainSleep: Boolean,
    val nap: Boolean,
    val manuallyEdited: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val summary: SourceSleepSummary?,
    val primaryStages: List<Stage>,
    val sourceStageTypes: List<String>,
    val shortAwakenings: List<Stage> = emptyList(),
    val outOfBedSegments: List<Stage> = emptyList(),
    val recordingMethod: String = "MISSING",
    val formFactor: String = "MISSING",
)
