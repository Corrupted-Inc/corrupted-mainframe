package commands

import commands.CommandHandler.*
import commands.CommandHandler.Command.CommandBuilder
import discord.Bot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.temporal.TemporalAccessor

class Commands(val bot: Bot) {
    val handler = CommandHandler<Message, MessageEmbed> { commandResult ->
        commandResult.sender.channel.sendMessage(commandResult.value ?: return@CommandHandler).queue()
    }

    private fun embed(title: String, url: String? = null, content: List<MessageEmbed.Field> = emptyList(), imgUrl: String? = null, thumbnail: String? = null, author: String? = null, authorUrl: String? = null, timestamp: TemporalAccessor? = null): MessageEmbed {
        val builder = EmbedBuilder()
        builder.setTitle(title, url)
        builder.fields.addAll(content)
        builder.setImage(imgUrl)
        builder.setThumbnail(thumbnail)
        builder.setAuthor(author, authorUrl)
        builder.setTimestamp(timestamp)
        return builder.build()
    }

    init {
        handler.register(
            CommandBuilder<Message, MessageEmbed>("help").arg(IntArg("page", true)).ran { sender, args ->
                var page = args.getOrDefault("page", 1) as Int - 1
                val list = handler.commands.sortedBy { it.base == "help" }.chunked(10)
                if (page !in list.indices) page = 0

                val commands = list[page]
                val builder = EmbedBuilder()
                builder.setTitle("Help ${page + 1}/${list.size}")
                for (command in commands) {
                    builder.addField(command.base + " " + command.arguments.joinToString(" ") { if (it.optional) "[${it.name}]" else "<${it.name}>" }, command.help, true)
                }
                InternalCommandResult(builder.build(), true)
            }.help("Shows a list of commands and their descriptions.")
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("stats").ran { sender, _ ->
                val builder = EmbedBuilder()
                builder.setTitle("Statistics and Info")
                builder.setThumbnail(sender.guild.iconUrl)
                builder.setDescription("""
                    **Bot Info**
                     Members: ${bot.dbWrapper.listUsers().size}
                     Guilds: ${bot.dbWrapper.listGuilds().size}
                     Commands: ${handler.commands.size}
                     Gateway ping: ${bot.jda.gatewayPing}ms
                """.trimIndent())
                InternalCommandResult(builder.build(), true)
            }
        )
    }

    fun handle(message: Message) {
        handler.handleAndSend("!", message.contentRaw, message)
    }
}
