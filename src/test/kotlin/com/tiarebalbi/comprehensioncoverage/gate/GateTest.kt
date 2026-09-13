package com.tiarebalbi.comprehensioncoverage.gate

import com.tiarebalbi.comprehensioncoverage.config.GateConfig
import com.tiarebalbi.comprehensioncoverage.scoring.Evidence
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleStatus
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val DAY = 86400L
private const val T0 = 1700000000L

class GateTest {

    private fun tempDir(): File = Files.createTempDirectory("gate-test").toFile()

    private val critical = listOf("core", "web")

    @Test
    fun `passes when nothing critical fails`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val outcome = runGate(GateConfig(), critical, "/nonexistent", modules, emptyList(), T0, null, null, 2)
        assertEquals(GateOutcome(0, ""), outcome)
    }

    @Test
    fun `exits 2 on a dark critical module and names the strongest scorer without a score`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, listOf("Alice" to 0.12)),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val events = listOf(Evidence("Alice", "core", EvidenceType.AUTHORED, T0, 400.0))
        val outcome = runGate(GateConfig(), critical, "/nonexistent", modules, events, T0 + 200 * DAY, null, null, 2)
        assertEquals(2, outcome.exitCode)
        assertTrue(outcome.message.contains("GATE: DARK critical module(s): core"))
        assertTrue(
            outcome.message.contains(
                "Alice holds the strongest remaining evidence; last AUTHORED evidence 200d before as-of."
            )
        )
        assertTrue(!outcome.message.contains("0.12")) // C5: no per-person scalar in gate output
    }

    @Test
    fun `AT_RISK does not exit by default`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.AT_RISK, 1, listOf("Bob" to 0.7)),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val outcome = runGate(GateConfig(), critical, "/nonexistent", modules, emptyList(), T0, null, null, 2)
        assertEquals(GateOutcome(0, ""), outcome)
    }

    @Test
    fun `exits 1 on AT_RISK critical when exit1OnAtRisk is enabled`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.AT_RISK, 1, listOf("Bob" to 0.7)),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val events = listOf(Evidence("Bob", "core", EvidenceType.AUTHORED, T0, 400.0))
        val outcome = runGate(
            GateConfig(exit1OnAtRisk = true), critical, "/nonexistent", modules, events,
            T0 + 50 * DAY, null, null, 2,
        )
        assertEquals(1, outcome.exitCode)
        // Full-line match, including the em-dash clause -- pinned so a
        // future edit here can't drift from the prototype's identical
        // literal (test_run_gate_exits_1_on_at_risk_when_enabled).
        assertEquals(
            "GATE: AT_RISK critical module(s): core — comprehension exists but is below " +
                "the bus-factor threshold (theta_covered=2).\n" +
                "  core: Bob holds the strongest remaining evidence; last AUTHORED evidence 50d before as-of.",
            outcome.message,
        )
    }

    @Test
    fun `no evidence recorded message when the module's people list is empty`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, emptyList()),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val outcome = runGate(GateConfig(), critical, "/nonexistent", modules, emptyList(), T0, null, null, 2)
        assertTrue(outcome.message.contains("core: no evidence recorded for this module."))
    }

    @Test
    fun `same-timestamp tie between AUTHORED and ATTESTED breaks toward the commit-derived event`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, listOf("Alice" to 0.1)),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val events = listOf(
            Evidence("Alice", "core", EvidenceType.ATTESTED, T0, 400.0),
            Evidence("Alice", "core", EvidenceType.AUTHORED, T0, 400.0),
        )
        val outcome = runGate(GateConfig(), critical, "/nonexistent", modules, events, T0 + 10 * DAY, null, null, 2)
        assertTrue(outcome.message.contains("last ATTESTED evidence"))
    }

    @Test
    fun `break-glass without a resolvable person throws`() {
        val modules = mapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, emptyList()),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val e = assertFailsWith<IllegalArgumentException> {
            runGate(GateConfig(), critical, "/nonexistent", modules, emptyList(), T0, "INC-1", null, 2)
        }
        assertTrue(e.message!!.contains("break-glass-person"))
    }

    @Test
    fun `a blank person falls through to the config value, then errors if that's blank too`() {
        // A blank string (CLI or config) must resolve the same as "not
        // provided" -- never silently accepted as a real person.
        val repo = tempDir()
        try {
            val modules = mapOf(
                "core" to ModuleReport(ModuleStatus.DARK, 0, emptyList()),
                "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
            )
            val e = assertFailsWith<IllegalArgumentException> {
                runGate(GateConfig(), critical, repo.path, modules, emptyList(), T0, "INC-1", "   ", 2)
            }
            assertTrue(e.message!!.contains("break-glass-person"))

            val outcome = runGate(
                GateConfig(breakGlassPerson = "oncall@example.com"), critical, repo.path, modules,
                emptyList(), T0, "INC-1", "   ", 2,
            )
            assertEquals(0, outcome.exitCode)
            assertTrue(outcome.message.contains("oncall@example.com")) // blank CLI value falls through to config
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `break-glass downgrades the exit to 0 and writes the exact stub`() {
        val repo = tempDir()
        try {
            val modules = mapOf(
                "core" to ModuleReport(ModuleStatus.DARK, 0, listOf("Alice" to 0.1)),
                "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
            )
            val events = listOf(Evidence("Alice", "core", EvidenceType.AUTHORED, T0, 400.0))
            val asOf = T0 + 200 * DAY
            val outcome = runGate(
                GateConfig(), critical, repo.path, modules, events, asOf,
                "INC-42", "oncall@example.com", 2,
            )
            assertEquals(0, outcome.exitCode)
            assertTrue(outcome.message.contains("BREAK-GLASS: 'INC-42'"))
            assertTrue(outcome.message.contains("oncall@example.com"))

            val content = File(repo, ".comprehension/attestations.yaml").readText()
            val expected = "- email: oncall@example.com\n" +
                "  module: core\n" +
                "  timestamp: \"${formatIso(asOf)}\"\n" +
                "  type: INCIDENT_DIAGNOSED\n" +
                "  incident_ref: \"INC-42\"\n"
            assertEquals(expected, content)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `break-glass appends without losing a missing trailing newline`() {
        val repo = tempDir()
        try {
            File(repo, ".comprehension").mkdirs()
            File(repo, ".comprehension/attestations.yaml").writeText(
                "- email: dana@example.com\n  module: web\n  timestamp: \"2026-09-08T00:00:00Z\""
            ) // no trailing newline

            val modules = mapOf(
                "core" to ModuleReport(ModuleStatus.DARK, 0, emptyList()),
                "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
            )
            val asOf = T0
            runGate(GateConfig(), critical, repo.path, modules, emptyList(), asOf, "INC-7", "oncall@example.com", 2)

            val content = File(repo, ".comprehension/attestations.yaml").readText()
            val expected = "- email: dana@example.com\n  module: web\n  timestamp: \"2026-09-08T00:00:00Z\"\n" +
                "- email: oncall@example.com\n" +
                "  module: core\n" +
                "  timestamp: \"${formatIso(asOf)}\"\n" +
                "  type: INCIDENT_DIAGNOSED\n" +
                "  incident_ref: \"INC-7\"\n"
            assertEquals(expected, content)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `formatIso matches the schema's example timestamp style`() {
        // 2026-09-08T00:00:00Z, the exact instant used throughout attestations fixtures.
        assertEquals("2026-09-08T00:00:00Z", formatIso(1788825600L))
    }
}
