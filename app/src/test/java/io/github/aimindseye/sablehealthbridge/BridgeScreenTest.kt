package io.github.aimindseye.sablehealthbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.aimindseye.sablehealthbridge.core.Action
import io.github.aimindseye.sablehealthbridge.core.Decision
import io.github.aimindseye.sablehealthbridge.core.OverlayInfo
import io.github.aimindseye.sablehealthbridge.core.StageType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BridgeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val screen = mutableStateOf(ScreenState())
    private val actions = mutableListOf<String>()

    private fun mount() {
        compose.setContent {
            BridgeTheme {
                BridgeScreen(
                    screen.value,
                    ScreenActions(
                        { actions.add("authorize") },
                        { actions.add("permission") },
                        { actions.add("preview") },
                        { actions.add("apply") },
                        { actions.add("forget") },
                        { actions.add("about") },
                    ),
                )
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h411dp-notnight")
    fun lightManualFlow() = manualFlow("light")

    @Test
    @Config(qualifiers = "w411dp-h411dp-night")
    fun darkManualFlow() = manualFlow("dark")

    private fun manualFlow(theme: String) {
        mount()
        compose.onNodeWithTag("google-connect").performClick()
        compose.onNodeWithTag("health-connect").performClick()
        compose.onNodeWithTag("preview-sleep").assertIsNotEnabled()
        compose.runOnIdle { screen.value = ScreenState(connected = true, permissions = true) }
        compose.onNodeWithTag("preview-sleep").performClick()
        compose.runOnIdle {
            screen.value = screen.value.copy(preview = Preview(testSleep(), Decision(Action.INSERT)))
        }
        compose.onNodeWithTag("sleep-preview").assertIsDisplayed()
        compose.onNodeWithTag("apply-sleep").assertIsDisplayed()
        capture("$theme-preview")
        compose.onNodeWithTag("apply-sleep").performClick()
        compose.onNodeWithText("Check again").performClick()
        compose.runOnIdle { screen.value = screen.value.copy(preview = null) }
        compose.onNodeWithTag("google-connect").performClick()
        compose.onNodeWithText("About").performClick()
        compose.runOnIdle {
            check(actions == listOf("authorize", "permission", "preview", "apply", "preview", "forget", "about"))
        }
    }

    @Test
    fun conflictAndBusyDoNotOfferWrites() {
        mount()
        compose.runOnIdle {
            screen.value = ScreenState(connected = true, preview = Preview(testSleep(), Decision(Action.CONFLICT)))
        }
        compose.onNodeWithTag("sleep-preview").assertIsDisplayed()
        compose.onNodeWithTag("apply-sleep").assertDoesNotExist()
        capture("conflict")
        compose.runOnIdle { screen.value = screen.value.copy(busy = true, preview = null) }
        compose.onNodeWithTag("preview-sleep").assertIsNotEnabled()
        compose.onNodeWithTag("google-connect").assertIsNotEnabled()
        compose.onNodeWithTag("health-connect").assertIsNotEnabled()
        capture("busy")
    }

    @Test
    fun overlayPreviewExplainsNormalizedAndSourceTotals() {
        mount()
        val original = testSleep()
        val session =
            original.copy(
                stages = original.stages.map { it.copy(type = StageType.OUT_OF_BED) },
                overlayInfo = OverlayInfo(1, 1, original.asleepMinutes(), 47, null, 1),
            )
        compose.runOnIdle {
            screen.value = ScreenState(connected = true, preview = Preview(session, Decision(Action.INSERT)))
        }
        val warning = "Review: 1 awakening type(s) inferred from Google collection"
        compose.onNodeWithText(warning).assertExists()
        compose.onNodeWithText("Google summary: 47m asleep · —m awake").assertExists()
        compose.onNodeWithText("Primary stages: ${original.asleepMinutes()}m asleep · Import: 0m").assertExists()
        compose.onNodeWithTag("apply-sleep").assertIsDisplayed()
        capture("overlays")
        val withoutSummary = OverlayInfo(1, 1, original.asleepMinutes(), null, null)
        val conflictSession = session.copy(overlayInfo = withoutSummary)
        val conflictPreview = Preview(conflictSession, Decision(Action.CONFLICT))
        compose.runOnIdle {
            screen.value = screen.value.copy(preview = conflictPreview)
        }
        compose.onNodeWithTag("apply-sleep").assertDoesNotExist()
        compose.onNodeWithText(warning).assertDoesNotExist()
    }

    @Test
    fun allDecisionLabelsAreDistinct() {
        val labels = Action.entries.map(::actionLabel)
        check(labels.size == labels.toSet().size)
        check(actionLabel(Action.EXTERNAL_MATCH).contains("no change"))
        check(actionLabel(Action.REMOVE_OWN_DUPLICATE).contains("Bridge copy"))
    }

    private fun capture(name: String) {
        val image =
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap
            }
        val directory = File("build/reports/sbr1-visual")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
