package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.domain.OvernightReplayEngine
import com.example.domain.OvernightReportMode
import com.example.domain.WatchInvestigationConfig
import com.example.ui.components.OvernightMorningReportCard
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.VoltTraceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OvernightMorningReportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun morningReportPassesTenSecondTestAndTriggersReplayAndWatchActions() {
        val syntheticReport = OvernightReplayEngine.createSyntheticPreviewReport(OvernightReportMode.OVERNIGHT_STANDBY)
        var replayClicked = false
        var watchClicked = false

        composeRule.setContent {
            VoltTraceTheme(themeMode = ThemeMode.DARK) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OvernightMorningReportCard(
                        report = syntheticReport,
                        watchConfig = WatchInvestigationConfig(),
                        onOpenReplay = { replayClicked = true },
                        onToggleWatch = { watchClicked = true }
                    )
                }
            }
        }

        // 1. How much battery was lost
        composeRule.onNodeWithTag("overnight_loss_pct").assertIsDisplayed()
        composeRule.onNodeWithTag("overnight_loss_mah").assertIsDisplayed()
        // 2. Personal baseline comparison
        composeRule.onNodeWithTag("overnight_baseline_pill").assertIsDisplayed()
        // 3. Which part of the night mattered
        composeRule.onNodeWithTag("overnight_key_segment_card").assertIsDisplayed()
        // 4. One-Tap Replay & Watch-for-this-again actions
        composeRule.onNodeWithTag("overnight_replay_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("overnight_watch_button").performScrollTo().performClick()

        assertTrue("One-Tap Replay action should fire", replayClicked)
        assertTrue("Watch for this again action should fire", watchClicked)
    }

    @Test
    fun displaysBeforeYouPluggedInFallbackWhenChargedOvernight() {
        val fallbackReport = OvernightReplayEngine.createSyntheticPreviewReport(OvernightReportMode.BEFORE_PLUGGED_IN)

        composeRule.setContent {
            VoltTraceTheme(themeMode = ThemeMode.DARK) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OvernightMorningReportCard(
                        report = fallbackReport,
                        watchConfig = WatchInvestigationConfig(),
                        onOpenReplay = {},
                        onToggleWatch = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("Before you plugged in").assertIsDisplayed()
        composeRule.onNodeWithText("Replay Window").performScrollTo().assertIsDisplayed()
    }
}
