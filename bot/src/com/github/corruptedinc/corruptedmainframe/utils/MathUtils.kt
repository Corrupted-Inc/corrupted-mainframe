package com.github.corruptedinc.corruptedmainframe.utils

import kotlin.random.Random

fun weightedRandom(values: Iterable<Int>, weights: Iterable<Double>): Int {
    val valuesList = values.toList()
    val weightsList = weights.toList()
    assert(valuesList.size == weightsList.size)
    val list = mutableListOf<Pair<Int, Double>>()
    var accumulator = 0.0
    for (i in valuesList.indices) {
        accumulator += weightsList[i]
        list.add(Pair(valuesList[i], accumulator))
    }

    val r = Random.nextDouble(accumulator)
    return list.first { it.second > r }.first
}
