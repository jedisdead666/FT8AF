package radio.ks3ckc.ft8af.ui.components

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.FT8Common
import com.k1af.ft8af.Ft8Message
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [latestDecodeFrom], [qsoBannerSnr] and [bannerSnrLabel]: the QSO banner
 * and the QSO sheet's Signal card read the same decode, and the report we send is
 * labelled rather than shown as if it were the current signal (#953).
 */
@RunWith(RobolectricTestRunner::class)
class QsoBannerSnrTest {

    private fun msg(from: String?, snr: Int, utc: Long = 0L): Ft8Message =
        Ft8Message(FT8Common.FT8_MODE).apply {
            callsignFrom = from
            callsignTo = "K1AF"
            extraInfo = "-10"
            this.snr = snr
            utcTime = utc
        }

    @Test
    fun `latestDecodeFrom picks the newest decode from the station`() {
        val first = msg("R8PD", -14, 1_000L)
        val other = msg("W1AW", 3, 2_000L)
        val latest = msg("R8PD", -7, 3_000L)

        assertThat(latestDecodeFrom(listOf(first, other, latest), "R8PD")).isSameInstanceAs(latest)
    }

    @Test
    fun `latestDecodeFrom ignores case`() {
        val m = msg("R8PD", -7)
        assertThat(latestDecodeFrom(listOf(m), "r8pd")).isSameInstanceAs(m)
    }

    @Test
    fun `latestDecodeFrom matches a hashed call bracketed or stripped`() {
        val m = msg("<R8PD>", -7)
        assertThat(latestDecodeFrom(listOf(m), "<R8PD>")).isSameInstanceAs(m)
        assertThat(latestDecodeFrom(listOf(m), "R8PD")).isSameInstanceAs(m)
    }

    @Test
    fun `latestDecodeFrom returns null for no callsign, no list, or no match`() {
        val list = listOf(msg("R8PD", -7))
        assertThat(latestDecodeFrom(list, null)).isNull()
        assertThat(latestDecodeFrom(list, "")).isNull()
        assertThat(latestDecodeFrom(null, "R8PD")).isNull()
        assertThat(latestDecodeFrom(list, "W1AW")).isNull()
    }

    @Test
    fun `latestDecodeFrom skips decodes with a null sender`() {
        assertThat(latestDecodeFrom(listOf(msg(null, -7)), "R8PD")).isNull()
    }

    @Test
    fun `banner shows the latest heard SNR and labels a different sent report`() {
        // The #953 case: sheet says -7, we locked in -14 as the report at QSO start.
        val snr = qsoBannerSnr(msg("R8PD", -7), sentReport = -14)
        assertThat(snr).isEqualTo(QsoBannerSnr(heard = -7, sent = -14))
    }

    @Test
    fun `sent report is dropped when it matches what we hear`() {
        assertThat(qsoBannerSnr(msg("R8PD", -7), sentReport = -7))
            .isEqualTo(QsoBannerSnr(heard = -7, sent = null))
    }

    @Test
    fun `sent report alone when the target is not in the decode list`() {
        assertThat(qsoBannerSnr(null, sentReport = -14))
            .isEqualTo(QsoBannerSnr(heard = null, sent = -14))
    }

    @Test
    fun `decode without an SNR gives no heard value`() {
        assertThat(qsoBannerSnr(msg("R8PD", Ft8Message.SNR_UNKNOWN), sentReport = -14))
            .isEqualTo(QsoBannerSnr(heard = null, sent = -14))
    }

    @Test
    fun `unknown or missing sent report is dropped`() {
        assertThat(qsoBannerSnr(msg("R8PD", -7), sentReport = Ft8Message.SNR_UNKNOWN))
            .isEqualTo(QsoBannerSnr(heard = -7, sent = null))
        assertThat(qsoBannerSnr(msg("R8PD", -7), sentReport = null))
            .isEqualTo(QsoBannerSnr(heard = -7, sent = null))
    }

    @Test
    fun `bannerSnrLabel signs the value`() {
        assertThat(bannerSnrLabel(3)).isEqualTo("+3dB")
        assertThat(bannerSnrLabel(0)).isEqualTo("+0dB")
        assertThat(bannerSnrLabel(-14)).isEqualTo("-14dB")
    }
}
