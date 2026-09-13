package com.tiarebalbi.comprehensioncoverage

import com.tiarebalbi.comprehensioncoverage.config.loadConfig
import com.tiarebalbi.comprehensioncoverage.gate.runGate
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

/** Parsed `--repo`/`--config`/`--as-of`/`--json`/`--gate`/`--break-glass`/`--break-glass-person`/`--show-individuals` flags, mirroring `main()`'s `argparse` setup. */
data class CliArgs(
    val repo: String,
    val config: String,
    val asOf: String?,
    val jsonOut: String?,
    val gate: Boolean,
    val breakGlass: String?,
    val breakGlassPerson: String?,
    val showIndividuals: Boolean,
)

/**
 * Structural parsing only (mirrors argparse's own errors: missing/unknown
 * flags, a flag missing its value) -- `runCli` maps failures here to exit
 * code 2. `--break-glass` semantic checks (requires `--gate`; non-empty
 * ref/person) are *not* here: the prototype validates those with
 * `sys.exit(str)` *after* `argparse.parse_args()` succeeds, which exits 1,
 * not 2 -- so `runCli` validates them post-parse too, to keep exit codes
 * identical across both implementations.
 */
fun parseArgs(args: Array<String>): CliArgs {
    var repo: String? = null
    var config: String? = null
    var asOf: String? = null
    var jsonOut: String? = null
    var gate = false
    var breakGlass: String? = null
    var breakGlassPerson: String? = null
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
            "--gate" -> gate = true
            "--break-glass" -> { i++; breakGlass = valueFor(arg, i) }
            "--break-glass-person" -> { i++; breakGlassPerson = valueFor(arg, i) }
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
        gate = gate,
        breakGlass = breakGlass,
        breakGlassPerson = breakGlassPerson,
        showIndividuals = showIndividuals,
    )
}

/**
 * Wires the pipeline exactly as `main()` does: loadConfig -> readCommits ->
 * readAttestations -> collect -> scoreAll -> buildModuleMap -> render/JSON
 * -> (optionally) `--gate`/`--break-glass` (SPEC §5.2). Takes `out`/`err`
 * so tests can capture output without touching real stdout/stderr or
 * calling [kotlin.system.exitProcess].
 */
fun runCli(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
    val parsed = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        err.println(e.message)
        return 2
    }

    if (parsed.breakGlass != null) {
        if (!parsed.gate) {
            err.println("--break-glass requires --gate")
            return 1
        }
        if (parsed.breakGlass.isBlank()) {
            err.println("--break-glass requires a non-empty incident ref")
            return 1
        }
    }
    if (parsed.breakGlassPerson != null && parsed.breakGlassPerson.isBlank()) {
        err.println("--break-glass-person requires a non-empty email")
        return 1
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

    if (parsed.gate) {
        val outcome = try {
            runGate(
                loaded.gate, loaded.critical, parsed.repo, modules, events, asOf,
                parsed.breakGlass, parsed.breakGlassPerson, loaded.scoring.thetaCovered,
            )
        } catch (e: IllegalArgumentException) {
            err.println(e.message)
            return 1
        }
        if (outcome.message.isNotEmpty()) out.println("\n${outcome.message}")
        return outcome.exitCode
    }
    return 0
}
