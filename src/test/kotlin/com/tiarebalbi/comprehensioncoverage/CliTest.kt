package com.tiarebalbi.comprehensioncoverage

import com.tiarebalbi.comprehensioncoverage.git.commit
import com.tiarebalbi.comprehensioncoverage.git.tempGitRepo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val T0 = 1700000000L
private const val DAY = 86400L

class CliTest {

    private fun buildSyntheticRepo(root: File) {
        // Same recipe as #6/#7's SyntheticRepoGoldenTest and the prototype's
        // build_synthetic_repo.
        File(root, "src/core").mkdirs()
        File(root, "src/web").mkdirs()
        File(root, "src/core/engine.txt").writeText("line\n".repeat(400))
        commit(root, "Alice", "alice@example.com", T0, "core: initial engine")
        File(root, "src/web/app.txt").writeText("line\n".repeat(200))
        commit(root, "Bob", "bob@example.com", T0 + 30 * DAY, "web: initial app")
        File(root, "src/core/engine.txt").writeText("new\n".repeat(500))
        commit(
            root, "Tiare", "me@tiarebalbi.com", T0 + 300 * DAY,
            "core: agent rewrite\n\nCo-Authored-By: Claude <noreply@anthropic.com>",
        )
        File(root, "src/web/app.txt").appendText("more\n".repeat(150))
        commit(root, "Bob", "bob@example.com", T0 + 320 * DAY, "web: feature by hand")
        File(root, ".comprehension").mkdirs()
        File(root, ".comprehension/attestations.yaml").writeText(
            "- email: dana@example.com\n  module: web\n  timestamp: \"${Instant.ofEpochSecond(T0 + 325 * DAY)}\"\n",
        )
    }

    /**
     * Transcribed and verified from a live run of `prototype/comprehension.py`
     * against this exact repo/config on 2026-09-13:
     *
     *   python3 prototype/comprehension.py --repo <tmp> --config <cfg.json> \
     *     --as-of 2024-10-09T22:13:20Z --json out.json --show-individuals
     *
     * stdout and `out.json`'s bytes below are copied verbatim from that run
     * (see the PR description for the full transcript). This is the CLI's
     * real `--json` shape (`main()`'s `public` dict) -- distinct from
     * `fixtures/golden-synthetic.json`'s shape, which also carries a
     * `scores` section that only the *test* fixture writer produces, never
     * `main()` itself. GoldenFixtureJsonTest covers that shape instead.
     */
    @Test
    fun `CLI output matches a live prototype run byte for byte`() {
        val repo = tempGitRepo()
        val configFile = Files.createTempFile("cli-test-config", ".json").toFile().apply { deleteOnExit() }
        val jsonOut = Files.createTempFile("cli-test-out", ".json").toFile().apply { deleteOnExit() }
        try {
            buildSyntheticRepo(repo)
            configFile.writeText(
                """{"modules": {"core": ["src/core/*"], "web": ["src/web/*"]}, "identity": {"dana@example.com": "Dana"}}""",
            )
            val stdout = ByteArrayOutputStream()

            val exitCode = runCli(
                arrayOf(
                    "--repo", repo.path, "--config", configFile.path,
                    "--as-of", "2024-10-09T22:13:20Z", "--json", jsonOut.path, "--show-individuals",
                ),
                out = PrintStream(stdout),
            )

            assertEquals(0, exitCode)
            assertEquals(
                "  core  ░░░░░░░░░░  0 comprehender(s)  DARK\n" +
                    "          Tiare: 0.2887\n" +
                    "          Alice: 0.1568\n" +
                    "  web   ██████████  2 comprehender(s)  COVERED\n" +
                    "          Bob: 1.0\n" +
                    "          Dana: 0.5962\n",
                stdout.toString(),
            )
            assertEquals(
                "{\n" +
                    "  \"as_of\": 1728512000,\n" +
                    "  \"modules\": {\n" +
                    "    \"core\": {\n" +
                    "      \"comprehenders\": 0,\n" +
                    "      \"status\": \"DARK\"\n" +
                    "    },\n" +
                    "    \"web\": {\n" +
                    "      \"comprehenders\": 2,\n" +
                    "      \"status\": \"COVERED\"\n" +
                    "    }\n" +
                    "  }\n" +
                    "}",
                jsonOut.readText(),
            )
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `omitting --as-of falls back to the ref's last commit timestamp`() {
        val repo = tempGitRepo()
        val configFile = Files.createTempFile("cli-test-config", ".json").toFile().apply { deleteOnExit() }
        val jsonOut = Files.createTempFile("cli-test-out", ".json").toFile().apply { deleteOnExit() }
        try {
            File(repo, "src/core").mkdirs()
            File(repo, "src/core/a.txt").writeText("a\n")
            commit(repo, "Alice", "alice@example.com", T0, "first")
            configFile.writeText("""{"modules": {"core": ["src/core/*"]}}""")

            val exitCode = runCli(
                arrayOf("--repo", repo.path, "--config", configFile.path, "--json", jsonOut.path),
                out = PrintStream(ByteArrayOutputStream()),
            )

            assertEquals(0, exitCode)
            assertTrue(jsonOut.readText().contains("\"as_of\": $T0"), jsonOut.readText())
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test
    fun `missing required argument fails with exit code 2`() {
        val err = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf("--config", "whatever.json"), err = PrintStream(err))
        assertEquals(2, exitCode)
        assertTrue(err.toString().contains("--repo"), err.toString())
    }

    @Test
    fun `an unloadable config fails with exit code 1, not a stack trace`() {
        val missingConfig = "does-not-exist-${System.nanoTime()}.json"
        val err = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf("--repo", ".", "--config", missingConfig), err = PrintStream(err))
        assertEquals(1, exitCode)
    }
}
