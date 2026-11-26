package io.github.aimindseye.sablehealthbridge

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.WorkInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProbeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val queued = mutableStateOf(false)
    private val busy = mutableStateOf(false)
    private val actions = mutableListOf<String>()

    @Test
    fun readOnlyActionsAndBusyState() {
        compose.setContent {
            BridgeTheme {
                ProbeScreen(
                    "Read only",
                    busy.value,
                    queued.value,
                    ProbeActions(
                        { actions.add("permission") },
                        { actions.add("delay:$it") },
                        { actions.add("cancel") },
                        { actions.add("back") },
                    ),
                )
            }
        }
        compose.onNodeWithText("Background permissions").performClick()
        compose.onNodeWithTag("probe-short").assertIsDisplayed().performClick()
        compose.onNodeWithTag("probe-long").assertIsDisplayed().performClick()
        compose.onNodeWithTag("probe-cancel").assertIsNotEnabled()
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("probe-short").assertIsNotEnabled()
        compose.onNodeWithTag("probe-long").assertIsNotEnabled()
        compose.runOnIdle {
            busy.value = false
            queued.value = true
        }
        compose.onNodeWithTag("probe-short").assertIsNotEnabled()
        compose.onNodeWithTag("probe-cancel").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle {
            check(actions == listOf("permission", "delay:2", "delay:75", "cancel", "back"))
        }
    }

    @Test
    fun statusIsBounded() {
        check(probeStatus(WorkInfo.State.SUCCEEDED, "PASS_READ_ONLY") == "PASS_READ_ONLY")
        check(probeStatus(WorkInfo.State.FAILED, "ACCOUNT_CHANGED") == "ACCOUNT_CHANGED")
        check(probeStatus(WorkInfo.State.FAILED, null) == "READ_NOT_CONFIRMED")
        check(probeStatus(WorkInfo.State.FAILED, "arbitrary") == "READ_NOT_CONFIRMED")
        check(probeStatus(WorkInfo.State.CANCELLED, null) == "Cancelled")
        check(probeStatus(WorkInfo.State.RUNNING, null) == "Background test running")
        check(probeStatus(WorkInfo.State.ENQUEUED, null).startsWith("Scheduled"))
        check(probeStatus(WorkInfo.State.BLOCKED, null).startsWith("Scheduled"))
    }
}
