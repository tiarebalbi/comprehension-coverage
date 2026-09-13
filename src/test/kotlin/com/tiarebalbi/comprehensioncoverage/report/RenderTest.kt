package com.tiarebalbi.comprehensioncoverage.report

import com.tiarebalbi.comprehensioncoverage.scoring.ModuleReport
import com.tiarebalbi.comprehensioncoverage.scoring.ModuleStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderTest {

    @Test
    fun `column width comes from the longest module name plus 2`() {
        val modules = linkedMapOf(
            "core" to ModuleReport(ModuleStatus.DARK, 0, emptyList()),
            "web" to ModuleReport(ModuleStatus.COVERED, 2, emptyList()),
        )
        val expected = "  core  ░░░░░░░░░░  0 comprehender(s)  DARK\n" +
            "  web   ██████████  2 comprehender(s)  COVERED"
        assertEquals(expected, renderMap(modules, showIndividuals = false))
    }

    @Test
    fun `show-individuals lists people below their module, indented to the same column`() {
        val modules = linkedMapOf(
            "core" to ModuleReport(ModuleStatus.AT_RISK, 1, listOf("Alice" to 0.6)),
        )
        val expected = "  core  ▓▓▓▓▓▓▓▓▓▓  1 comprehender(s)  AT_RISK\n" +
            "          Alice: 0.6"
        assertEquals(expected, renderMap(modules, showIndividuals = true))
    }

    @Test
    fun `only the top 5 people are listed, even when more hold evidence`() {
        val people = (1..7).map { "Person$it" to (1.0 - it * 0.01) }
        val modules = linkedMapOf("core" to ModuleReport(ModuleStatus.COVERED, 7, people))

        val lines = renderMap(modules, showIndividuals = true).lines()
        val personLines = lines.drop(1) // drop the module summary line
        assertEquals(5, personLines.size)
        assertEquals(people.take(5).map { it.first }, personLines.map { it.trim().substringBefore(":") })
    }

    @Test
    fun `show-individuals is off by default output (people omitted)`() {
        val modules = linkedMapOf("core" to ModuleReport(ModuleStatus.DARK, 0, listOf("Alice" to 0.1)))
        assertEquals(1, renderMap(modules, showIndividuals = false).lines().size)
    }
}
