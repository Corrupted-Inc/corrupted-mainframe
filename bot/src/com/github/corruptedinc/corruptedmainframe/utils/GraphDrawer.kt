package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.commands.ThrustCurve
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import java.io.File
import kotlin.math.*

class Series(val yValues: DoubleArray, val name: String, val color: RGB)

class Graph(val title: String, val xAxisTitle: String, val yAxisTitle: String, val xValues: DoubleArray, val series: List<Series>) {
    suspend fun render(bot: Bot): ByteArray? {
        val svg = genSVG()
        return bot.pathDrawer.render(StringBuilder(svg))
    }

    private val screenspaceMinX = 60.0
    private val screenspaceMaxX = 380.0
    private val screenspaceMinY = 250.0
    private val screenspaceMaxY = 40.0

    private fun transformX(xRange: DoubleRange, value: Double) = ((value - xRange.start) / (xRange.endInclusive - xRange.start)) * (screenspaceMaxX - screenspaceMinX) + screenspaceMinX
    private fun transformY(yRange: DoubleRange, value: Double) = ((value - yRange.start) / (yRange.endInclusive - yRange.start)) * (screenspaceMaxY - screenspaceMinY) + screenspaceMinY

    fun genSVG(): String {
        var xMin = xValues.min()
        var xMax = xValues.max()
        val diff = (xMax - xMin)

//        xMin -= diff * 0.1
        xMax += diff * 0.1

        var yMin = series.minOf { it.yValues.min() }
        var yMax = series.maxOf { it.yValues.max() }
        val diff2 = (yMax - yMin)

//        yMin -= diff2 * 0.1
        yMax += diff2 * 0.1

        val verticalGridlines = gridlines(yMin..yMax, 6)
        val horizontalGridlines = gridlines(xMin..xMax, 10)

        val gridlineColor = "#cccccc"

        return """
            <svg viewBox="0 0 400 300" width="800" height="600" xmlns="http://www.w3.org/2000/svg">
                <!-- title -->
                <text x="200" y="30" font-size="20" text-anchor="middle" font-family="sans-serif" fill="$gridlineColor">$title</text>

                <!-- axis lines -->
                <path d="M$screenspaceMinX,40 L$screenspaceMinX,250 L380,250" fill="none" stroke="$gridlineColor" stroke-width="1.5" />

                <!-- vertical axis gridlines -->
                ${verticalGridlines.joinToString("") { value -> val pos = transformY(yMin..yMax, value); "<path d=\"M$screenspaceMinX,$pos L380,$pos\" stroke=\"gray\" stroke-width=\"1\" /><text x=\"${screenspaceMinX - 3.0}\" y=\"$pos\" dominant-baseline=\"middle\" text-anchor=\"end\" font-size=\"12\" font-family=\"sans-serif\" fill=\"$gridlineColor\">${value.sensibleString()}</text>" } }
                <!-- vertical axis label -->
                <text x="20" y="150" transform="rotate(-90, 20, 150)" text-anchor="middle" font-family="sans-serif" fill="$gridlineColor">$yAxisTitle</text>

                <!-- horizontal axis gridlines -->
                ${horizontalGridlines.joinToString("") { value -> val pos = transformX(xMin..xMax, value); "<path d=\"M$pos,250 L$pos,40\" stroke=\"gray\" stroke-width=\"1\" /><text x=\"$pos\" y=\"265\" text-anchor=\"end\" font-size=\"12\" font-family=\"sans-serif\" fill=\"$gridlineColor\">${value.sensibleString()}</text>" } }
                <!-- horizontal axis label -->
                <text x="200" y="290" text-anchor="middle" font-family="sans-serif" fill="$gridlineColor">$xAxisTitle</text>

                <!-- series -->
                ${series.joinToString("") { "<path d=\"M$screenspaceMinX,${transformY(yMin..yMax, it.yValues[0])} ${it.yValues.withIndex().drop(1).joinToString(" ") { (idx, y) -> val x = xValues[idx]; "L${transformX(xMin..xMax, x)},${transformY(yMin..yMax, y)}" } } \" fill=\"none\" stroke=\"${it.color.toString().dropLast(2)}\" stroke-width=\"2\" />" } }
            </svg>
        """.trimIndent()
    }
}

fun bestMatch(desiredCount: Int, maxDiff: Int, func: (Int) -> Pair<Double, Int>): Pair<Double, Int> {
    return (-maxDiff..maxDiff).map(func).minBy { (it.second - desiredCount).absoluteValue }
}

fun gridlines(range: DoubleRange, desiredCount: Int): List<Double> {
    val fullRange = range.endInclusive - range.start
    val initialStep = fullRange / desiredCount.toDouble()
    // try rounding to nearest power of 10, nearest power of 10 / 2, to nearest power of 2

    // power of 10
    val (powerOfTenStep, powerOfTenCount) = bestMatch(desiredCount, 3) { val step = 10.0.pow(log10(initialStep).roundToInt() - it); Pair(step, (fullRange / step).roundToInt()) }
//    val powerOfTenStep = 10.0.pow(log10(initialStep).roundToInt() - 1)
//    val powerOfTenCount = (initialStep / powerOfTenStep).roundToInt()

    // power of 10 / 2
    val (powerOfTenHalfStep, powerOfTenHalfCount) = bestMatch(desiredCount, 3) { val step = 10.0.pow(log10(initialStep).roundToInt() - it) / 2.0; Pair(step, (fullRange / step).roundToInt()) }
//    val powerOfTenHalfStep = 10.0.pow(log10(initialStep).roundToInt() - 1) / 2.0
//    val powerOfTenHalfCount = (initialStep / powerOfTenStep).roundToInt()

    // power of 2
    val (powerOfTwoStep, powerOfTwoCount) = bestMatch(desiredCount, 3) { val step = 2.0.pow(log10(initialStep).roundToInt() - it); Pair(step, (fullRange / step).roundToInt()) }
//    val powerOfTwoStep = 2.0.pow(log2(initialStep).roundToInt() - 2)
//    val powerOfTwoCount = (initialStep / powerOfTwoStep).roundToInt()

    val step = listOf(powerOfTenStep to powerOfTenCount, powerOfTenHalfStep to powerOfTenHalfCount, powerOfTwoStep to powerOfTwoCount)
        .minBy { (it.second - desiredCount).absoluteValue }.first

    val start = (range.start / step).roundToInt().toDouble() * step - step * 2

    val lines = mutableListOf<Double>()

    for (i in 0..desiredCount * 2) {
        lines.add(start + (step * i.toDouble()))
    }

    lines.retainAll { it in range }

//    println("generating $desiredCount for range $range")
//    println("${lines.size} $lines")

    return lines
}

//fun main() {
//    val found = ThrustCurve().motors.find { it.properName == "L1000" }!!
//    val xValues = found.data.map { it[0] }.toDoubleArray()
//    val yValues = found.data.map { it[1] }.toDoubleArray()
//
//    val graph = Graph(found.name, "time (s)", "thrust (N)", xValues, listOf(Series(yValues, "", RGB(30U, 30U, 255U))))
//
//    val svg = graph.genSVG()
//
//    println(svg)
//
//    File("/tmp/aaa.svg").writeText(svg)
//
////    val series = Series(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 6.0), "asdf", RGB(255U, 0U, 0U))
////    println(Graph("asdf", "x axis", "y axis", doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0), listOf(series)).genSVG())
//}
