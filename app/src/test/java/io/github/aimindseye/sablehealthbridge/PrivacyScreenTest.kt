package io.github.aimindseye.sablehealthbridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h411dp")
class PrivacyScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<PrivacyActivity>()

    @Test
    fun productionPrivacyEntryPointShowsBuildAndBoundaries() {
        compose.onNodeWithText("Privacy & about").assertIsDisplayed()
        val version = "SB-R1 · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val revision = "$version · ${BuildConfig.SOURCE_REVISION}"
        compose.onNodeWithText(revision).assertIsDisplayed()
        compose.onNodeWithText("Reads only Google Health sleep.", substring = true).assertIsDisplayed()
    }
}
