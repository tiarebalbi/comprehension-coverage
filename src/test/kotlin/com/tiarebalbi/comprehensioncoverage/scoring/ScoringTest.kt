package com.tiarebalbi.comprehensioncoverage.scoring

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY = 86400L
private const val T0 = 1700000000L // fixed epoch base, matches the prototype's tests

private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-9) {
    assertTrue(abs(actual - expected) < tolerance, "expected $expected, got $actual")
}

class ScoringTest {

    // ---------------------------------------------------------------- decay

    @Test
    fun `wall-clock half-life with quiescence stretch disabled`() {
        // quiescence_stretch=0 isolates the base h_wall_days mechanic
        // (churn_ratio=0 -> h_wall_eff == h_wall_days) from the stretch below.
        val config = ScoringConfig(quiescenceStretch = 0.0)
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines)
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0 + 180 * DAY)
        assertNear(0.5, scores.getValue("alice" to "core"))
    }

    @Test
    fun `quiescence stretches the wall-clock floor for frozen evidence`() {
        // CALIBRATION.md candidate 1: a frozen module (churn_ratio=0) stretches
        // the wall half-life to h_wall*(1+stretch) -- default stretch=2.0 -> 3x.
        val config = ScoringConfig()
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines)
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0 + 540 * DAY)
        assertNear(0.5, scores.getValue("alice" to "core"))
    }

    @Test
    fun `quiescence does not stretch fully churned evidence`() {
        // At churn_ratio == churn_cap, the stretch relaxes to 0: h_wall_eff ==
        // h_wall_days, identical to the unstretched baseline (SPEC C3) --
        // evidence already churned away by others must not get a second reprieve.
        val config = ScoringConfig()
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines, churnAfter = config.churnCap * 1000.0)
        val stretched = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0 + 180 * DAY)
        val baseline = scoreAll(config.copy(quiescenceStretch = 0.0), listOf(e), mapOf("core" to 1000.0), T0 + 180 * DAY)
        assertNear(baseline.getValue("alice" to "core"), stretched.getValue("alice" to "core"))
    }

    @Test
    fun `churn half-life`() {
        val config = ScoringConfig()
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines, churnAfter = 1000.0) // == module size -> churn_ratio 1.0
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0)
        assertNear(0.5, scores.getValue("alice" to "core"))
    }

    // ---------------------------------------------------------------- 0.5^x parity (Python's `**`)

    @Test
    fun `half pow x matches Python's half star star x bit-for-bit for a representative fractional exponent`() {
        // 0.5 ** 0.37 in CPython (platform libm): 0.7737824967711949,
        // raw bits 3fe8c2d382bb2f2b. `0.5.pow(x)` delegates to Math.pow
        // (fdlibm-derived) -- a different implementation permitted up to 1 ULP
        // of divergence from libm on non-exact inputs. This one happens to
        // agree exactly; lock it as a regression case.
        val bits = java.lang.Double.doubleToRawLongBits(0.5.pow(0.37))
        assertEquals(0x3fe8c2d382bb2f2bL, bits)
    }

    @Test
    fun `half pow x stays within one ULP of Python at a half-integer exponent`() {
        // Documented finding for #10 (A4 byte-identical parity), not fixed
        // here -- and NOT just a Kotlin-vs-Python fact, which is why this
        // asserts a bound rather than a fixed divergence:
        //
        // Python's 0.5 ** 2.5 == 0.1767766952966369 (bits ...3bcd). Locally
        // (GraalVM 21.0.10, aarch64) Math.pow's 0.5.pow(2.5) landed 1 ULP low
        // at ...3bcc; on this project's CI (Temurin 21, Linux x64) the same
        // Kotlin source instead matches Python's bits exactly. Same bytecode,
        // same exponent, different JVM vendor/platform, different pow result
        // -- Math.pow's ~1 ULP tolerance is real and vendor-observable, not
        // hypothetical. That's a sharper C6 concern than "Kotlin vs. Python
        // parity": it means two JVM builds running the identical Kotlin
        // implementation are not guaranteed bit-for-bit identical to each
        // other either. #10 needs a strategy for this (round before
        // comparing, a shared correctly-rounded pow, or pinning the JVM
        // distribution as part of C6's determinism contract).
        val pythonBits = 0x3fc6a09e667f3bcdL
        val kotlinBits = java.lang.Double.doubleToRawLongBits(0.5.pow(2.5))
        val ulpDelta = kotlinBits - pythonBits
        assertTrue(
            abs(ulpDelta) <= 1,
            "pow(0.5, 2.5) diverged by more than the documented 1 ULP: kotlin=$kotlinBits python=$pythonBits delta=$ulpDelta",
        )
    }

    @Test
    fun `fractional decay through the full score_all evaluation order matches Python bit-for-bit`() {
        // as_of = T0 + 66 days gives eff_age = 66 / 540 = 0.1222... (non-dyadic,
        // exercises the real 0.5^x call through score_all's exact evaluation
        // order, not pow() in isolation). Python's comprehension.py score_all
        // produces 0.9187713517408406 (bits 3fed66932d8750e1) for these inputs.
        val config = ScoringConfig()
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines)
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0 + 66 * DAY)
        val bits = java.lang.Double.doubleToRawLongBits(scores.getValue("alice" to "core"))
        assertEquals(0x3fed66932d8750e1L, bits)
    }

    // ---------------------------------------------------------------- weights

    @Test
    fun `agent-mediated evidence is discounted relative to hand authorship`() {
        val config = ScoringConfig()
        val alice = Evidence("alice", "core", EvidenceType.AUTHORED, T0, config.satLines)
        val bob = Evidence("bob", "core", EvidenceType.AGENT_MEDIATED, T0, config.satLines)
        val scores = scoreAll(config, listOf(alice, bob), mapOf("core" to 1000.0), T0)
        assertNear(0.3, scores.getValue("bob" to "core") / scores.getValue("alice" to "core"))
    }

    @Test
    fun `attested evidence applies its fixed weight`() {
        // magnitude fixed at sat_lines (fully saturated); same timestamp as
        // as_of -> no decay -> score == weight(ATTESTED) exactly.
        val config = ScoringConfig()
        val e = Evidence("dana", "core", EvidenceType.ATTESTED, T0, config.satLines)
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0)
        assertNear(config.weights.getValue(EvidenceType.ATTESTED), scores.getValue("dana" to "core"))
    }

    @Test
    fun `saturation caps magnitude value at one`() {
        val config = ScoringConfig()
        val e = Evidence("alice", "core", EvidenceType.AUTHORED, T0, 10 * config.satLines)
        val scores = scoreAll(config, listOf(e), mapOf("core" to 1000.0), T0)
        assertEquals(1.0, scores.getValue("alice" to "core"))
    }

    // ---------------------------------------------------------------- module map

    @Test
    fun `status thresholds`() {
        val config = ScoringConfig()
        val scores = mapOf(
            ("alice" to "core") to 0.9,
            ("bob" to "core") to 0.6,
            ("carol" to "web") to 0.4,
        )
        val modules = buildModuleMap(config, scores, listOf("core", "web"))
        assertEquals(ModuleStatus.COVERED, modules.getValue("core").status)
        assertEquals(ModuleStatus.DARK, modules.getValue("web").status)
    }

    @Test
    fun `departed people are excluded from comprehender counts`() {
        val config = ScoringConfig(departed = setOf("alice"))
        val scores = mapOf(("alice" to "core") to 0.9)
        val modules = buildModuleMap(config, scores, listOf("core"))
        assertEquals(ModuleStatus.DARK, modules.getValue("core").status) // evidence exists, holder is gone
    }

    @Test
    fun `score rounding is round-half-to-even, not half-up`() {
        // Both inputs are exact binary ties at the 4th decimal place with an
        // even preceding digit (2): HALF_EVEN rounds down (stays even) while
        // HALF_UP would round away from zero to 0.0313 / 0.1563 instead.
        val config = ScoringConfig()
        val scores = mapOf(
            ("alice" to "core") to 0.03125,
            ("bob" to "core") to 0.15625,
        )
        val modules = buildModuleMap(config, scores, listOf("core"))
        val people = modules.getValue("core").people.toMap()
        assertEquals(0.0312, people.getValue("alice"))
        assertEquals(0.1562, people.getValue("bob"))
    }
}
