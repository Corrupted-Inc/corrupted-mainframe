package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.toHumanReadable
import dev.minn.jda.ktx.await
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.MiscUtil
import net.dv8tion.jda.api.utils.TimeUtil
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.min

private const val MAX_PURGE = 1000
private const val PURGE_DELAY = 2000L

fun registerCommands(bot: Bot) {
    bot.commands.register(
        CommandData("purge", "Purges messages")
        .addOption(OptionType.INTEGER, "count", "The number of messages to purge", true)) { event ->

        val count = (event.getOption("count")?.asLong ?: throw CommandException("Invalid number")).toInt()
            .coerceIn(1, MAX_PURGE) + 1

        bot.commands.assertPermissions(event, Permission.MESSAGE_MANAGE)

        event.deferReply().await()

        delay(PURGE_DELAY)
        var yetToDelete = count
        while (yetToDelete > 0) {
            @Suppress("MagicNumber")
            val maxDeletion = 100

            val messages = event.channel.history.retrievePast(min(maxDeletion, yetToDelete)).await()
            if (messages.size == 1) {
                messages.first().delete().await()
            } else {
                @Suppress("MagicNumber")
                val twoWeeksAgo = System.currentTimeMillis() -
                        Duration.of(14, ChronoUnit.DAYS).toMillis()

                val bulkDeletable = messages.filter {
                    TimeUtil.getDiscordTimestamp(twoWeeksAgo) < MiscUtil.parseSnowflake(
                        it.id
                    )
                }
                event.textChannel.deleteMessages(bulkDeletable).await()
                for (item in messages - bulkDeletable.toSet()) {
                    item.delete().await()
                    delay(maxDeletion.toLong())
                }
            }
            if (messages.size != min(maxDeletion, yetToDelete)) break
            yetToDelete -= messages.size
            @Suppress("MagicNumber")
            (delay(1100))  // To prevent ratelimit being exceeded
        }
    }

    bot.commands.register(CommandData("stats", "Shows bot statistics")) { event ->
        // TODO daily commands executed, new servers, etc

        val builder = EmbedBuilder()
        builder.setTitle("Statistics and Info")
        builder.setThumbnail(event.guild?.iconUrl)
        val id = bot.jda.selfUser.id
        builder.setDescription("""
                **Bot Info**
                Members: ${bot.database.users().size}
                Guilds: ${bot.database.guildCount()}
                Commands: ${bot.commands.newCommands.size}
                Gateway ping: ${bot.jda.gatewayPing}ms
                Rest ping: ${bot.jda.restPing.await()}ms
                Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable()}
                Git: ${bot.config.gitUrl}
                Invite: [Admin invite](${Commands.adminInvite(id)})  [basic permissions](${Commands.basicInvite(id)})
                **Guild Info**
                Owner: ${event.guild?.owner?.asMention}
            """.trimIndent())
        event.replyEmbeds(builder.build()).await()
    }

    bot.commands.register(
        CommandData("ban", "Ban a user")
        .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild?.ban(user, 0)?.await() ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(Commands.embed("Banned", description = "Banned ${user.asMention}")).await()
    }

    bot.commands.register(
        CommandData("unban", "Unban a user")
        .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser ?: throw CommandException("Failed to find user!")

        event.guild?.unban(user)?.await() ?: throw CommandException("Couldn't unban user!")
        event.replyEmbeds(Commands.embed("Unbanned", description = "Unbanned ${user.asMention}"))
    }

    bot.commands.register(
        CommandData("kick", "Kick a user")
        .addOption(OptionType.USER, "user", "The user to kick", true)) { event ->
        bot.commands.assertPermissions(event, Permission.KICK_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild?.kick(user.id)?.await() ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(Commands.embed("Kicked", description = "Kicked ${user.asMention}")).await()
    }

    bot.commands.register(CommandData("reactionrole", "Reactions are specified with the format " +
            "'\uD83D\uDC4D-rolename, \uD83D\uDCA5-otherrolename'.")
        .addOption(OptionType.STRING, "message", "Message link", true)
        .addOption(OptionType.STRING, "reactions", "Reactions", true)) { event ->

        bot.commands.assertAdmin(event)

        val reactionsMap = event.getOption("reactions")!!.asString.removeSurrounding("\"").replace(", ", ",").split(",")
            // Split by colon into emote and role name, stripping spaces from the start of the latter
            .map { Pair(
                it.substringBeforeLast("-"),
                it.substringAfterLast("-").dropWhile { c -> c == ' ' })
            }

            // Retrieve all the roles
            .map {
                event.guild?.getRolesByName(it.second, true)
                    ?.firstOrNull()?.run { Pair(it.first, this) } ?: throw CommandException("Couldn't find role '${it.second}'!")
            }
            .filter { event.member?.canInteract(it.second) == true }  // Prevent privilege escalation
            .associate { Pair(it.first, it.second.idLong) }  // Convert to map for use by the database

        val link = event.getOption("message")!!.asString
        val messageId = link.substringAfterLast("/")
        val channelId = link.removeSuffix("/$messageId").substringAfterLast("/").toLongOrNull()
            ?: throw CommandException("Invalid message link")
        val channel = event.guild?.getTextChannelById(channelId)
        val msg = channel?.retrieveMessageById(
            messageId.toLongOrNull()
                ?: throw CommandException("Invalid message link")
        )?.await() ?: throw CommandException("Invalid message link")

        bot.database.moderationDB.addAutoRole(msg, reactionsMap)

        for (reaction in reactionsMap) {
            msg.addReaction(reaction.key).await()
        }

        event.replyEmbeds(
            Commands.embed("Successfully added ${reactionsMap.size} reaction roles:",
                content = reactionsMap.map {
                    MessageEmbed.Field(it.key, event.guild?.getRoleById(it.value)?.name, false)
                })
        ).await()
    }

    bot.commands.register(CommandData("starboard", "Manage the starboard").addSubcommands(
        SubcommandData("setup", "Set up or modify the starboard")
            .addOption(OptionType.CHANNEL, "channel", "The starboard channel", true)
            .addOption(OptionType.STRING, "emote", "The starboard emote", true)
            .addOption(OptionType.INTEGER, "threshold", "The starboard threshold (default 7)", false),
        SubcommandData("disable", "Disable the starboard"),
        SubcommandData("remove", "Remove a message from the starboard")
            .addOption(OptionType.STRING, "link", "A link to the offending message", true),
        SubcommandData("star", "Add a message to the starboard")
            .addOption(OptionType.STRING, "link", "A link to the message to be added", true),
        SubcommandData("threshold", "Set the threshold for the starboard")
            .addOption(OptionType.INTEGER, "threshold", "The number of reactions", true)
    )) { event ->
        bot.commands.assertPermissions(event, Permission.MESSAGE_MANAGE)

        when (event.subcommandName) {
            "setup" -> {
                val channel = event.getOption("channel")!!.asMessageChannel as? TextChannel
                    ?: throw CommandException("Must be a message channel!")
                val reactionName = event.getOption("emote")!!.asString
                val reaction = event.guild!!.getEmoteById(
                    reactionName.substringAfterLast(':').removeSuffix(">")
                ) ?: throw CommandException("Couldn't find a custom emoji with that name!")
                val threshold = event.getOption("threshold")?.asLong?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
                    ?: ExposedDatabase.STARBOARD_THRESHOLD_DEFAULT
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild ?: return@trnsctn)
                    g.starboardChannel = channel.idLong
                    g.starboardReaction = reaction.name
                    g.starboardThreshold = threshold
                }
                event.replyEmbeds(
                    Commands.embed(
                        "Starboard",
                        description = "Starboard successfully set up in ${channel.asMention}."
                    )
                ).await()
            }
            "disable" -> {
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    g.starboardChannel = null
                }
                event.replyEmbeds(Commands.embed("Starboard", description = "Starboard disabled.")).await()
            }
            "remove" -> {
                val msgID = event.getOption("link")!!.asString.substringAfterLast('/')
                val chanID = bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    g.starboardChannel
                }
                val channel = event.guild?.getTextChannelById(chanID
                    ?: throw CommandException("No starboard set up!"))
                    ?: throw CommandException("No starboard set up!")  // this is fine

                bot.starboard.unstarDB(channel.retrieveMessageById(msgID).await())
                channel.deleteMessageById(msgID).await()
                event.replyEmbeds(Commands.embed("Starboard", description = "Removed message.")).await()
            }
            "star" -> {
                val msgLink = event.getOption("link")!!.asString
                val msgID = msgLink.substringAfterLast('/')
                val msgChannel = msgLink.removeSuffix("/$msgID").substringAfterLast('/')

                val chan = event.guild!!.getTextChannelById(msgChannel)
                    ?: throw CommandException("Message not found!")
                val msg = chan.retrieveMessageById(msgID).await()
                    ?: throw CommandException("Message not found!")

                bot.starboard.star(msg)

                event.replyEmbeds(Commands.embed("Starboard", description = "Starred message.")).await()
            }
            "threshold" -> {
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    g.starboardThreshold = event.getOption("threshold")!!.asLong
                        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                }
                event.replyEmbeds(Commands.embed("Starboard", description = "Successfully updated threshold.")).await()
            }
        }
    }
}
