package com.tiarebalbi.comprehensioncoverage.scoring

/**
 * v0.1's implemented evidence classes (SPEC.md §2). `REVIEWED`,
 * `INCIDENT_DIAGNOSED`, and `ADR_AUTHORED` are declared interfaces only —
 * not implemented in v0.1 — so they are not modeled here.
 */
enum class EvidenceType {
    AUTHORED,
    AGENT_MEDIATED,
    ATTESTED,
}

/**
 * An evidence event (SPEC.md §2): `(person, module, type, timestamp, magnitude)`,
 * plus `churnAfter` — lines changed in `module` by anyone other than `person`
 * between this event and the "as of" instant (prototype's `collect()` computes
 * this from git history; here it is supplied directly, since building it from
 * commits is out of scope for this engine — see #6).
 */
data class Evidence(
    val person: String,
    val module: String,
    val type: EvidenceType,
    val timestamp: Long,
    val magnitude: Double,
    val churnAfter: Double = 0.0,
)

/** (person, module) — mirrors the prototype's `(p, m)` tuple key. */
typealias PersonModule = Pair<String, String>
