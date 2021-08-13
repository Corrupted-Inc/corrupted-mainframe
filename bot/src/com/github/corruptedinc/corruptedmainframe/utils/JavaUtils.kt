package com.github.corruptedinc.corruptedmainframe.utils

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

fun String.containsAny(items: Iterable<String>) = items.any { it in this }
