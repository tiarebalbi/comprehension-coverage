package com.tiarebalbi.comprehensioncoverage.config

import com.tiarebalbi.comprehensioncoverage.git.moduleOf
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigTest {

    private fun tempConfigFile(json: String): File {
        val file = Files.createTempFile("comprehension-config-test", ".json").toFile()
        file.writeText(json)
        return file
    }

    @Test
    fun `unset tunables fall back to the prototype's defaults`() {
        val config = tempConfigFile("""{"modules": {"core": ["src/core/*"]}}""")
        val loaded = loadConfig(config.path)

        assertEquals(1.0, loaded.scoring.hChurn)
        assertEquals(180.0, loaded.scoring.hWallDays)
        assertEquals(2.0, loaded.scoring.quiescenceStretch)
        assertEquals(4.0, loaded.scoring.churnCap)
        assertEquals(400.0, loaded.scoring.satLines)
        assertEquals(0.5, loaded.scoring.thetaPerson)
        assertEquals(2, loaded.scoring.thetaCovered)
        assertEquals(
            mapOf(EvidenceType.AUTHORED to 1.0, EvidenceType.AGENT_MEDIATED to 0.3, EvidenceType.ATTESTED to 0.6),
            loaded.scoring.weights,
        )
        assertEquals(emptySet(), loaded.scoring.departed)

        assertEquals("HEAD", loaded.ingest.ref)
        assertEquals(
            listOf("noreply@anthropic.com", "copilot@github.com", "cursoragent@cursor.com"),
            loaded.ingest.agentTrailerEmails,
        )
        assertEquals(listOf("[bot]"), loaded.ingest.botAuthorPatterns)
        assertEquals(emptyMap(), loaded.ingest.identity)
        assertEquals(400.0, loaded.ingest.satLines)
        assertEquals(emptyList(), loaded.critical)
    }

    @Test
    fun `a partial weights override merges key-by-key, leaving the rest at default`() {
        // Ports load_config's `cfg[k].update(v)` for nested dicts: overriding
        // one weight must not blow away the other two.
        val config = tempConfigFile(
            """
            {"modules": {"core": ["src/core/*"]}, "weights": {"AGENT_MEDIATED": 0.5}}
            """.trimIndent(),
        )
        val loaded = loadConfig(config.path)

        assertEquals(
            mapOf(EvidenceType.AUTHORED to 1.0, EvidenceType.AGENT_MEDIATED to 0.5, EvidenceType.ATTESTED to 0.6),
            loaded.scoring.weights,
        )
        // order-preservation is the entire reason for the JsonObject-backed
        // dependency choice (see CLAUDE.md's "JSON: read with a library,
        // write by hand") -- an override must not reorder the map, only
        // update the overridden key's value in place.
        assertEquals(
            listOf(EvidenceType.AUTHORED, EvidenceType.AGENT_MEDIATED, EvidenceType.ATTESTED),
            loaded.scoring.weights.keys.toList(),
        )
    }

    @Test
    fun `a non-object override replaces the default wholesale, not merged`() {
        val config = tempConfigFile(
            """
            {"modules": {"core": ["src/core/*"]}, "departed": ["Alice"], "theta_covered": 3}
            """.trimIndent(),
        )
        val loaded = loadConfig(config.path)

        assertEquals(setOf("Alice"), loaded.scoring.departed)
        assertEquals(3, loaded.scoring.thetaCovered)
    }

    @Test
    fun `missing modules fails loudly`() {
        val config = tempConfigFile("""{"theta_covered": 3}""")
        val error = assertFailsWith<IllegalArgumentException> { loadConfig(config.path) }
        assertTrue(error.message!!.contains("'modules'"), error.message)
    }

    @Test
    fun `an empty modules object also fails loudly`() {
        val config = tempConfigFile("""{"modules": {}}""")
        assertFailsWith<IllegalArgumentException> { loadConfig(config.path) }
    }

    @Test
    fun `two modules whose globs both match one path resolve by the config file's key order`() {
        // moduleOf is first-match-wins over IngestConfig.modules' iteration
        // order (see IngestConfig's KDoc) -- this is the load-bearing
        // guarantee the JSON parser's key-order preservation exists for.
        val config = tempConfigFile(
            """
            {"modules": {"first": ["src/shared*"], "second": ["src/shared.txt"]}}
            """.trimIndent(),
        )
        val loaded = loadConfig(config.path)
        assertEquals(listOf("first", "second"), loaded.ingest.modules.keys.toList())
        assertEquals("first", moduleOf(loaded.ingest, "src/shared.txt"))
    }

    @Test
    fun `reversing the config file's module order flips which module wins`() {
        val config = tempConfigFile(
            """
            {"modules": {"second": ["src/shared.txt"], "first": ["src/shared*"]}}
            """.trimIndent(),
        )
        val loaded = loadConfig(config.path)
        assertEquals("second", moduleOf(loaded.ingest, "src/shared.txt"))
    }

    @Test
    fun `express-config json produces an equivalent config to the prototype's merged cfg`() {
        // Relies on Gradle running tests with the project root as the working
        // directory (true here and in CI) -- assert that explicitly so a
        // future test-runner change fails with a clear message instead of a
        // confusing FileNotFoundException from deep inside loadConfig.
        val configPath = "prototype/express-config.json"
        assertTrue(File(configPath).exists(), "expected $configPath relative to the working directory")
        val loaded = loadConfig(configPath)

        assertEquals(
            linkedMapOf(
                "application" to listOf("lib/application.js", "lib/express.js"),
                "request" to listOf("lib/request.js"),
                "response" to listOf("lib/response.js"),
                "view" to listOf("lib/view.js"),
                "utils" to listOf("lib/utils.js"),
                "router" to listOf("lib/router*", "lib/router/*"),
                "middleware" to listOf("lib/middleware/*"),
                "tests" to listOf("test/*", "test/**/*"),
            ),
            loaded.ingest.modules,
        )
        assertEquals(listOf("application", "response", "router"), loaded.critical)
        // express-config.json overrides no tunables -- scoring config is all defaults.
        assertEquals(180.0, loaded.scoring.hWallDays)
        assertEquals(2, loaded.scoring.thetaCovered)

        assertEquals("router", moduleOf(loaded.ingest, "lib/router/index.js"))
    }
}
