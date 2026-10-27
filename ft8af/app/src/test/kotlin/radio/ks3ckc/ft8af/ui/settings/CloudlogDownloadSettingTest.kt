package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [cloudlogDownloadUnsupported]: "Download QSOs from Wavelog when syncing" is greyed out
 * only once the server is known to have no QSO listing (issue #965).
 */
class CloudlogDownloadSettingTest {
    @Test
    fun `only a server known not to list qsos disables the toggle`() {
        assertThat(cloudlogDownloadUnsupported(false)).isTrue()
        assertThat(cloudlogDownloadUnsupported(true)).isFalse()
        // Unknown (offline, still checking): left usable; the sync skips the download.
        assertThat(cloudlogDownloadUnsupported(null)).isFalse()
    }
}
