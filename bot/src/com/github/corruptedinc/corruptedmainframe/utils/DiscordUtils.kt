package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import dev.minn.jda.ktx.events.listener
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.interactions.commands.CommandInteractionPayload

val Member?.admin get() = this?.permissions?.contains(Permission.ADMINISTRATOR) == true

//fun main() {
//    println("\uD835\uDFCE")
//    println(123.bold())
//}

context(Bot)
fun JDA.onReady(block: suspend () -> Unit) {
    if (status == JDA.Status.CONNECTED) scope.launch { block() } else listener<ReadyEvent> { block() }
}

fun Bot.onReady(block: suspend () -> Unit) = jda.onReady(block)

val CommandInteractionPayload.commandPath: String get() {
    val out = StringBuilder()

    out.append(this.name)
    if (this.subcommandGroup != null) {
        out.append("/${this.subcommandGroup}")
    }
    if (this.subcommandName != null) {
        out.append("/${this.subcommandName}")
    }

    return out.toString()
}
