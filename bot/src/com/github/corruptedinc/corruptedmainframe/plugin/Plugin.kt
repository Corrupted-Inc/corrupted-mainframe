package com.github.corruptedinc.corruptedmainframe.plugin

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.interactions.commands.build.CommandData

interface Plugin {
    /**
     * Called when the plugin is first loaded.
     */
    fun load(bot: Bot)

    /**
     * Called when the plugin should register commands.
     */
    fun registerCommands(register: (data: CommandData, lambda: suspend (SlashCommandInteraction) -> Unit) -> Unit)
}
