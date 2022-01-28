package com.github.corruptedinc.corruptedmainframe.utils

class Row(private vararg val items: String, val overrideWidths: Array<Int?> = arrayOfNulls(items.size)) : Iterable<String> {
    operator fun get(index: Int) = items[index]

    override fun iterator(): Iterator<String> {
        return items.iterator()
    }

    val indices get() = items.indices
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
