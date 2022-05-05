package com.github.corruptedinc.corruptedmainframe.utils

import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction
import java.nio.file.Files
import java.nio.file.Path
import java.text.ParseException
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.*
import kotlin.math.min


fun Duration.toHumanReadable(): String {
    return toString().removePrefix("PT")
        .replace("(\\d[HMS])(?!$)".toRegex(), "$1 ").lowercase(Locale.getDefault())
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

private val DATE_FORMAT_REGEXPS = listOf(
    "^\\d{8}$".toRegex() to "yyyyMMdd",
    "^\\d{1,2}-\\d{1,2}-\\d{4}$".toRegex() to "dd-MM-yyyy",
    "^\\d{4}-\\d{1,2}-\\d{1,2}$".toRegex() to "yyyy-MM-dd",
    "^\\d{1,2}/\\d{1,2}/\\d{4}$".toRegex() to "MM/dd/yyyy",
    "^\\d{4}/\\d{1,2}/\\d{1,2}$".toRegex() to "yyyy/MM/dd",
    "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}$".toRegex() to "dd MMM yyyy",
    "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}$".toRegex() to "dd MMMM yyyy",
    "^\\d{12}$".toRegex() to "yyyyMMddHHmm",
    "^\\d{8}\\s\\d{4}$".toRegex() to "yyyyMMdd HHmm",
    "^\\d{1,2}-\\d{1,2}-\\d{4}\\s\\d{1,2}:\\d{2}$".toRegex() to "dd-MM-yyyy HH:mm",
    "^\\d{4}-\\d{1,2}-\\d{1,2}\\s\\d{1,2}:\\d{2}$".toRegex() to "yyyy-MM-dd HH:mm",
    "^\\d{1,2}/\\d{1,2}/\\d{4}\\s\\d{1,2}:\\d{2}$".toRegex() to "MM/dd/yyyy HH:mm",
    "^\\d{4}/\\d{1,2}/\\d{1,2}\\s\\d{1,2}:\\d{2}$".toRegex() to "yyyy/MM/dd HH:mm",
    "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}\\s\\d{1,2}:\\d{2}$".toRegex() to "dd MMM yyyy HH:mm",
    "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}\\s\\d{1,2}:\\d{2}$".toRegex() to "dd MMMM yyyy HH:mm",
    "^\\d{14}$".toRegex() to "yyyyMMddHHmmss",
    "^\\d{8}\\s\\d{6}$".toRegex() to "yyyyMMdd HHmmss",
    "^\\d{1,2}-\\d{1,2}-\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "dd-MM-yyyy HH:mm:ss",
    "^\\d{4}-\\d{1,2}-\\d{1,2}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "yyyy-MM-dd HH:mm:ss",
    "^\\d{1,2}/\\d{1,2}/\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "MM/dd/yyyy HH:mm:ss",
    "^\\d{4}/\\d{1,2}/\\d{1,2}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "yyyy/MM/dd HH:mm:ss",
    "^\\d{1,2}\\s[a-z]{3}\\s\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "dd MMM yyyy HH:mm:ss",
    "^\\d{1,2}\\s[a-z]{4,}\\s\\d{4}\\s\\d{1,2}:\\d{2}:\\d{2}$".toRegex() to "dd MMMM yyyy HH:mm:ss"
).toMap()

/**
 * Determine SimpleDateFormat pattern matching with the given date string. Returns null if
 * format is unknown. You can simply extend DateUtil with more formats if needed.
 * @param dateString The date string toa determine the SimpleDateFormat pattern for.
 * @return The matching SimpleDateFormat pattern, or null if format is unknown.
 * @see SimpleDateFormat
 */
fun determineDateFormat(dateString: String): String? {
    for (regexp in DATE_FORMAT_REGEXPS.keys) {
        if (dateString.lowercase().matches(regexp)) {
            return DATE_FORMAT_REGEXPS[regexp]
        }
    }
    return null // Unknown format.
}

fun String.date(): Date? {
    val format = determineDateFormat(this) ?: return null
    return try {
        SimpleDateFormat(format).parse(this)
    } catch (e: ParseException) {
        null
    }
}

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

data class RGB(val r: UByte, val g: UByte, val b: UByte, val a: UByte = 255U)

inline fun <T> T.runIf(condition: Boolean, block: T.() -> T) = if (condition) block() else this

fun ReplyCallbackAction.ephemeral() = setEphemeral(true)
