package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.commands.Leveling.Companion.LEVEL_BAR_WIDTH
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

fun Duration.toHumanReadable(): String {
    val parts = mutableListOf<String>()
    if (toDaysPart() > 0) parts.add("${toDaysPart()} days")
    if (toHoursPart() > 0) parts.add("${toHoursPart()} hours")
    if (toMinutesPart() > 0) parts.add("${toMinutesPart()} minutes")
    if (toSecondsPart() > 0) parts.add("${toSecondsPart()} seconds")

    return parts.joinToString()
//    return toString().removePrefix("PT")
//        .replace("(\\d[HMS])(?!$)".toRegex(), "$1 ").lowercase(Locale.getDefault())
}

fun CharSequence.DDHHMMSStoDuration(): kotlin.time.Duration? {
    if (any { it !in '0'..'9' && it != ':' && it != '.' }) return null
    val parts = split(":").toMutableList()
    var secs = 0.0
    // seconds
    parts.removeLastOrNull()?.let { val s = it.toDoubleOrNull() ?: return null; if (s > 60.0) return null; secs += s }
    // minutes
    parts.removeLastOrNull()?.let { val s = it.toIntOrNull() ?: return null; if (s > 60) return null; secs += s * 60 }
    // hours
    parts.removeLastOrNull()?.let { val s = it.toIntOrNull() ?: return null; if (s > 24) return null; secs += s * 3600 }
    // days
    parts.removeLastOrNull()?.let { val s = it.toIntOrNull() ?: return null; secs += s * 86400 }
    if (parts.isNotEmpty()) return null

    return secs.seconds
}

@JvmName("levenshtein1")
fun CharSequence.levenshtein(other: CharSequence) = levenshtein(this, other)

// from https://gist.github.com/ademar111190/34d3de41308389a0d0d8
@SuppressWarnings("ReturnCount")
fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
    if(lhs == rhs) { return 0 }
    if(lhs.isEmpty()) { return rhs.length }
    if(rhs.isEmpty()) { return lhs.length }

    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1 until rhsLength) {
        newCost[0] = i

        for (j in 1 until lhsLength) {
            val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1

            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }

        val swap = cost
        cost = newCost
        newCost = swap
    }

    return cost[lhsLength - 1]
}

fun biasedLevenshtein(x: String, y: String): Float {
    val dp = Array(x.length + 1) { IntArray(y.length + 1) }
    for (i in 0..x.length) {
        for (j in 0..y.length) {
            if (i == 0) {
                dp[i][j] = j
            } else if (j == 0) {
                dp[i][j] = i
            } else {
                dp[i][j] = (dp[i - 1][j - 1]
                        + if (x[i - 1] == y[j - 1]) 0 else 1).coerceAtMost(dp[i - 1][j] + 1)
                    .coerceAtMost(dp[i][j - 1] + 1)
            }
        }
    }
    val output = dp[x.length][y.length]
    if (y.startsWith(x) || x.startsWith(y)) {
        return output / 3f
    }
    return if (y.contains(x) || x.contains(y)) {
        output / 1.5f
    } else output.toFloat()
}

fun biasedLevenshteinInsensitive(x: String, y: String): Float {
    return biasedLevenshtein(x.lowercase(Locale.getDefault()), y.lowercase(Locale.getDefault()))
}

fun String.containsAny(items: Iterable<String>) = items.any { it in this }

// from https://mkyong.com/java/how-to-delete-directory-in-java/ why isn't this built in
fun deleteDirectory(path: Path) {
    // read java doc, Files.walk need close the resources.
    // try-with-resources to ensure that the stream's open directories are closed
    Files.walk(path).use {
        it.sorted(Comparator.reverseOrder())
        .forEach(Files::delete)
    }
}

typealias DoubleRange = ClosedFloatingPointRange<Double>

data class RGB(val r: UByte, val g: UByte, val b: UByte, val a: UByte = 255U) {
    override fun toString(): String {
        return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}${a.toString(16).padStart(2, '0')}"
    }
}

inline fun <T> T.runIf(condition: Boolean, block: T.() -> T) = if (condition) block() else this

inline fun <T> T.runIf(condition: (T) -> Boolean, block: T.() -> T) = if (condition(this)) block() else this

fun ReplyCallbackAction.ephemeral() = setEphemeral(true)

typealias CmdCtx = CommandContext

typealias AtcmpCtx = AutocompleteContext

fun String.strp() = dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }

private val imprecisionRegex = "00000+\\d\\d?$".toRegex()
private val zeroRegex = "\\.0+$".toRegex()

fun Double.sensibleString() = toString().replace(imprecisionRegex, "").replace(zeroRegex, "")

fun bar(portion: Double, width: Int): String {
    val parts = " ▏▎▍▌▋▊▉█"
    val blocks = width * portion
    return (parts.last().toString().repeat(blocks.toInt()) + parts[((blocks - blocks.toInt().toDouble()) * 8).toInt()]).padEnd(LEVEL_BAR_WIDTH, ' ')
}
