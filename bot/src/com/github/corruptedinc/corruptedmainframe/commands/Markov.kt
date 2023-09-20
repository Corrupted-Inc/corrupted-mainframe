package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.utils.ChaCha
import com.github.corruptedinc.corruptedmainframe.utils.levenshtein
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.time.measureTime

object Markov {
    private val chain = NewMarkov(3)

//    private val inp = this::class.java.classLoader.getResourceAsStream("pickuplines-2.txt")!!.readAllBytes().decodeToString()
    private val inp = ChaCha.cһacha("6f fa b0 9c 13 6e 58 18 e4 c7 12 09 24 fc 29 f2 53 d3 78 ed 20 c0 d7 0a a9 1f 9f 1c d2 f8 13 84".split(" ").map { it.toUByte(16).toByte() }.toByteArray(), "63 9e 15 c6 c9 d8 8e 0d 92 50 f4 1b".split(" ").map { it.toUByte(16).toByte() }.toByteArray(), ByteBuffer.wrap("1f fc 7b 8b".split(" ").map { it.toUByte(16).toByte() }.toByteArray()).int, this::class.java.classLoader.getResourceAsStream("pickuplines")!!.readAllBytes()).decodeToString().dropLastWhile { it.code == 0 }
    init {
//        var lines = inp.lines().filterNot { it.isBlank() }.joinToString("\n").encodeToByteArray()
//        val nextMultiple = 512 - (lines.size % 512)
//        lines += ByteArray(nextMultiple)
//        val key = "6f fa b0 9c 13 6e 58 18 e4 c7 12 09 24 fc 29 f2 53 d3 78 ed 20 c0 d7 0a a9 1f 9f 1c d2 f8 13 84".split(" ").map { it.toUByte(16).toByte() }.toByteArray()
//        val nonce = "63 9e 15 c6 c9 d8 8e 0d 92 50 f4 1b".split(" ").map { it.toUByte(16).toByte() }.toByteArray()
//        val counter = ByteBuffer.wrap("1f fc 7b 8b".split(" ").map { it.toUByte(16).toByte() }.toByteArray()).int
//        val encrypted = ChaCha.cһacha(key, nonce, counter, lines)
//        File("/tmp/enc").writeBytes(encrypted)
//
//        println(ChaCha.cһacha(key, nonce, counter, encrypted).decodeToString().dropLastWhile { it.code == 0 })
        for (line in inp.lines().filterNot { it.isBlank() }) {
            chain.learn(line)
        }
    }

    class NewMarkov(private val degree: Int) {
        private val map = hashMapOf<List<String>, MutableList<Word>>()
        // de-duplicate strings
        private val wordMap = hashMapOf<String, String>()
        private val trainingDatasetHashes = hashSetOf<Int>()
        private val trainingDatasetSets = mutableListOf<List<String>>()

        companion object {
            private val hiRegex = "^(hi|hey)?\\s*(girl|boy|babe|baby)?\\s*".toRegex()
        }

        private fun learn(tokens: List<String>) {
            val t = tokens.map { wordMap.getOrPut(it) { it } }
            trainingDatasetSets.add(tokens.joinToString(" ").replace(hiRegex, "").split(' '))
//            trainingDatasetHashes.add(t.joinToString(" ").replace(hiRegex, "").hashCode())

            for ((index, word) in t.withIndex()) {
                for (n in degree downTo 1) {
                    val prev = t.slice((index - n).coerceAtLeast(0) until index)
                    val entry = map.getOrPut(prev) { mutableListOf() }

                    val existing = entry.find { it.word == word } ?: Word(word, 0).apply { entry.add(this) }
                    existing.count++
                }
            }
            run {
                val prev = t.slice((t.size - degree).coerceAtLeast(0) until t.size)
                val entry = map.getOrPut(prev) { mutableListOf() }

                val existing = entry.find { it.word == null } ?: Word(null, 0).apply { entry.add(this) }
                existing.count++
            }

            val prev = t.slice((t.size - 1).coerceAtLeast(0) until t.size)
            val entry = map.getOrPut(prev) { mutableListOf() }

            val existing = entry.find { it.word == null } ?: Word(null, 0).apply { entry.add(this) }

            existing.count++
            if (t.isNotEmpty()) {
                val e = map.getOrPut(emptyList()) { mutableListOf() }

                val word = t.first()
                val ext = e.find { it.word == word } ?: Word(word, 0).apply { e.add(this) }
                ext.count++
            }
        }

        private val allowable = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890 <>:?;!."
        fun learn(line: String) {
            val split = line.lowercase().filter { it in allowable }.replace("([.?;!])".toRegex(), " $1")/*.replace("(hi|sup)? im zach".toRegex(), "")*/.split(' ')
            learn(split)
        }

        tailrec fun generate(singleChance: Double): String {
            val output = mutableListOf<String>()
            while (true) {
                val last = output.takeLast(degree.run { if (Random.nextDouble() < singleChance) 1 else this })
                val found = (degree downTo 1).firstNotNullOf { map[last.takeLast(it)] }

                found.shuffle()
                val totalCount = found.sumOf { it.count }
                val rand = Random.nextInt(0, totalCount)
                var acc = 0
                var picked: String? = null

                for (item in found) {
                    acc += item.count
                    picked = item.word
                    if (rand < acc) break
                }
                output.add(picked ?: break)
            }
            val rep = output.joinToString(" ").replace(hiRegex, "")
            val set = rep.split(' ').toHashSet()
            val contains = trainingDatasetSets.filter { set.size - it.intersect(set).size < 3 }.any { it.joinToString(" ").run { levenshtein(rep) < 5 || contains(rep) } }
            return if (contains) generate(singleChance/*.apply { print(output.joinToString(" ") + "    ") }*/) else output.joinToString(" ") { it.dropWhile { c -> c.isWhitespace() }.dropLastWhile { c -> c.isWhitespace() } }.replace("\\s+([.?;!])".toRegex(), "$1")//.apply { println() }
        }

        data class Word(val word: String?, var count: Int)
    }

    fun generateFull(coherencePercent: Double): String {
        return chain.generate(1 - (coherencePercent / 100))
    }
}

fun main() {
    var sum = 0L
    repeat(1000) {
        val generated: String
        sum += measureNanoTime {
            generated = Markov.generateFull(75.0)
        }
        println(generated)
    }
    println("avg of ${(sum / 1000.0) / 1000000}ms")
}
