package com.github.corruptedinc.corruptedmainframe.utils

class Row(private vararg val items: String, val overrideWidths: Array<Int?> = arrayOfNulls(items.size)) : Iterable<String> {
    operator fun get(index: Int) = items[index]

    override fun iterator(): Iterator<String> {
        return items.iterator()
    }

    val indices get() = items.indices
}

enum class Align {
    LEFT, CENTER, RIGHT
}

class Col(val align: Align, private vararg val items: String, val overrideWidth: Int = -1) : Iterable<String> by items.toList() {
    val size = items.size

    operator fun get(index: Int) = items[index]
}

fun table(vararg data: Col): String {
    val colWidths = data.map { if (it.overrideWidth != -1) it.overrideWidth else (it.maxOf { v -> v.length } + 2) }
    val builder = StringBuilder()

    val horizontalLine = colWidths.joinToString("+", "+", "+\n") { "-".repeat(it) }

    builder.append(horizontalLine)

    builder.append(data.zip(colWidths).joinToString("|", "|", "|") { it.first[0].padEnd(it.second) })
    builder.append('\n')

    builder.append(horizontalLine)

    for (row in 1 until data[0].size) {
        builder.append("| ")
        for ((i, c) in data.withIndex()) {
            val width = colWidths[i] - 2
            val value = c[row].run {
                when (c.align) {
                    Align.LEFT -> padEnd(width)
                    Align.RIGHT -> padStart(width)
                    Align.CENTER -> TODO()
                }
            }
            builder.append(value)

            builder.append(" | ")
        }
        builder.append('\n')
    }

    builder.append(horizontalLine)

    return builder.toString()
}

fun table(data: Array<Row>): String {
    val columnWidths = data.first().indices.map {
        data.maxOf { r -> r[it].length } + 2
    }

    val columns = StringBuilder()
    columns.append('+')
    for (item in columnWidths) {
        columns.append("-".repeat(item))
        columns.append('+')
    }
    columns.append('\n')

    for ((i, r) in data.withIndex()) {
        columns.append('|')

        for ((index, item) in r.withIndex()) {
            if (r.overrideWidths[index] != null) {
                columns.append(item)
            } else {
                columns.append(item.padStart(columnWidths[index] - 1, ' '))
            }
            columns.append(' ')
            columns.append('|')
        }

        columns.append('\n')

        if (i == 0) {
            columns.append('+')
            for (item in columnWidths) {
                columns.append("-".repeat(item))
                columns.append('+')
            }
            columns.append('\n')
        }
    }

    columns.append("+")
    for (item in columnWidths) {
        columns.append("-".repeat(item))
        columns.append("+")
    }
    columns.append('\n')

    return columns.toString()
}
