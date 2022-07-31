package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent

data class AutocompleteContext(val bot: Bot, val event: CommandAutoCompleteInteractionEvent)
