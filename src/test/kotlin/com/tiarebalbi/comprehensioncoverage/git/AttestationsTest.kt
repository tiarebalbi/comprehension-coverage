package com.tiarebalbi.comprehensioncoverage.git

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttestationsTest {

    private fun writeAttestations(repo: File, vararg records: Triple<String, String, String>) {
        File(repo, ".comprehension").mkdirs()
        val lines = records.flatMap { (email, module, timestamp) ->
            listOf("- email: $email", "  module: $module", "  timestamp: \"$timestamp\"")
        }
        File(repo, ".comprehension/attestations.yaml").writeText(lines.joinToString("\n") + "\n")
    }

    private fun tempDir(): File = Files.createTempDirectory("attestations-test").toFile()

    @Test
    fun `missing file returns an empty list`() {
        val repo = tempDir()
        try {
            assertEquals(emptyList(), readAttestations(repo.path, IngestConfig()))
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `email is lowercased and resolution is deferred to collect`() {
        val repo = tempDir()
        try {
            val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
            writeAttestations(repo, Triple("Dana@Example.com", "core", "2026-09-08T00:00:00Z"))
            assertEquals(
                listOf(Attestation("dana@example.com", "core", 1788825600L)),
                readAttestations(repo.path, config),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `tz-naive timestamp is rejected`() {
        val repo = tempDir()
        try {
            val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
            writeAttestations(repo, Triple("dana@example.com", "core", "2026-09-08T00:00:00"))
            assertFailsWith<IllegalArgumentException> { readAttestations(repo.path, config) }
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `record missing a required key is rejected`() {
        val repo = tempDir()
        try {
            File(repo, ".comprehension").mkdirs()
            File(repo, ".comprehension/attestations.yaml").writeText(
                "- email: dana@example.com\n  timestamp: \"2026-09-08T00:00:00Z\"\n"
            )
            assertFailsWith<IllegalArgumentException> {
                readAttestations(repo.path, IngestConfig(modules = mapOf("core" to listOf("src/core/*"))))
            }
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `unconfigured module is skipped with a stderr warning`() {
        val repo = tempDir()
        try {
            val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
            writeAttestations(repo, Triple("dana@example.com", "nonexistent", "2026-09-08T00:00:00Z"))

            val originalErr = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured))
            val result = try {
                readAttestations(repo.path, config)
            } finally {
                System.setErr(originalErr)
            }

            assertEquals(emptyList(), result)
            val warning = captured.toString()
            assertTrue(warning.contains("attestations.yaml") && warning.contains("nonexistent"), warning)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `a record with a reserved type is skipped with a warning, not folded into evidence`() {
        val repo = tempDir()
        try {
            val config = IngestConfig(modules = mapOf("core" to listOf("src/core/*")))
            File(repo, ".comprehension").mkdirs()
            File(repo, ".comprehension/attestations.yaml").writeText(
                "- email: oncall@example.com\n" +
                    "  module: core\n" +
                    "  timestamp: \"2026-09-08T00:00:00Z\"\n" +
                    "  type: INCIDENT_DIAGNOSED\n" +
                    "  incident_ref: \"INC-7\"\n"
            )

            val originalErr = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured))
            val result = try {
                readAttestations(repo.path, config)
            } finally {
                System.setErr(originalErr)
            }

            assertEquals(emptyList(), result)
            val warning = captured.toString()
            assertTrue(warning.contains("INCIDENT_DIAGNOSED") && warning.contains("reserved"), warning)
        } finally {
            repo.deleteRecursively()
        }
    }
}
