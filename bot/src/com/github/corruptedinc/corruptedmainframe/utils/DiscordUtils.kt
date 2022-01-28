package com.github.corruptedinc.corruptedmainframe.utils

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.Interaction

val Member?.admin get() = this?.permissions?.contains(Permission.ADMINISTRATOR) == true

fun Int.bold() = toString().map { "\uD835" + (0xdfce + it.digitToInt()).toChar() }.joinToString("")

//fun main() {
//    println("\uD835\uDFCE")
//    println(123.bold())
//}
