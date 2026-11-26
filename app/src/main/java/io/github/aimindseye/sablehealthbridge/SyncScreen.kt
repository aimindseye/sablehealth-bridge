package io.github.aimindseye.sablehealthbridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

internal data class SyncActions(
    val enable: () -> Unit,
    val disable: () -> Unit,
    val now: () -> Unit,
    val interval: (Int) -> Unit,
    val manual: () -> Unit,
    val about: () -> Unit,
)

@Composable
internal fun SyncScreen(
    state: SyncUiState,
    actions: SyncActions,
) {
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(horizontal = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sable Health Bridge", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = actions.about) { Text("About") }
            }
            Text(
                if (state.enabled) "Automatic sync on" else "Automatic sync off",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(syncStatusLabel(state.notice ?: state.report.status), Modifier.testTag("sync-status"))
            SyncCounts(state.report)
            SyncInterval(state, actions.interval)
            Button(
                onClick = { if (state.enabled) actions.disable() else confirm = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync-toggle"),
            ) { Text(if (state.enabled) "Stop sync" else "Enable sync") }
            TextButton(
                onClick = actions.now,
                enabled = state.enabled && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync-now"),
            ) { Text("Sync now") }
            TextButton(
                onClick = actions.manual,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sync-manual"),
            ) { Text("Manual preview / import") }
            SyncHistoryPanel(state.history)
        }
    }
    if (confirm) {
        SyncConsentDialog(
            onConfirm = {
                confirm = false
                actions.enable()
            },
            onCancel = { confirm = false },
        )
    }
}

@Composable
private fun SyncCounts(report: SyncReport) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Text("Imported\n${report.imported}", style = MaterialTheme.typography.bodyMedium)
        Text("Verified\n${report.verified}", style = MaterialTheme.typography.bodyMedium)
        Text("Held\n${report.held}", style = MaterialTheme.typography.bodyMedium)
    }
    Text("Last run: ${syncRunTime(report)}", style = MaterialTheme.typography.labelSmall)
}

private fun syncRunTime(report: SyncReport): String =
    if (report.finishedAt > 0) DateFormat.getDateTimeInstance().format(Date(report.finishedAt)) else "Not yet"

@Composable
private fun SyncHistoryPanel(history: List<SyncReport>) {
    var expanded by remember { mutableStateOf(false) }
    if (history.isNotEmpty()) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide history" else "Sync history") }
        if (expanded) {
            history.forEach { report ->
                Text(syncRunTime(report), style = MaterialTheme.typography.labelMedium)
                Text(
                    "${syncStatusLabel(report.status)} · ${report.imported} imported · ${report.held} held",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SyncInterval(
    state: SyncUiState,
    choose: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        syncIntervals.forEach { minutes ->
            TextButton(
                onClick = { choose(minutes) },
                enabled = !state.enabled && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (state.minutes == minutes) "$minutes min ✓" else "$minutes min") }
        }
    }
}

@Composable
private fun SyncConsentDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enable automatic imports?") },
        text = {
            Text(
                "Allow the Bridge to import new completed Google wearable sleep into Health Connect, " +
                    "including when closed. First 14 days, then 7 days. No automatic updates or deletions. " +
                    "Stop anytime; a write already submitted may finish.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Allow automatic sync") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

internal fun syncStatusLabel(status: SyncStatus): String =
    when (status) {
        SyncStatus.OFF -> "Not scheduled"
        SyncStatus.READY -> "Waiting for sync"
        SyncStatus.RUNNING -> "Sync in progress"
        SyncStatus.COMPLETE -> "Last run complete · held records need review"
        SyncStatus.PAUSED -> "Paused · enable again"
        SyncStatus.AUTH_REQUIRED -> "Google authorization required"
        SyncStatus.ACCOUNT_CHANGED -> "Bound Google account changed · no import"
        SyncStatus.PERMISSION_REQUIRED -> "Sleep and background read access required"
        SyncStatus.UNAVAILABLE -> "Background Health Connect unavailable"
        SyncStatus.FAILED -> "Sync not confirmed · review"
        SyncStatus.WRITE_UNCERTAIN -> "Write not verified · manual review required"
    }
