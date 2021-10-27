package com.github.corruptedinc.corruptedmainframe.plugin

import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface Plugin {
    fun load()

    fun botStarted()

    fun registerCommands(register: (data: CommandData, lambda: suspend (SlashCommandEvent) -> Unit) -> Unit)
}
