package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.interactions.Interaction

val Member?.admin get() = this?.permissions?.contains(Permission.ADMINISTRATOR) == true

fun Int.bold() = toString().map { "\uD835" + (0xdfce + it.digitToInt()).toChar() }.joinToString("")

//fun main() {
//    println("\uD835\uDFCE")
//    println(123.bold())
//}

context(Bot)
fun JDA.onReady(block: suspend () -> Unit) {
    if (status == JDA.Status.CONNECTED) scope.launch { block() } else listener<ReadyEvent> { block() }
}

fun Bot.onReady(block: suspend () -> Unit) = jda.onReady(block)
