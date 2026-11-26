package io.github.aimindseye.sablehealthbridge

import android.graphics.Bitmap
import android.graphics.Canvas
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(qualifiers = "w411dp-h411dp-notnight")
    fun lightLauncher() = verifyAndCapture("light")

    @Test
    @Config(qualifiers = "w411dp-h411dp-night")
    fun darkLauncher() = verifyAndCapture("dark")

    private fun verifyAndCapture(theme: String) {
        compose.onNodeWithText("Sable Health Bridge").assertIsDisplayed()
        compose.onNodeWithTag("sync-toggle").assertIsDisplayed()
        compose.onNodeWithTag("sync-now").assertIsNotEnabled()
        compose.onNodeWithTag("sync-manual").assertIsDisplayed()
        val image =
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap
            }
        val directory = File("build/reports/sbr1-visual")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$theme-launcher.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        compose.onNodeWithTag("sync-manual").performClick()
        compose.onNodeWithTag("google-connect").assertIsDisplayed()
        compose.onNodeWithTag("health-connect").assertIsDisplayed()
        compose.onNodeWithTag("preview-sleep").assertIsNotEnabled()
    }
}
