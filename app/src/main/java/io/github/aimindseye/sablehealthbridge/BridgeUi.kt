package io.github.aimindseye.sablehealthbridge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.OverlayInfo
import io.github.aimindseye.sablehealthbridge.core.SleepSession
import io.github.aimindseye.sablehealthbridge.core.StageType
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val DARK_PRIMARY_ARGB = 0xFF8BCBF2
private const val LIGHT_PRIMARY_ARGB = 0xFF17658E
private const val AWAKE_ARGB = 0xFFD38C9D
private const val LIGHT_ARGB = 0xFF54A9DB
private const val DEEP_ARGB = 0xFF7175D2
private const val REM_ARGB = 0xFFB19BD8
private const val OUT_OF_BED_ARGB = 0xFFDBA15B
private val darkPrimary = Color(DARK_PRIMARY_ARGB)
private val lightPrimary = Color(LIGHT_PRIMARY_ARGB)
private val stagePalette =
    listOf(
        Color(AWAKE_ARGB),
        Color(LIGHT_ARGB),
        Color(DEEP_ARGB),
        Color(REM_ARGB),
        Color(OUT_OF_BED_ARGB),
    )
private const val MINUTES_PER_HOUR = 60

@Composable
internal fun BridgeTheme(content: @Composable () -> Unit) {
    val scheme =
        if (isSystemInDarkTheme()) {
            darkColorScheme(primary = darkPrimary)
        } else {
            lightColorScheme(primary = lightPrimary)
        }
    MaterialTheme(colorScheme = scheme, content = content)
}

internal data class ScreenActions(
    val authorize: () -> Unit,
    val permission: () -> Unit,
    val preview: () -> Unit,
    val apply: () -> Unit,
    val forget: () -> Unit,
    val about: () -> Unit,
)

@Composable
internal fun BridgeScreen(
    state: ScreenState,
    actions: ScreenActions,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text("Sable Health Bridge", style = MaterialTheme.typography.titleMedium)
                    Text("Sleep only · SB-R1", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = actions.about, modifier = Modifier.heightIn(min = 48.dp)) { Text("About") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConnectionCard(state, actions.authorize, actions.permission, actions.forget)
                Text(
                    state.message,
                    modifier = Modifier.padding(4.dp).testTag("bridge-status"),
                    style = MaterialTheme.typography.bodySmall,
                )
                state.preview?.let { SleepCard(it) }
            }
            val item = state.preview
            if (item?.decision?.canWrite == true) {
                Button(
                    onClick = actions.apply,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("apply-sleep"),
                ) { Text(actionLabel(item.decision.action)) }
                TextButton(onClick = actions.preview, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Check again")
                }
            } else {
                Button(
                    onClick = actions.preview,
                    enabled = state.connected && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("preview-sleep"),
                ) {
                    Text("Preview latest sleep")
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ScreenState,
    authorize: () -> Unit,
    permission: () -> Unit,
    forget: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text("Google Health", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.connected) "Authorized" else "Not connected",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = if (state.connected) forget else authorize,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("google-connect"),
                ) {
                    Text(if (state.connected) "Clear session" else "Connect")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text("Health Connect", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.permissions) "Sleep access checked" else "Sleep read / write",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(
                    onClick = permission,
                    enabled = !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("health-connect"),
                ) { Text("Permissions") }
            }
        }
    }
}

@Composable
private fun SleepCard(preview: Preview) {
    val sleep = preview.session
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    Card(Modifier.fillMaxWidth().testTag("sleep-preview")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Latest main sleep · Google wearable", style = MaterialTheme.typography.labelSmall)
            val hours = sleep.asleepMinutes() / MINUTES_PER_HOUR
            val minutes = sleep.asleepMinutes() % MINUTES_PER_HOUR
            Text("${hours}h ${minutes}m asleep", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${format.format(sleep.start)} – ${format.format(sleep.end)}",
                style = MaterialTheme.typography.bodySmall,
            )
            StageBar(sleep)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StageType.entries.filter { it != StageType.OUT_OF_BED }.forEach { stage ->
                    Column {
                        Text(
                            stage.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text("${sleep.minutes(stage)}m", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            sleep.overlayInfo?.let { OverlayDetails(sleep, it) }
            Text(actionLabel(preview.decision.action), style = MaterialTheme.typography.bodySmall)
        }
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
        "Primary stages: ${info.primaryAsleepMinutes}m asleep · Import: ${sleep.asleepMinutes()}m",
        style = MaterialTheme.typography.bodySmall,
    )
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
        val total = Duration.between(session.start, session.end).toMillis().toFloat()
        session.stages.forEach { stage ->
            val start = Duration.between(session.start, stage.start).toMillis() / total * size.width
            val width = Duration.between(stage.start, stage.end).toMillis() / total * size.width
            drawRect(stagePalette[stage.type.ordinal], Offset(start, 0f), Size(width, size.height))
        }
    }
}

internal fun actionLabel(action: Action): String =
    when (action) {
        Action.INSERT -> "Import sleep"
        Action.UPDATE -> "Update Bridge sleep"
        Action.ALREADY_IMPORTED -> "Already imported · no change"
        Action.EXTERNAL_MATCH -> "Matching external sleep · no change"
        Action.REMOVE_OWN_DUPLICATE -> "Remove matching Bridge copy"
        Action.CONFLICT -> "Overlapping or different sleep · no change"
    }

@Composable
internal fun PrivacyScreen(back: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Privacy & about", style = MaterialTheme.typography.titleLarge)
            Text(
                "SB-R1 · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                    BuildConfig.SOURCE_REVISION,
            )
            Text(
                "Reads only Google Health sleep. Your account authorization is handled by Google Play services. " +
                    "The Bridge does not store tokens or sleep records in app files. No analytics or separate backend.",
            )
            Text(
                "Sleep read access detects overlaps. Sleep write access imports the explicitly reviewed record. " +
                    "Only the Bridge's own matching copy can be removed; other apps' records are never altered.",
            )
            Text(
                "Google Health may export a duplicate later. Preview again to detect it. Clear session removes " +
                    "the app's in-memory access token, not the Google account grant. " +
                    "Revoke access in Google account settings.",
            )
            TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
    }
}
