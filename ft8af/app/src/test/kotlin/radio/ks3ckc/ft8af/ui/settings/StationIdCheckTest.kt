package radio.ks3ckc.ft8af.ui.settings

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.log.ThirdPartyService
import org.junit.Test

/**
 * Unit tests for [checkStationId], the Station ID sanity check under Test Connection in the
 * Cloudlog / Wavelog settings dialog. Wavelog rejects every QSO whose station isn't the API
 * key owner's, and Test Connection alone can't tell — so the dialog warns up front.
 */
class StationIdCheckTest {
    private fun station(id: String) = ThirdPartyService.StationProfile(id, "Home", "W1AW", "FN31")

    @Test
    fun `id in the fetched list is ok`() {
        assertThat(checkStationId("2", listOf(station("1"), station("2"))))
            .isEqualTo(StationIdCheck.OK)
    }

    @Test
    fun `id trimmed before matching`() {
        assertThat(checkStationId(" 2 \n", listOf(station("2")))).isEqualTo(StationIdCheck.OK)
    }

    @Test
    fun `id missing from the list is flagged`() {
        assertThat(checkStationId("7", listOf(station("1"), station("2"))))
            .isEqualTo(StationIdCheck.NOT_ON_SERVER)
    }

    @Test
    fun `blank id is flagged regardless of the list`() {
        assertThat(checkStationId("", listOf(station("1")))).isEqualTo(StationIdCheck.BLANK)
        assertThat(checkStationId("   ", emptyList())).isEqualTo(StationIdCheck.BLANK)
    }

    @Test
    fun `no list means no verdict`() {
        assertThat(checkStationId("3", emptyList())).isEqualTo(StationIdCheck.UNKNOWN)
    }
}
