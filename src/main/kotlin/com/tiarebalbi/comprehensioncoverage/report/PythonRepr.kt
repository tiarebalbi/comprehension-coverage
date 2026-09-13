package com.tiarebalbi.comprehensioncoverage.report

import kotlin.math.abs

/**
 * Replicates Python's float `repr()` (what `json.dumps` and `str()` both
 * use for a float) exactly. Java's `Double.toString()` has produced the
 * same *shortest round-tripping digit sequence* Python's algorithm does
 * since JDK 19 -- the two only diverge in notation policy: Java switches
 * to scientific notation below `1e-3` and at/above `1e7`; Python's
 * thresholds are `1e-4` and `1e16`, and the exponent is spelled
 * differently (`1e-05` vs `1.0E-5`). This re-derives the significant
 * digits and decimal exponent from Java's string (whichever notation it
 * chose -- both carry the same digits losslessly) and reformats using
 * Python's own rules.
 */
fun pythonRepr(x: Double): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
    if (x == 0.0) return if (1.0 / x < 0) "-0.0" else "0.0"

    val negative = x < 0
    val (digits, exponent) = significantDigits(abs(x))
    return formatPythonStyle(negative, digits, exponent)
}

/**
 * Parses Java's shortest-round-trip `toString()` into (significant digits
 * with no leading/trailing zeros, decimal exponent of the first digit) --
 * i.e. `value == 0.<digits> * 10^(exponent + 1)`, or equivalently
 * `d.igits * 10^exponent` with the point after the first digit.
 */
private fun significantDigits(x: Double): Pair<String, Int> {
    val s = x.toString()
    val eIdx = s.indexOf('E')
    val mantissa = if (eIdx >= 0) s.substring(0, eIdx) else s
    val javaExp = if (eIdx >= 0) s.substring(eIdx + 1).toInt() else 0

    val dotIdx = mantissa.indexOf('.')
    val intPart = mantissa.substring(0, dotIdx)
    val fracPart = mantissa.substring(dotIdx + 1)
    val allDigits = intPart + fracPart
    var exponent = javaExp + (intPart.length - 1)

    val firstNonZero = allDigits.indexOfFirst { it != '0' }
    if (firstNonZero < 0) return "0" to 0 // unreachable: x == 0.0 handled by the caller
    exponent -= firstNonZero
    val digits = allDigits.substring(firstNonZero).trimEnd('0').ifEmpty { "0" }
    return digits to exponent
}

private fun formatPythonStyle(negative: Boolean, digits: String, exponent: Int): String {
    val sign = if (negative) "-" else ""
    return when {
        exponent < -4 || exponent >= 16 -> {
            val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
            val expSign = if (exponent < 0) "-" else "+"
            val expDigits = abs(exponent).toString().padStart(2, '0')
            "$sign${mantissa}e$expSign$expDigits"
        }
        exponent >= 0 -> {
            val intLen = exponent + 1
            if (digits.length <= intLen) {
                "$sign${digits.padEnd(intLen, '0')}.0"
            } else {
                "$sign${digits.substring(0, intLen)}.${digits.substring(intLen)}"
            }
        }
        else -> {
            val zeros = "0".repeat(-exponent - 1)
            "${sign}0.$zeros$digits"
        }
    }
}
