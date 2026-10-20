package com.k1af.ft8af.flows

import android.Manifest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.k1af.ft8af.MainViewModel
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import radio.ks3ckc.ft8af.ComposeMainActivity

/**
 * Shared plumbing for the scripted emulator flows (BUR-25). Each flow launches the
 * real [ComposeMainActivity], drives it through Compose semantics, and saves a
 * screenshot when it ends (plus one on failure). CI pulls the screenshots from
 * [SCREENSHOT_DIR] and uploads them as the `emulator-flows` artifact, so QA can
 * see what each flow ended on without a phone.
 */
internal object Flows {
    /**
     * Written by the shell user (via `screencap`), not the app, so the files
     * survive the uninstall at the end of connectedAndroidTest and CI can
     * `adb pull` them afterwards.
     */
    const val SCREENSHOT_DIR = "/data/local/tmp/ft8af-flows"

    /** The splash plays for ~2 s; allow for a slow, cold emulator on top. */
    const val LAUNCH_TIMEOUT_MS = 30_000L

    private const val TAG = "FT8AFFlows"

    /** Same pre-grants as AppSmokeTest: without them the permission dialog keeps the activity PAUSED. */
    private fun permissionRule(): GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /** Compose rule for the main activity. */
    fun composeRule() = createAndroidComposeRule<ComposeMainActivity>()

    /**
     * Permissions outermost so they're granted before launch; the failure
     * screenshot innermost so it fires while the activity is still on screen.
     */
    fun chain(
        compose: AndroidComposeTestRule<ActivityScenarioRule<ComposeMainActivity>, ComposeMainActivity>,
    ): TestRule = RuleChain.outerRule(permissionRule())
        .around(compose)
        .around(ScreenshotOnFailure())

    /** Run a shell command as the shell user and wait for it to finish. */
    fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    /** Save a full-screen PNG to [SCREENSHOT_DIR]/<name>.png. */
    fun screenshot(name: String) {
        shell("mkdir -p $SCREENSHOT_DIR")
        val out = shell("screencap -p $SCREENSHOT_DIR/$name.png")
        Log.i(TAG, "screenshot $name.png ${out.trim()}")
    }

    /** Wait out the splash until the tab bar is on screen. */
    fun ComposeTestRule.waitForMainScreen() {
        waitUntil(LAUNCH_TIMEOUT_MS) {
            onAllNodes(hasText("Settings") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(LAUNCH_TIMEOUT_MS) { MainViewModel.peekInstance() != null }
    }

    /** Tap a bottom-bar tab by its label. */
    fun ComposeTestRule.openTab(label: String) {
        onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()
        waitForIdle()
    }

    /** Run [block] on the main thread and return its result. */
    fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching(block)
        }
        return result!!.getOrThrow()
    }
}

/** Screenshot named `<Class>_<method>-FAILED` when a flow fails. */
internal class ScreenshotOnFailure : TestWatcher() {
    override fun failed(e: Throwable?, description: Description) {
        runCatching {
            Flows.screenshot("${description.testClass.simpleName}_${description.methodName}-FAILED")
        }
    }
}
