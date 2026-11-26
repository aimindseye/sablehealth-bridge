package io.github.aimindseye.sablehealthbridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(SyncUiState())
    private val calls = mutableListOf<String>()

    @Test
    fun explicitConsentAndDisabledStateAndManualRecovery() {
        compose.setContent {
            BridgeTheme {
                SyncScreen(
                    state.value,
                    SyncActions(
                        { calls.add("enable") },
                        { calls.add("disable") },
                        { calls.add("now") },
                        { calls.add(it.toString()) },
                        { calls.add("manual") },
                        { calls.add("about") },
                    ),
                )
            }
        }
        compose.onNodeWithTag("sync-now").assertIsNotEnabled()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithTag("sync-toggle").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { check(calls == listOf("15")) }
        compose.onNodeWithTag("sync-toggle").performClick()
        compose.onNodeWithText("Allow automatic sync").performClick()
        compose.runOnIdle {
            state.value = SyncUiState(true, 15, SyncReport(SyncStatus.COMPLETE, 2, 1, 0, 123))
        }
        compose.onNodeWithText("Automatic sync on").assertIsDisplayed()
        compose.onNodeWithTag("sync-now").performClick()
        compose.onNodeWithTag("sync-toggle").performClick()
        compose.onNodeWithTag("sync-manual").performClick()
        compose.onNodeWithText("About").performClick()
        compose.runOnIdle { check(calls == listOf("15", "enable", "now", "disable", "manual", "about")) }
    }

    @Test
    fun allStatusLabelsAreExplicitAndDistinct() {
        val labels = SyncStatus.entries.map(::syncStatusLabel)
        check(labels.size == labels.toSet().size)
        check(syncStatusLabel(SyncStatus.WRITE_UNCERTAIN).contains("manual review"))
    }
}
