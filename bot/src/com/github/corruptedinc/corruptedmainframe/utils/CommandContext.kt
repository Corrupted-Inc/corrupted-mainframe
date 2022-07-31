package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.stripPings
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import java.awt.Color
import java.time.temporal.TemporalAccessor

data class CommandContext(val bot: Bot, val event: SlashCommandInteractionEvent) {
    @Suppress("LongParameterList")  // They've got default arguments
    fun embed(
        title: String,
        url: String? = null,
        content: List<MessageEmbed.Field> = emptyList(),
        imgUrl: String? = null,
        thumbnail: String? = null,
        author: String? = null,
        authorUrl: String? = null,
        timestamp: TemporalAccessor? = null,
        color: Color? = null,
        description: String? = null,
        stripPings: Boolean = true,
        footer: String? = null
    ): MessageEmbed {
        val builder = EmbedBuilder()
        builder.setTitle(title, url)
        builder.fields.addAll(if (stripPings) content.map {
            MessageEmbed.Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline)
        } else content)
        builder.setImage(imgUrl)
        builder.setThumbnail(thumbnail)
        builder.setAuthor(author, authorUrl)
        builder.setTimestamp(timestamp)
        builder.setColor(color)
        builder.setDescription(if (stripPings) description?.stripPings() else description)
        builder.setFooter(footer)
        return builder.build()
    }
}
