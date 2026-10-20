package com.k1af.ft8af.flows

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.MainViewModel
import com.k1af.ft8af.database.OperationBand
import com.k1af.ft8af.flows.Flows.waitForMainScreen
import com.k1af.ft8af.rigs.BaseRigOperation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import radio.ks3ckc.ft8af.ui.components.selectBandIndex

/**
 * Flow 2: picking a band in the Band & mode sheet retunes the app, and the
 * BAND & MODE row shows the new frequency.
 */
@RunWith(AndroidJUnit4::class)
class BandChangeFlowTest {

    private val compose = Flows.composeRule()

    @get:Rule
    val rules: TestRule = Flows.chain(compose)

    @Test
    fun changingBand_updatesShownFrequency() {
        compose.waitForMainScreen()
        val originalBand = GeneralVariables.band

        // Hop to 40m, or to 20m if a previous run left the app on 40m, so the
        // label always has to change.
        val onFortyMeters = BaseRigOperation.getMeterFromFreq(originalBand) == "40m"
        val targetBand = if (onFortyMeters) "20m" else "40m"
        val targetHz = if (onFortyMeters) 14_074_000L else 7_074_000L
        val expectedLabel = "${"%.3f".format(java.util.Locale.US, targetHz / 1e6)} MHz · $targetBand"

        try {
            // With the controls drawer on (the default) the BAND & MODE row is
            // inside the collapsed drawer; open it first.
            val expand = compose.onAllNodesWithContentDescription("Expand controls")
            if (expand.fetchSemanticsNodes().isNotEmpty()) {
                expand.onFirst().performClick()
                compose.waitForIdle()
            }

            compose.onAllNodes(hasText(" MHz · ", substring = true) and hasClickAction())
                .onFirst()
                .performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Band & mode").fetchSemanticsNodes().isNotEmpty()
            }

            compose.onAllNodes(hasText(targetBand) and isSelectable()).onFirst().performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(expectedLabel, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(expectedLabel, substring = true).onFirst().assertIsDisplayed()
            assertThat(GeneralVariables.band).isEqualTo(targetHz)

            Flows.screenshot("02_band_change_${targetBand}")
        } finally {
            restoreBand(originalBand)
        }
    }

    /** Put the dial back so a run on a real phone doesn't leave it retuned. */
    private fun restoreBand(hz: Long) {
        if (GeneralVariables.band == hz) return
        val vm = MainViewModel.peekInstance() ?: return
        Flows.onMain {
            selectBandIndex(vm, compose.activity, OperationBand.getIndexByFreq(hz))
        }
    }
}
