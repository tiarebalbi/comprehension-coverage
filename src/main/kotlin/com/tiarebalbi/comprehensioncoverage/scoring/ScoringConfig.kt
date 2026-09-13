package com.tiarebalbi.comprehensioncoverage.scoring

/**
 * The subset of SPEC.md §4's tunable parameters consumed by [scoreAll] and
 * [buildModuleMap]. Defaults match the prototype's `DEFAULTS` dict / the
 * spec's current tunables table. Config loading (JSON, defaults-merge,
 * validation) is out of scope for this engine — see #7.
 */
data class ScoringConfig(
    val hChurn: Double = 1.0,
    val hWallDays: Double = 180.0,
    val quiescenceStretch: Double = 2.0,
    val churnCap: Double = 4.0,
    val satLines: Double = 400.0,
    val thetaPerson: Double = 0.5,
    val thetaCovered: Int = 2,
    val weights: Map<EvidenceType, Double> = mapOf(
        EvidenceType.AUTHORED to 1.0,
        EvidenceType.AGENT_MEDIATED to 0.3,
        EvidenceType.ATTESTED to 0.6,
    ),
    val departed: Set<String> = emptySet(),
)
