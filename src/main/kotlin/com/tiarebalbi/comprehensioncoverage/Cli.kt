package com.tiarebalbi.comprehensioncoverage

import com.tiarebalbi.comprehensioncoverage.config.loadConfig
import com.tiarebalbi.comprehensioncoverage.git.collect
import com.tiarebalbi.comprehensioncoverage.git.lastCommitTimestamp
import com.tiarebalbi.comprehensioncoverage.git.readAttestations
import com.tiarebalbi.comprehensioncoverage.git.readCommits
import com.tiarebalbi.comprehensioncoverage.report.dumpJson
import com.tiarebalbi.comprehensioncoverage.report.publicJson
import com.tiarebalbi.comprehensioncoverage.report.renderMap
import com.tiarebalbi.comprehensioncoverage.scoring.buildModuleMap
import com.tiarebalbi.comprehensioncoverage.scoring.scoreAll
import java.io.File
import java.io.PrintStream

/** Parsed `--repo`/`--config`/`--as-of`/`--json`/`--show-individuals` flags, mirroring `main()`'s `argparse` setup. */
data class CliArgs(
    val repo: String,
    val config: String,
    val asOf: String?,
    val jsonOut: String?,
    val showIndividuals: Boolean,
)

/**
 * `--gate`/break-glass (SPEC §5.2) is deliberately absent -- gate mode and
 * its exit codes are issue #9's scope, not this one's.
 */
fun parseArgs(args: Array<String>): CliArgs {
    var repo: String? = null
    var config: String? = null
    var asOf: String? = null
    var jsonOut: String? = null
    var showIndividuals = false

    fun valueFor(flag: String, i: Int): String =
        args.getOrNull(i) ?: throw IllegalArgumentException("$flag requires a value")

    var i = 0
    while (i < args.size) {
        when (val arg = args[i]) {
            "--repo" -> { i++; repo = valueFor(arg, i) }
            "--config" -> { i++; config = valueFor(arg, i) }
            "--as-of" -> { i++; asOf = valueFor(arg, i) }
            "--json" -> { i++; jsonOut = valueFor(arg, i) }
            "--show-individuals" -> showIndividuals = true
            else -> throw IllegalArgumentException("unrecognized argument: $arg")
        }
        i++
    }

    return CliArgs(
        repo = repo ?: throw IllegalArgumentException("missing required argument: --repo"),
        config = config ?: throw IllegalArgumentException("missing required argument: --config"),
        asOf = asOf,
        jsonOut = jsonOut,
        showIndividuals = showIndividuals,
    )
}

/**
 * Wires the pipeline exactly as `main()` does: loadConfig -> readCommits ->
 * readAttestations -> collect -> scoreAll -> buildModuleMap -> render/JSON.
 * Takes `out`/`err` so tests can capture output without touching real
 * stdout/stderr or calling [kotlin.system.exitProcess]. Exit codes here
 * cover basic CLI/config-error failure (mirroring `sys.exit(str(e))`) --
 * `--gate`'s DARK/AT_RISK exit codes (SPEC §5.2) are issue #9's.
 */
fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        err.println(e.message)
        return 2
    }

    val loaded = try {
        loadConfig(parsed.config)
    } catch (e: Exception) {
        err.println(e.message)
        return 1
    }

    // Narrowed to wrap only parseAsOf: NumberFormatException (a possible
    // failure of lastCommitTimestamp's `%at` parsing) is itself an
    // IllegalArgumentException, and catching it here too would report a
    // malformed git timestamp through --as-of's error message.
    val asOf = if (parsed.asOf != null) {
        try {
            parseAsOf(parsed.asOf)
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            return 1
        }
    } else {
        lastCommitTimestamp(parsed.repo, loaded.ingest.ref)
    }

    val commits = readCommits(parsed.repo, loaded.ingest.ref)
    val attestations = readAttestations(parsed.repo, loaded.ingest)
    val (events, sizes) = collect(loaded.ingest, commits, asOf, attestations)
    val scores = scoreAll(loaded.scoring, events, sizes, asOf)
    val modules = buildModuleMap(loaded.scoring, scores, loaded.ingest.modules.keys)

    out.println(renderMap(modules, parsed.showIndividuals))
    parsed.jsonOut?.let { File(it).writeText(dumpJson(publicJson(asOf, modules))) }
    return 0
}
