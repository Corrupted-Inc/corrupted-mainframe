package utils

import java.time.Duration

fun Duration.toHumanReadable() = toString().removePrefix("PT").replace("(\\d[HMS])(?!$)".toRegex(), "$1 ").toLowerCase()
