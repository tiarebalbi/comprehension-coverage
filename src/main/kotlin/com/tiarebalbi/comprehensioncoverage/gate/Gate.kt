package com.tiarebalbi.comprehensioncoverage.gate

import com.tiarebalbi.comprehensioncoverage.config.GateConfig
import com.tiarebalbi.comprehensioncoverage.scoring.Evidence
import com.tiarebalbi.comprehensioncoverage.scoring.EvidenceType
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleStatus
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** Inverse of `parseAsOf` for a UTC unix timestamp -- ports the prototype's `format_iso`; used to stamp the break-glass stub from `asOf` (C6). */
fun formatIso(epochSeconds: Long): String = ISO_FORMAT.format(Instant.ofEpochSecond(epochSeconds))

/**
 * SPEC §5.2: "who last held evidence, how it decayed" for one failing
 * critical module. Ports the prototype's `remediation_text` exactly --
 * `person` is the module's strongest current scorer (`report.people` is
 * already sorted desc by score), not necessarily its sole comprehender,
 * since this same text covers both the DARK and the (configurable)
 * AT_RISK exit path. No numeric score is printed: C5 reserves per-person
 * scalars for `--show-individuals`; naming *who* is what §5.2 itself
 * requires, under C5's own "explicit flag, team-local use" carve-out for
 * `--gate`.
 *
 * "Last" is the event with the greatest `(timestamp, kind)`, `kind`
 * breaking a same-instant tie the same way `collect()`'s action stream
 * orders it (commits before attestations) -- computed explicitly rather
 * than relied on from `events`' list order, which happens to already be
 * chronological but isn't a contract this function should depend on.
 */
fun remediationText(module: String, report: ModuleReport?, events: List<Evidence>, asOf: Long): String {
    val people = report?.people.orEmpty()
    val person = people.firstOrNull()?.first
        ?: return "  $module: no evidence recorded for this module."
    val candidates = events.filter { it.module == module && it.person == person }
    val last = candidates.maxWithOrNull(
        compareBy({ it.timestamp }, { if (it.type == EvidenceType.ATTESTED) 1 else 0 })
    ) ?: return "  $module: $person holds the strongest remaining score, but no contributing event was found."
    val days = (asOf - last.timestamp) / 86400
    return "  $module: $person holds the strongest remaining evidence; last ${last.type} evidence ${days}d before as-of."
}

/**
 * SPEC §5.2 break-glass: appends one `INCIDENT_DIAGNOSED`-class stub
 * record per overridden critical module to
 * `.comprehension/attestations.yaml`, in the schema §2 defines plus
 * `type`/`incident_ref`. Timestamped from `asOf` -- the resolved as-of
 * instant, never the wall clock (C6).
 *
 * This is a declared interface, not an implemented one (SPEC §2):
 * `readAttestations` skips `type: INCIDENT_DIAGNOSED` records rather than
 * scoring them, so break-glass records that an override happened without
 * manufacturing comprehension by itself.
 */
fun writeBreakGlassStub(repo: String, modules: List<String>, person: String, incidentRef: String, asOf: Long) {
    val dir = File(repo, ".comprehension")
    dir.mkdirs()
    val file = File(dir, "attestations.yaml")
    var existing = if (file.isFile) file.readText() else ""
    if (existing.isNotEmpty() && !existing.endsWith("\n")) existing += "\n"
    val iso = formatIso(asOf)
    val blocks = modules.joinToString(separator = "") { mod ->
        "- email: $person\n" +
            "  module: $mod\n" +
            "  timestamp: \"$iso\"\n" +
            "  type: INCIDENT_DIAGNOSED\n" +
            "  incident_ref: \"$incidentRef\"\n"
    }
    file.writeText(existing + blocks)
}

/** `(exitCode, message)` -- `message` is `""` when `exitCode` is 0. Mirrors the prototype's `run_gate` return shape. */
data class GateOutcome(val exitCode: Int, val message: String)

/**
 * Evaluates `--gate`/`--break-glass` (SPEC §5.2) against an already-built
 * map. Split out of the CLI wiring so it's directly testable, the same
 * way [com.tiarebalbi.comprehensioncoverage.scoring.buildModuleMap] is,
 * without going through arg parsing or a real git subprocess.
 *
 * Throws [IllegalArgumentException] if `breakGlass` is set on a failing
 * gate but no person is resolvable -- the CLI turns that into an error exit.
 */
fun runGate(
    gateConfig: GateConfig,
    critical: List<String>,
    repo: String,
    modules: Map<String, ModuleReport>,
    events: List<Evidence>,
    asOf: Long,
    breakGlass: String?,
    breakGlassPerson: String?,
    thetaCovered: Int,
): GateOutcome {
    val dark = critical.filter { modules[it]?.status == ModuleStatus.DARK }
    val atRisk = if (gateConfig.exit1OnAtRisk) {
        critical.filter { modules[it]?.status == ModuleStatus.AT_RISK }
    } else {
        emptyList()
    }
    val failing = dark + atRisk
    val exitCode = if (dark.isNotEmpty()) 2 else if (atRisk.isNotEmpty()) 1 else 0
    if (exitCode == 0) return GateOutcome(0, "")

    // A blank string (from an explicitly-empty CLI flag or a blank config
    // value) must resolve the same as "not provided" -- never silently
    // accepted as a real person.
    fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    // break-glass only means something once there's a red exit to
    // downgrade -- a passing gate with --break-glass set is a no-op, not
    // an error, and doesn't demand a --break-glass-person (handled above:
    // exitCode == 0 already returned).
    val person = if (breakGlass != null) {
        clean(breakGlassPerson) ?: clean(gateConfig.breakGlassPerson)
            ?: throw IllegalArgumentException(
                "--break-glass requires --break-glass-person (or config " +
                    "'break_glass_person'): who is invoking this override? " +
                    "(never inferred from git config user.email)"
            )
    } else {
        null
    }

    val lines = mutableListOf<String>()
    if (dark.isNotEmpty()) {
        lines.add(
            "GATE: DARK critical module(s): ${dark.joinToString(", ")} — an agent change " +
                "here cannot merge without re-establishing comprehension."
        )
    }
    if (atRisk.isNotEmpty()) {
        lines.add(
            "GATE: AT_RISK critical module(s): ${atRisk.joinToString(", ")} — comprehension " +
                "exists but is below the bus-factor threshold (theta_covered=$thetaCovered)."
        )
    }
    for (m in failing) lines.add(remediationText(m, modules[m], events, asOf))
    var message = lines.joinToString("\n")

    if (breakGlass != null) {
        message += "\n\nBREAK-GLASS: '$breakGlass' — gate override by $person; " +
            "writing INCIDENT_DIAGNOSED stub(s) for: ${failing.joinToString(", ")}."
        writeBreakGlassStub(repo, failing, person!!, breakGlass, asOf)
        return GateOutcome(0, message)
    }
    return GateOutcome(exitCode, message)
}
