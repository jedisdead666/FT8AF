@file:JvmName("CloudlogFailureHint")

package radio.ks3ckc.ft8af.sync

import com.k1af.ft8af.GeneralVariables
import com.k1af.ft8af.R

/**
 * Turns a rejected Cloudlog / Wavelog / Nextlog upload into something the operator can act on.
 *
 * The failure line that reaches us (see `ThirdPartyService.describeHttpFailure`) is the raw
 * HTTP status plus the server's JSON body, e.g.
 *
 *     HTTP 401: {"status":"failed","reason":"station id does not belong to the API key owner."}
 *
 * That line is exactly right for `debug.log`, but on screen the JSON wrapper is noise and the
 * reason alone still leaves the operator guessing which setting to fix. The two Wavelog
 * rejections that account for nearly every "the test passes but nothing uploads" support
 * thread are a Station ID that isn't the key owner's and a key without write rights, so those
 * get a one-line hint pointing at the setting. Pure string logic, unit-tested; the only
 * Android touch-point is [cloudlogFailureMessage] resolving string resources.
 */
enum class CloudlogFailureKind {
    /** The `station_profile_id` is blank or belongs to another Wavelog user. */
    STATION_ID,

    /** The API key is missing, unknown, disabled, or read-only. */
    API_KEY,

    /** Anything else: transport errors, ADIF import errors, proxies returning HTML… */
    OTHER,
}

private val REASON = Regex("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
private val MESSAGES = Regex("\"messages\"\\s*:\\s*\\[(.*?)]")
private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

private fun unescapeJson(s: String): String {
    return s.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
}

/**
 * The server's own explanation, stripped of the HTTP/JSON wrapper: the `reason` field when
 * there is one, else the non-empty `messages` entries joined with "; ", else the line as-is
 * (a bare "HTTP 502" or "Unknown host: …" is already readable). Blank in, blank out.
 */
fun cloudlogServerReason(failure: String?): String {
    val raw = failure?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    REASON.find(raw)?.let { return unescapeJson(it.groupValues[1]).trim() }
    MESSAGES.find(raw)?.let { m ->
        val parts =
            QUOTED.findAll(m.groupValues[1])
                .map { unescapeJson(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
        if (parts.isNotEmpty()) return parts.joinToString("; ")
    }
    return raw
}

/** Which setting the failure points at. Station check runs first: its text mentions the key too. */
fun classifyCloudlogFailure(failure: String?): CloudlogFailureKind {
    val reason = cloudlogServerReason(failure).lowercase()
    if (reason.isEmpty()) return CloudlogFailureKind.OTHER
    if (reason.contains("station")) return CloudlogFailureKind.STATION_ID
    if (reason.contains("api key") || reason.contains("apikey") || reason.contains("key invalid")) {
        return CloudlogFailureKind.API_KEY
    }
    return CloudlogFailureKind.OTHER
}

/** String resource for the fix-it hint, or null when we have nothing better than the reason. */
fun cloudlogHintResId(failure: String?): Int? =
    when (classifyCloudlogFailure(failure)) {
        CloudlogFailureKind.STATION_ID -> R.string.cloudlog_hint_station_id
        CloudlogFailureKind.API_KEY -> R.string.cloudlog_hint_api_key
        CloudlogFailureKind.OTHER -> null
    }

/**
 * One sentence for a toast: the server's reason followed by the hint when there is one.
 * Resolves resources through [GeneralVariables.getStringFromResource] so it is callable from
 * the Java upload thread in MainViewModel as well as from Kotlin.
 */
fun cloudlogFailureMessage(failure: String?): String {
    val reason = cloudlogServerReason(failure)
    val hint = cloudlogHintResId(failure)?.let { GeneralVariables.getStringFromResource(it) }.orEmpty()
    return when {
        hint.isEmpty() -> reason
        reason.isEmpty() -> hint
        else -> "$reason $hint"
    }
}
