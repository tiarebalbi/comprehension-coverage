package com.tiarebalbi.comprehensioncoverage.report

import com.tiarebalbi.comprehensioncoverage.git.IngestConfig
import com.tiarebalbi.comprehensioncoverage.git.collect
import com.tiarebalbi.comprehensioncoverage.git.commit
import com.tiarebalbi.comprehensioncoverage.git.readAttestations
import com.tiarebalbi.comprehensioncoverage.git.readCommits
import com.tiarebalbi.comprehensioncoverage.git.tempGitRepo
import com.tiarebalbi.comprehensioncoverage.scoring.ScoringConfig
import com.tiarebalbi.comprehensioncoverage.scoring.buildModuleMap
import com.tiarebalbi.comprehensioncoverage.scoring.pythonRound
import com.tiarebalbi.comprehensioncoverage.scoring.scoreAll
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val T0 = 1700000000L
private const val DAY = 86400L

/**
 * Issue #8's Done criteria: "JSON output for the golden-synthetic scenario
 * is byte-identical to fixtures/golden-synthetic.json." That fixture's
 * shape (`as_of`, `modules`, and a `scores` section keyed by
 * `"person|module"` at 6-decimal rounding) is produced only by
 * `test_comprehension.py`'s own fixture writer -- never by `main()`'s real
 * `--json` output, which omits `scores` entirely (see `publicJson`'s KDoc
 * and CliTest, which covers *that* shape against a live `main()` run
 * instead). This test reproduces the fixture writer's shape directly, not
 * `runCli`'s, since they are genuinely different JSON documents.
 */
class GoldenFixtureJsonTest {

    private fun buildSyntheticRepo(root: File) {
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

    @Test
    fun `full pipeline reproduces fixtures golden-synthetic json byte for byte`() {
        val repo = tempGitRepo()
        try {
            buildSyntheticRepo(repo)
            val ingestConfig = IngestConfig(
                modules = mapOf("core" to listOf("src/core/*"), "web" to listOf("src/web/*")),
                identity = mapOf("dana@example.com" to "Dana"),
            )
            val scoringConfig = ScoringConfig()
            val commits = readCommits(repo.path)
            val attestations = readAttestations(repo.path, ingestConfig)
            val asOf = T0 + 330 * DAY
            val (events, sizes) = collect(ingestConfig, commits, asOf, attestations)
            val scores = scoreAll(scoringConfig, events, sizes, asOf)
            val modules = buildModuleMap(scoringConfig, scores, ingestConfig.modules.keys)

            val fixtureShape = JsonValue.Obj(
                mapOf(
                    "as_of" to JsonValue.IntNum(asOf),
                    "modules" to JsonValue.Obj(
                        modules.mapValues { (_, report) ->
                            JsonValue.Obj(
                                mapOf(
                                    "comprehenders" to JsonValue.IntNum(report.comprehenders.toLong()),
                                    "status" to JsonValue.Str(report.status.name),
                                ),
                            )
                        },
                    ),
                    "scores" to JsonValue.Obj(
                        scores.entries.associate { (key, raw) ->
                            "${key.first}|${key.second}" to JsonValue.Num(pythonRound(raw, 6))
                        },
                    ),
                ),
            )

            val actual = dumpJson(fixtureShape)
            // Relies on Gradle's project-root working directory, same as
            // ConfigTest's express-config.json read (#7) -- assert it
            // explicitly so a runner change fails legibly.
            val fixturePath = "fixtures/golden-synthetic.json"
            assertTrue(File(fixturePath).exists(), "expected $fixturePath relative to the working directory")
            val expected = File(fixturePath).readText()
            assertEquals(expected, actual)
        } finally {
            repo.deleteRecursively()
        }
    }
}
