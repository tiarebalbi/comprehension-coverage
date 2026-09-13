package com.tiarebalbi.comprehensioncoverage.scoring

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Ports the prototype's `score_all` (SPEC.md §3). Evaluates every evidence
 * event's decay and value, accumulating per (person, module), then caps each
 * total at 1.0.
 *
 * `H_wall_eff` (the quiescence-scaled wall-clock floor, CALIBRATION.md
 * candidate 1) is evaluated in the exact order the prototype uses — this is
 * a deliberate non-rearrangement, not an oversight; algebraically equivalent
 * reorderings can still diverge under IEEE-754 doubles.
 */
fun scoreAll(
    config: ScoringConfig,
    events: List<Evidence>,
    moduleSizes: Map<String, Double>,
    asOf: Long,
): Map<PersonModule, Double> {
    val scores = LinkedHashMap<PersonModule, Double>()
    for (e in events) {
        val moduleSize = moduleSizes[e.module] ?: 1.0
        val churnRatio = minOf(config.churnCap, e.churnAfter / moduleSize)
        val days = maxOf(0.0, (asOf - e.timestamp) / 86400.0)
        val hWallEff = config.hWallDays *
            (1 + config.quiescenceStretch * (1 - churnRatio / config.churnCap))
        val effectiveAge = churnRatio / config.hChurn + days / hWallEff
        val decay = 0.5.pow(effectiveAge)
        val value = config.weights.getValue(e.type) * minOf(1.0, sqrt(e.magnitude / config.satLines))
        val key = e.person to e.module
        scores[key] = (scores[key] ?: 0.0) + value * decay
    }
    return scores.mapValues { (_, total) -> minOf(1.0, total) }
}

enum class ModuleStatus { COVERED, AT_RISK, DARK }

data class ModuleReport(
    val status: ModuleStatus,
    val comprehenders: Int,
    /** Sorted descending by score, then ascending by person — mirrors the prototype. */
    val people: List<Pair<String, Double>>,
)

/**
 * Python's `round(x, n)` is round-half-to-even on the underlying binary
 * double, not `HALF_UP`. `BigDecimal(x)` (not `BigDecimal.valueOf(x)`, which
 * round-trips through `Double.toString()` first) preserves the exact binary
 * value, so `HALF_EVEN` here matches Python's rounding of the same double.
 * Public: [buildModuleMap] uses 4 decimals (display/threshold rounding);
 * the golden fixture's `scores` section (#8) uses 6.
 */
fun pythonRound(x: Double, decimals: Int): Double =
    BigDecimal(x).setScale(decimals, RoundingMode.HALF_EVEN).toDouble()

/**
 * Ports the prototype's `build_map`. Note the threshold check compares
 * against the *rounded* score (as `people` does), not the raw score —
 * replicated exactly, since a value can round across `theta_person` at the
 * boundary.
 */
fun buildModuleMap(
    config: ScoringConfig,
    scores: Map<PersonModule, Double>,
    moduleNames: Collection<String>,
): Map<String, ModuleReport> {
    val modules = LinkedHashMap<String, ModuleReport>()
    for (mod in moduleNames.sorted()) {
        val people = scores.entries
            .filter { (key, _) -> key.second == mod }
            .map { (key, score) -> key.first to pythonRound(score, 4) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        val comprehenders = people.count { (person, score) ->
            score >= config.thetaPerson && person !in config.departed
        }
        val status = when {
            comprehenders == 0 -> ModuleStatus.DARK
            comprehenders < config.thetaCovered -> ModuleStatus.AT_RISK
            else -> ModuleStatus.COVERED
        }
        modules[mod] = ModuleReport(status, comprehenders, people)
    }
    return modules
}
