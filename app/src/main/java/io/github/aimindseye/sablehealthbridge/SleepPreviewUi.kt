package io.github.aimindseye.sablehealthbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.aimindseye.sablehealthbridge.core.OverlayInfo
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.SourceSleepInfo
import io.github.aimindseye.sablehealthbridge.core.StageType
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val AWAKE_ARGB = 0xFFD38C9D
private const val LIGHT_ARGB = 0xFF54A9DB
private const val DEEP_ARGB = 0xFF7175D2
private const val REM_ARGB = 0xFFB19BD8
private const val OUT_OF_BED_ARGB = 0xFFDBA15B
private const val ASLEEP_ARGB = 0xFF548CB9
private const val UNKNOWN_ARGB = 0xFF8A8A8A
private val stagePalette =
    listOf(
        Color(AWAKE_ARGB),
        Color(LIGHT_ARGB),
        Color(DEEP_ARGB),
        Color(REM_ARGB),
        Color(OUT_OF_BED_ARGB),
        Color(ASLEEP_ARGB),
        Color(UNKNOWN_ARGB),
        Color(AWAKE_ARGB),
    )
private const val MINUTES_PER_HOUR = 60
private val uncertainSourceTypes = setOf("RESTLESS", "OTHER", "MISSING", "SLEEP_STAGE_TYPE_UNSPECIFIED")

@Composable
internal fun SleepCard(preview: Preview) {
    val sleep = preview.session
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    Card(Modifier.fillMaxWidth().testTag("sleep-preview")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sessionLabel(sleep), style = MaterialTheme.typography.labelSmall)
            val hours = sleep.asleepMinutes() / MINUTES_PER_HOUR
            val minutes = sleep.asleepMinutes() % MINUTES_PER_HOUR
            val headline =
                if (sleep.stages.isEmpty()) {
                    "Session only · asleep unavailable"
                } else {
                    "${hours}h ${minutes}m known asleep"
                }
            Text(headline, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${format.format(sleep.start)} – ${format.format(sleep.end)}",
                style = MaterialTheme.typography.bodySmall,
            )
            StageBar(sleep)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf(StageType.AWAKE, StageType.LIGHT, StageType.DEEP, StageType.REM).forEach { stage ->
                    Column {
                        Text(
                            stage.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text("${sleep.minutes(stage)}m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SourceDetails(sleep)
            sleep.overlayInfo?.let { OverlayDetails(sleep, it) }
            Text(actionLabel(preview.decision.action), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sessionLabel(sleep: SleepSession): String {
    val source = sleep.sourceInfo
    return when {
        source?.nap == true -> "Nap · Google wearable"
        source?.mainSleep == true -> "Main sleep · Google wearable"
        else -> "Sleep session · Google wearable"
    }
}

@Composable
private fun SourceDetails(sleep: SleepSession) {
    val source = sleep.sourceInfo ?: return
    Text("Source: ${source.type} · ${source.stagesStatus}", style = MaterialTheme.typography.bodySmall)
    if (source.processed == null) {
        Text("Processing status unavailable", style = MaterialTheme.typography.bodySmall)
    }
    ExtraStageDetails(sleep, source)
    val summary = source.summary
    if (summary != null && sleep.overlayInfo == null) {
        Text(
            "Google summary: ${summary.minutesAsleep ?: "—"}m asleep · ${summary.minutesAwake ?: "—"}m awake",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (summary != null) {
        Text(
            "In period: ${summary.minutesInSleepPeriod ?: "—"}m · latency: ${summary.minutesToFallAsleep ?: "—"}m",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("After wake: ${summary.minutesAfterWakeUp ?: "—"}m", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExtraStageDetails(
    sleep: SleepSession,
    source: SourceSleepInfo,
) {
    val extra = listOf(StageType.ASLEEP, StageType.UNKNOWN)
    if (sleep.stages.any { it.type in extra }) {
        Text(
            "Unspecified asleep: ${sleep.minutes(StageType.ASLEEP)}m · Unknown: ${sleep.minutes(StageType.UNKNOWN)}m",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (source.sourceStageTypes.any { it in uncertainSourceTypes }) {
        Text("Restless or unfamiliar stages mapped to unknown", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OverlayDetails(
    sleep: SleepSession,
    info: OverlayInfo,
) {
    Text(
        "Awake overlays: ${info.shortAwakenings} · Out of bed: ${sleep.minutes(StageType.OUT_OF_BED)}m",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Short awakenings: ${info.shortAwakeningMinutes}m · source layer, not subtracted",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Primary stages: ${info.primaryAsleepMinutes}m asleep · Import: ${sleep.asleepMinutes()}m",
        style = MaterialTheme.typography.bodySmall,
    )
    if (info.unexpectedAwakeningTypes > 0) {
        Text(
            "Review: ${info.unexpectedAwakeningTypes} awakening type(s) inferred from Google collection",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val asleep = info.reportedAsleepMinutes
    val awake = info.reportedAwakeMinutes
    if (asleep != null || awake != null) {
        Text(
            "Google summary: ${asleep ?: "—"}m asleep · ${awake ?: "—"}m awake",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StageBar(session: SleepSession) {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val total = Duration.between(session.start, session.end).toNanos().toDouble()
        session.stages.forEach { stage ->
            val start = (Duration.between(session.start, stage.start).toNanos() / total * size.width).toFloat()
            val width = (Duration.between(stage.start, stage.end).toNanos() / total * size.width).toFloat()
            drawRect(stagePalette[stage.type.ordinal], Offset(start, 0f), Size(width, size.height))
        }
    }
}
