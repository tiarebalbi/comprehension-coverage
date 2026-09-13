package com.tiarebalbi.comprehensioncoverage.report

import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleStatus

private val BAR = mapOf(
    ModuleStatus.COVERED to "█",
    ModuleStatus.AT_RISK to "▓",
    ModuleStatus.DARK to "░",
)

/**
 * Ports the prototype's `render`/`BAR` exactly: column width is
 * `max(len(m) for m in modules) + 2`, each module gets a 10-char status
 * bar, and (with `showIndividuals`) each module's top-5 people by score
 * are listed below it, indented to the same column.
 */
fun renderMap(modules: Map<String, ModuleReport>, showIndividuals: Boolean): String {
    val width = modules.keys.maxOf { it.length } + 2
    val lines = mutableListOf<String>()
    for ((mod, report) in modules) {
        val bar = BAR.getValue(report.status).repeat(10)
        lines.add("  ${mod.padEnd(width)}$bar  ${report.comprehenders} comprehender(s)  ${report.status.name}")
        if (showIndividuals) {
            for ((person, score) in report.people.take(5)) {
                lines.add("  ${"".padEnd(width)}  $person: ${pythonRepr(score)}")
            }
        }
    }
    return lines.joinToString("\n")
}
