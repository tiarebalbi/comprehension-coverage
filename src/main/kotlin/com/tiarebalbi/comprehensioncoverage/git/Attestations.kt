package com.tiarebalbi.comprehensioncoverage.git

import com.tiarebalbi.comprehensioncoverage.parseAsOf
import java.io.File

/**
 * Parses `.comprehension/attestations.yaml` (SPEC §2): a hand-rolled
 * reader for a restricted YAML subset -- a flat list of `- key: value`
 * mappings, no nesting, no multi-line scalars -- mirroring the
 * prototype's `read_attestations` exactly, including its error/skip
 * behavior. Returns raw (lowercased) emails, not yet resolved to a
 * person: identity resolution needs the commit stream, which only
 * [collect] has (SPEC §2.1).
 *
 * A record may also carry a `type` field (SPEC §2): its absence means
 * `ATTESTED`, the only type this reader ingests in v0.1. A `type` naming a
 * reserved-not-implemented class (e.g. `INCIDENT_DIAGNOSED`, written by
 * `--break-glass`, SPEC §5) is a legitimate record, not a malformed one --
 * it is skipped with a stderr warning rather than folded into evidence.
 * This is what keeps a break-glass stub from silently manufacturing
 * comprehension.
 */
fun readAttestations(repo: String, config: IngestConfig): List<Attestation> {
    val file = File(repo, ".comprehension/attestations.yaml")
    if (!file.isFile) return emptyList()

    val records = mutableListOf<MutableMap<String, String>>()
    var current: MutableMap<String, String>? = null
    file.forEachLine { raw ->
        val line = raw.substringBefore("#")
        if (line.isBlank()) return@forEachLine
        var stripped = line.trim()
        if (stripped.startsWith("- ")) {
            current?.let { records.add(it) }
            current = mutableMapOf()
            stripped = stripped.substring(2)
        }
        val rec = current ?: throw IllegalArgumentException(
            "attestations.yaml: expected a list, got: $line"
        )
        val colon = stripped.indexOf(':')
        if (colon < 0) throw IllegalArgumentException(
            "attestations.yaml: expected 'key: value', got: $line"
        )
        val key = stripped.substring(0, colon).trim()
        val value = stripped.substring(colon + 1).trim().trim('"').trim('\'')
        rec[key] = value
    }
    current?.let { records.add(it) }

    val attestations = mutableListOf<Attestation>()
    for (rec in records) {
        for (required in listOf("email", "module", "timestamp")) {
            require(required in rec) { "attestations.yaml: record missing '$required': $rec" }
        }
        val type = rec["type"] ?: "ATTESTED"
        if (type != "ATTESTED") {
            System.err.println(
                "comprehension: attestations.yaml: skipping reserved evidence " +
                    "class '$type' (declared interface, not ingested in v0.1): $rec"
            )
            continue
        }
        val module = rec.getValue("module")
        if (module !in config.modules) {
            // A subset-module config is a legitimate run mode, so this is a
            // skip, not an error -- but silent drops are how real data goes
            // missing unnoticed, so name the record on the way out.
            System.err.println(
                "comprehension: attestations.yaml: skipping record for " +
                    "unconfigured module '$module': $rec"
            )
            continue
        }
        val timestamp = parseAsOf(rec.getValue("timestamp"), "attestations.yaml timestamp")
        attestations.add(Attestation(rec.getValue("email").trim().lowercase(), module, timestamp))
    }
    return attestations
}
