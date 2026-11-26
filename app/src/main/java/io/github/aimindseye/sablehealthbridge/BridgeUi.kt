package io.github.aimindseye.sablehealthbridge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.aimindseye.sablehealthbridge.core.Action

private const val DARK_PRIMARY_ARGB = 0xFF8BCBF2
private const val LIGHT_PRIMARY_ARGB = 0xFF17658E
private val darkPrimary = Color(DARK_PRIMARY_ARGB)
private val lightPrimary = Color(LIGHT_PRIMARY_ARGB)

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
    val previous: () -> Unit = {},
    val next: () -> Unit = {},
    val sync: (() -> Unit)? = null,
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
                actions.sync?.let { back -> TextButton(onClick = back) { Text("Sync") } }
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
                SessionControls(state, actions)
                state.preview?.let {
                    Text(actionLabel(it.decision.action), style = MaterialTheme.typography.labelMedium)
                    SleepCard(it)
                }
                SchemaDiagnostics(state.diagnostics)
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
                    Text("Preview recent sleep")
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
private fun SessionControls(
    state: ScreenState,
    actions: ScreenActions,
) {
    if (state.sessionCount > 1) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = actions.previous, enabled = !state.busy) { Text("Previous") }
            Text("${state.sessionIndex + 1} / ${state.sessionCount}", modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = actions.next, enabled = !state.busy) { Text("Next") }
        }
    }
    if (state.heldCount > 0) {
        Text("${state.heldCount} session(s) deferred or conflicting", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SchemaDiagnostics(report: String) {
    var expanded by remember { mutableStateOf(false) }
    if (report.isNotBlank()) {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide diagnostics" else "API diagnostics") }
        if (expanded) {
            Text("Select and copy this sanitized schema report.", style = MaterialTheme.typography.labelSmall)
            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall) }
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
                "Sleep read access detects overlaps. Manual import requires review. Automatic sync is opt-in and " +
                    "inserts only new completed sleep. Other apps' records are never altered.",
            )
            Text(
                "Automatic sync retains a salted account binding, opaque import identities, and aggregate status " +
                    "locally. No tokens or raw sleep records are persisted. Stop sync to prevent new automatic writes. " +
                    "An already submitted write may complete. Revoke access in Google account settings.",
            )
            TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
    }
}
