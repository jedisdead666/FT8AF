package radio.ks3ckc.ft8af.sync

import com.google.common.truth.Truth.assertThat
import com.k1af.ft8af.R
import org.junit.Test

/**
 * Unit tests for the rejected-upload explainer: [cloudlogServerReason], [classifyCloudlogFailure]
 * and [cloudlogHintResId].
 *
 * The motivating case is a Wavelog user whose Test Connection passes but every QSO comes
 * back `HTTP 401: {"status":"failed","reason":"station id does not belong to the API key
 * owner."}` — a wrong Station ID. The app used to show "0 accepted" and nothing else.
 */
class CloudlogFailureHintTest {
    private val stationRejection =
        "HTTP 401: {\"status\":\"failed\",\"reason\":\"station id does not belong to the API key owner.\"}"

    @Test
    fun `wavelog station rejection yields the bare reason and the station hint`() {
        assertThat(cloudlogServerReason(stationRejection))
            .isEqualTo("station id does not belong to the API key owner.")
        assertThat(classifyCloudlogFailure(stationRejection)).isEqualTo(CloudlogFailureKind.STATION_ID)
        assertThat(cloudlogHintResId(stationRejection)).isEqualTo(R.string.cloudlog_hint_station_id)
    }

    @Test
    fun `wrong api key points at the key, not the station`() {
        val f = "HTTP 401: {\"status\":\"failed\",\"reason\":\"missing or wrong api key\"}"

        assertThat(cloudlogServerReason(f)).isEqualTo("missing or wrong api key")
        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.API_KEY)
        assertThat(cloudlogHintResId(f)).isEqualTo(R.string.cloudlog_hint_api_key)
    }

    @Test
    fun `read-only key is an api key problem`() {
        val f = "HTTP 403: {\"status\":\"failed\",\"reason\":\"API key does not have write permissions\"}"

        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.API_KEY)
    }

    @Test
    fun `adif import errors surface the messages array with no hint`() {
        val f =
            "HTTP 400: {\"status\":\"abort\",\"adif_errors\":1," +
                "\"messages\":[\"column \\\"tx_pwr\\\" of relation \\\"contacts\\\" does not exist\", \"\"]}"

        assertThat(cloudlogServerReason(f))
            .isEqualTo("column \"tx_pwr\" of relation \"contacts\" does not exist")
        assertThat(classifyCloudlogFailure(f)).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId(f)).isNull()
    }

    @Test
    fun `several messages are joined`() {
        val f = "HTTP 400: {\"status\":\"abort\",\"messages\":[\"first\",\"second\"]}"

        assertThat(cloudlogServerReason(f)).isEqualTo("first; second")
    }

    @Test
    fun `non-json failures pass through unchanged`() {
        assertThat(cloudlogServerReason("HTTP 502")).isEqualTo("HTTP 502")
        assertThat(cloudlogServerReason("Unknown host: wavelog.example")).isEqualTo("Unknown host: wavelog.example")
        assertThat(cloudlogServerReason("HTTP 500: <html>gateway timeout</html>"))
            .isEqualTo("HTTP 500: <html>gateway timeout</html>")
        assertThat(classifyCloudlogFailure("HTTP 502")).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId("Unknown host: wavelog.example")).isNull()
    }

    @Test
    fun `blank and null are harmless`() {
        assertThat(cloudlogServerReason(null)).isEmpty()
        assertThat(cloudlogServerReason("   ")).isEmpty()
        assertThat(classifyCloudlogFailure(null)).isEqualTo(CloudlogFailureKind.OTHER)
        assertThat(cloudlogHintResId("")).isNull()
    }

    @Test
    fun `station check wins even though its text also mentions the api key`() {
        // "…does not belong to the API key owner" contains "api key"; the fix is still the station.
        assertThat(classifyCloudlogFailure(stationRejection)).isEqualTo(CloudlogFailureKind.STATION_ID)
    }
}
