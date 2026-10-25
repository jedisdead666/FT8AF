package radio.ks3ckc.ft8af.ui.decode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit-tests [decodeRowDimAlpha], the row-opacity decision extracted from the
 * DecodeRow graphicsLayer lambda (which can't be unit-tested directly).
 *
 * Pure math — no Android types, so no Robolectric runner needed.
 */
class DecodeRowStyleTest {

    @Test
    fun noiseRow_isDimmedButReadable() {
        val alpha = decodeRowDimAlpha(isInQsoWithOther = true)

        assertThat(alpha).isEqualTo(0.8f)
        // Must stay brighter than the old 0.55 floor (issue #332) — a regression
        // back to the unreadable dimming fails here.
        assertThat(alpha).isGreaterThan(0.55f)
    }

    @Test
    fun directRow_isFullOpacity() {
        assertThat(decodeRowDimAlpha(isInQsoWithOther = false)).isEqualTo(1f)
    }

    @Test
    fun secondaryFieldText_prefersTheGridWhenPresent() {
        assertThat(secondaryFieldText(maidenGrid = "EM98", extraInfo = "-04")).isEqualTo("EM98")
    }

    @Test
    fun secondaryFieldText_fallsBackToExtraInfoWhenNotAGrid() {
        // Real-device finding: a signal-report exchange ("-04") never matches the
        // native decoder's strict grid-shape check, so maidenGrid comes back blank
        // and the report was only visible on the raw message text line -- which
        // compact mode hides entirely, making the row look like nothing decoded.
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "-04")).isEqualTo("-04")
        assertThat(secondaryFieldText(maidenGrid = null, extraInfo = "R-09")).isEqualTo("R-09")
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "RR73")).isEqualTo("RR73")
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "73")).isEqualTo("73")
    }

    @Test
    fun secondaryFieldText_blankWhenNeitherIsSet() {
        assertThat(secondaryFieldText(maidenGrid = null, extraInfo = null)).isEmpty()
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "")).isEmpty()
    }

    @Test
    fun secondaryFieldText_trimsExtraInfo() {
        assertThat(secondaryFieldText(maidenGrid = "", extraInfo = "  -04  ")).isEqualTo("-04")
    }
}
