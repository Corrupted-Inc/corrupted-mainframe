package com.github.corruptedinc.corruptedmainframe.plugin

import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed

interface Plugin {
    fun load()

    fun botStarted()

    fun registerCommands(handler: CommandHandler<Message, MessageEmbed>)
}
