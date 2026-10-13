package radio.ks3ckc.ft8af.ui.settings

import com.k1af.ft8af.log.ThirdPartyService

/**
 * Does the Station ID in the Cloudlog / Wavelog dialog stand a chance of being accepted?
 *
 * Test Connection only proves the API key (`api/auth`), and the station list it fetches
 * afterwards is scoped to that key's owner. Wavelog rejects every QSO whose
 * `station_profile_id` is blank or belongs to a different user with
 * "station id does not belong to the API key owner." — so a Station ID that is blank, or
 * that doesn't appear in the fetched list, is going to fail before the operator ever
 * leaves Settings. Pure so it is unit-testable; the composable just maps the result to a
 * warning string.
 */
enum class StationIdCheck {
    /** The ID is one of the key owner's stations. */
    OK,

    /** No ID entered at all. */
    BLANK,

    /** The server listed stations for this key and this ID is not among them. */
    NOT_ON_SERVER,

    /** No station list to judge against (not fetched yet, or the key owner has none). */
    UNKNOWN,
}

internal fun checkStationId(
    stationId: String,
    stations: List<ThirdPartyService.StationProfile>,
): StationIdCheck {
    val id = stationId.trim()
    if (id.isEmpty()) return StationIdCheck.BLANK
    if (stations.isEmpty()) return StationIdCheck.UNKNOWN
    return if (stations.any { it.stationId == id }) StationIdCheck.OK else StationIdCheck.NOT_ON_SERVER
}
