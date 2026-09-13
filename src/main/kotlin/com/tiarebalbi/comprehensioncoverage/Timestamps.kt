package com.tiarebalbi.comprehensioncoverage

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parses an ISO-8601 instant, rejecting tz-naive strings (SPEC §2.1 / C6):
 * "same repository state + same config = same map, bit for bit" forbids
 * resolving an ambiguous timestamp against the invoking machine's local
 * timezone. Mirrors the prototype's single `parse_as_of` -- the one C6
 * timestamp parser every caller goes through: attestation timestamps (see
 * `readAttestations`) and config loading's `--as-of`-equivalent inputs
 * both route through this function, not a second copy of the rejection
 * logic.
 *
 * Distinguishes two failure modes (review note from #23, which reported
 * the same "no UTC offset" message for any parse failure): a string that
 * parses as a valid ISO-8601 local date-time but has no offset gets the
 * specific C6 rejection; anything else -- genuinely malformed input -- gets
 * a plain "unparseable" error instead, so the message doesn't imply a
 * missing offset was the only thing wrong with it.
 */
fun parseAsOf(s: String, label: String = "--as-of"): Long {
    val normalized = s.replace("Z", "+00:00")
    try {
        return OffsetDateTime.parse(normalized).toEpochSecond()
    } catch (offsetError: DateTimeParseException) {
        val looksValidButOffsetless = try {
            LocalDateTime.parse(normalized)
            true
        } catch (e: DateTimeParseException) {
            false
        }
        if (looksValidButOffsetless) {
            throw IllegalArgumentException(
                "$label '$s' has no UTC offset; C6 forbids resolving it against " +
                    "the local machine's timezone. Use an explicit offset, e.g. " +
                    "'2026-09-13T00:00:00Z'."
            )
        }
        throw IllegalArgumentException(
            "$label '$s' is unparseable as an ISO-8601 timestamp: ${offsetError.message}"
        )
    }
}
