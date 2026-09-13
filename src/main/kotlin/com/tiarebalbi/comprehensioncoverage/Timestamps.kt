package com.tiarebalbi.comprehensioncoverage

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parses an ISO-8601 instant, rejecting tz-naive strings (SPEC §2.1 / C6):
 * "same repository state + same config = same map, bit for bit" forbids
 * resolving an ambiguous timestamp against the invoking machine's local
 * timezone. Mirrors the prototype's single `parse_as_of` -- the one C6
 * timestamp parser every caller goes through. Today that's attestation
 * timestamps (see `readAttestations`); `--as-of` CLI parsing is out of
 * scope for this engine (see #7) but should route through this same
 * function when it lands, not a second copy of the rejection logic.
 */
fun parseAsOf(s: String, label: String = "--as-of"): Long {
    val normalized = s.replace("Z", "+00:00")
    val offsetDateTime = try {
        OffsetDateTime.parse(normalized)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException(
            "$label '$s' has no UTC offset; C6 forbids resolving it against " +
                "the local machine's timezone. Use an explicit offset, e.g. " +
                "'2026-09-13T00:00:00Z'."
        )
    }
    return offsetDateTime.toEpochSecond()
}
