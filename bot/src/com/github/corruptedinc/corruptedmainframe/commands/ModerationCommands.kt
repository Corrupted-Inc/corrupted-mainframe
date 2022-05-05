package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AutoRoleMessage.Companion
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AutoRoleMessages
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AutoRoles
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.Command
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.SelectMenuInteractionEvent
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.message
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.OptionData.MAX_CHOICES
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.MiscUtil
import net.dv8tion.jda.api.utils.TimeUtil
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.roundToLong

private const val MAX_PURGE = 1000
private const val PURGE_DELAY = 2000L

fun registerCommands(bot: Bot) {
    bot.commands.register(
        slash("purge", "Purges messages")
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

    bot.commands.register(slash("stats", "Shows bot statistics")) { event ->
        // TODO daily commands executed, new servers, etc

        val builder = EmbedBuilder()
        builder.setTitle("Statistics and Info")
        builder.setThumbnail(event.guild?.iconUrl)
        val id = bot.jda.selfUser.id
        builder.setDescription("""
                **Bot Info**
                Guilds: ${bot.database.guildCount()}
                Commands: ${bot.commands.newCommands.size}
                Gateway ping: ${bot.jda.gatewayPing}ms
                Rest ping: ${bot.jda.restPing.await()}ms
                Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable()}
                Git: ${bot.config.gitUrl}
                Invite: [Admin invite](${Commands.adminInvite(id)})  [basic permissions](${Commands.basicInvite(id)})
                Commands Run Today: ${bot.database.commandsRun(Instant.now().minus(24, ChronoUnit.HOURS), Instant.now())}
                Commands Run Total: ${bot.database.commandsRun(Instant.EPOCH, Instant.now())}
                **Guild Info**
                Owner: ${event.guild?.owner?.asMention}
                Creation Date: <t:${event.guild?.timeCreated?.toEpochSecond()}> UTC
                Members: ${event.guild?.memberCount}
                Boost Level: ${event.guild?.boostTier?.name?.lowercase()?.replace('_', ' ')}
            """.trimIndent())
        event.replyEmbeds(builder.build()).await()
    }

    bot.commands.register(
        slash("ban", "Ban a user")
        .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild?.ban(user, 0)?.await() ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(Commands.embed("Banned", description = "Banned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("unban", "Unban a user")
        .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser ?: throw CommandException("Failed to find user!")

        event.guild?.unban(user)?.await() ?: throw CommandException("Couldn't unban user!")
        event.replyEmbeds(Commands.embed("Unbanned", description = "Unbanned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("kick", "Kick a user")
        .addOption(OptionType.USER, "user", "The user to kick", true)) { event ->
        bot.commands.assertPermissions(event, Permission.KICK_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild!!.kick(user.id).await()// ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(Commands.embed("Kicked", description = "Kicked ${user.asMention}", stripPings = false)).ephemeral().await()
    }

    bot.commands.register(slash("reactionrole", "Manage reaction role messages")
        .addSubcommands(
            SubcommandData("add", "Adds a reaction role.")
                .addOption(OptionType.STRING, "url", "Message URL", true)
                .addOption(OptionType.STRING, "reactions", "The emote to reaction mapping in \uD83D\uDC4D-rolename, \uD83D\uDCA5-otherrolename format", true, true),
            SubcommandData("list", "Lists the current reaction roles"),
            SubcommandData("delete", "Removes the autorole from a message")
                .addOption(OptionType.STRING, "url", "Message URL", true),
            SubcommandData("removeoption", "Removes a specific role from being awarded by a reaction role message")
                .addOption(OptionType.STRING, "url", "Message URL", true)
                .addOption(OptionType.ROLE, "role", "Role name", true),
            SubcommandData("addoption", "Adds an option to an existing reaction role message")
                .addOption(OptionType.STRING, "url", "Message URL", true)
                .addOption(OptionType.STRING, "emote", "The emote to add", true)
                .addOption(OptionType.ROLE, "role", "The role to add", true)
        )
    ) { event ->
        when (event.subcommandName) {
            "add" -> {
                val reactionsMap = event.getOption("reactions")!!.asString
                    .removeSurrounding("\"")
                    .replace(", ", ",")
                    .split(",")

                    // Split by colon into emote and role name, stripping spaces from the start of the latter
                    .map { Pair(
                        it.substringBeforeLast("-"),
                        it.substringAfterLast("-").dropWhile { c -> c == ' ' })
                    }

                    // Retrieve all the roles
                    .map {
                        event.guild?.getRolesByName(it.second, true)
                            ?.firstOrNull()
                            ?.run { Pair(it.first, this) }
                            ?: throw CommandException("Couldn't find role '${it.second}'!")
                    }
                    .filter { event.member?.canInteract(it.second) == true }  // Prevent privilege escalation
                    .associate { Pair(it.first, it.second.idLong) }  // Convert to map for use by the database

                val link = event.getOption("url")!!.asString
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
                    if (reaction.key.startsWith(":")) {
                        // sketchy
                        msg.addReaction(event.guild!!.getEmotesByName(
                            reaction.key.removeSurrounding(":"), false).first()
                        ).await()
                    } else {
                        msg.addReaction(reaction.key).await()
                    }
                }
            }
            "list" -> {
                // don't do expensive API calls within a transaction
                val output = mutableListOf<Pair<Long, MutableList<Pair<String, Long>>>>()
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    for (item in g.autoRoles) {
                        val l = mutableListOf<Pair<String, Long>>()
                        output.add(Pair(item.message, l))
                        for (role in item.roles) {
                            l.add(Pair(role.emote, role.role))
                        }
                    }
                }

                val fields = mutableListOf<MessageEmbed.Field>()

                for (item in output) {
                    val v = StringBuilder()

                    for (role in item.second) {
                        v.append(role.first)
                        v.append(" - ")
                        v.appendLine(event.guild!!.getRoleById(role.second)!!.name)
                    }

                    fields.add(
                        MessageEmbed.Field(event.guild!!.getTextChannelById(item.first)!!.name,
                            v.toString(), false)
                    )
                }

                event.replyEmbeds(Commands.embed("Reaction Roles", content = fields)).ephemeral().await()
            }
            "delete" -> {
                bot.commands.assertAdmin(event)

                val messageId = event.getOption("url")!!.asString.substringAfterLast("/").toLong()

                bot.database.trnsctn {
                    val item = ModerationDB.AutoRoleMessage.find { AutoRoleMessages.message eq messageId }.firstOrNull()
                        ?: throw CommandException("No reaction role found!")
                    AutoRoles.deleteWhere { AutoRoles.message eq item.id }
                    item.delete()
                }

                event.replyEmbeds(Commands.embed("Successfully removed")).ephemeral().await()
            }
            "removeoption" -> {
                bot.commands.assertAdmin(event)

                val messageId = event.getOption("url")!!.asString.substringAfterLast("/").toLongOrNull()
                    ?: throw CommandException("Invalid link!")

                val role = event.getOption("role")!!.asRole.idLong

                val c = bot.database.trnsctn {
                    val item = ModerationDB.AutoRoleMessage.find { AutoRoleMessages.message eq messageId }.firstOrNull()
                        ?: throw CommandException("No reaction role found!")
                    AutoRoles.deleteWhere { (AutoRoles.message eq item.id) and (AutoRoles.role eq role) }
                }

                event.replyEmbeds(Commands.embed("Removed $c role${if (c == 1) "" else "s"}")).ephemeral().await()
            }
            "addoption" -> {
                TODO("AAAA")
            }
        }
    }

    bot.jda.listener<CommandAutoCompleteInteractionEvent> { event ->
        if (event.name != "reactionrole") return@listener
        if (event.subcommandName != "add") return@listener

        if (event.focusedOption.name == "reactions") {
            val typed = event.focusedOption.value.substringAfterLast(',').removePrefix(" ")
            if (typed.isEmpty()) {
//                event.
            } else {
                if (typed.contains('-')) {
                    val typedRole = typed.substringAfterLast('-').removeSuffix(" ")
                    val roles = event.guild!!.roles.sortedBy { biasedLevenshteinInsensitive(typedRole, it.name) }
                    if (roles.firstOrNull()?.name?.lowercase() == typedRole) {
                        event.replyChoiceStrings("", ", ").await()
                        return@listener
                    }
                    event.replyChoiceStrings(roles.take(MAX_CHOICES).map { it.name }).await()
                } else {
                    event.replyChoice("-", "-").await()
                }
            }
        }
    }

//    bot.commands.register(slash("reactionrole", "Reactions are specified with the format " +
//            "'\uD83D\uDC4D-rolename, \uD83D\uDCA5-otherrolename'.")
//        .addOption(OptionType.STRING, "message", "Message link", true)
//        .addOption(OptionType.STRING, "reactions", "Reactions", true)) { event ->
//
//        bot.commands.assertAdmin(event)
//
//        event.deferReply().await()
//
//        val reactionsMap = event.getOption("reactions")!!.asString.removeSurrounding("\"").replace(", ", ",").split(",")
//            // Split by colon into emote and role name, stripping spaces from the start of the latter
//            .map { Pair(
//                it.substringBeforeLast("-"),
//                it.substringAfterLast("-").dropWhile { c -> c == ' ' })
//            }
//
//            // Retrieve all the roles
//            .map {
//                event.guild?.getRolesByName(it.second, true)
//                    ?.firstOrNull()?.run { Pair(it.first, this) } ?: throw CommandException("Couldn't find role '${it.second}'!")
//            }
//            .filter { event.member?.canInteract(it.second) == true }  // Prevent privilege escalation
//            .associate { Pair(it.first, it.second.idLong) }  // Convert to map for use by the database
//
//        val link = event.getOption("message")!!.asString
//        val messageId = link.substringAfterLast("/")
//        val channelId = link.removeSuffix("/$messageId").substringAfterLast("/").toLongOrNull()
//            ?: throw CommandException("Invalid message link")
//        val channel = event.guild?.getTextChannelById(channelId)
//        val msg = channel?.retrieveMessageById(
//            messageId.toLongOrNull()
//                ?: throw CommandException("Invalid message link")
//        )?.await() ?: throw CommandException("Invalid message link")
//
//        bot.database.moderationDB.addAutoRole(msg, reactionsMap)
//
//        for (reaction in reactionsMap) {
//            if (reaction.key.startsWith(":")) {
//                // sketchy
//                msg.addReaction(event.guild!!.getEmotesByName(reaction.key.removeSurrounding(":"), false).first()).await()
//            } else {
//                msg.addReaction(reaction.key).await()
//            }
//        }
//
//        event.hook.editOriginalEmbeds(
//            Commands.embed("Successfully added ${reactionsMap.size} reaction roles:",
//                content = reactionsMap.map {
//                    MessageEmbed.Field(it.key, event.guild?.getRoleById(it.value)?.name, false)
//                })
//        ).await()
//    }

    bot.commands.register(slash("starboard", "Manage the starboard").addSubcommands(
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
                ).ephemeral().await()
            }
            "disable" -> {
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    g.starboardChannel = null
                }
                event.replyEmbeds(Commands.embed("Starboard", description = "Starboard disabled.")).ephemeral().await()
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
                event.replyEmbeds(Commands.embed("Starboard", description = "Removed message.")).ephemeral().await()
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

                event.replyEmbeds(Commands.embed("Starboard", description = "Starred message.")).ephemeral().await()
            }
            "threshold" -> {
                bot.database.trnsctn {
                    val g = bot.database.guild(event.guild!!)
                    g.starboardThreshold = event.getOption("threshold")!!.asLong
                        .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                }
                event.replyEmbeds(Commands.embed("Starboard", description = "Successfully updated threshold.")).ephemeral().await()
            }
        }
    }

    bot.commands.register(slash("addrole", "Creates a role")
        .addOption(OptionType.STRING, "name", "Role name", true)
    ) { event ->
        bot.commands.assertAdmin(event)

        val role = try {
            event.guild!!.createRole().setName(event.getOption("name")!!.asString).await()
        } catch (ignored: PermissionException) {
            throw CommandException("Not enough permissions!")
        }.idLong

        event.replyEmbeds(Commands.embed("Role Created", description = "id: $role")).ephemeral().await()
    }

    bot.commands.register(slash("say", "Says something in a channel.")
        .addOption(OptionType.STRING, "title", "The title", true)
        .addOption(OptionType.STRING, "content", "The thing to say", true)
        .addOption(OptionType.CHANNEL, "channel", "The channel to send it in", true)
    ) { event ->
        val title = event.getOption("title")!!.asString
        val content = event.getOption("content")!!.asString
        val channel = event.getOption("channel")!!.asMessageChannel

        bot.commands.assertAdmin(event)

        channel!!.sendMessageEmbeds(Commands.embed(title, description = content, footer = "Requested by <@${event.user.id}>", stripPings = false)).await()
        event.replyEmbeds(Commands.embed("Sent")).ephemeral().await()
    }

    bot.commands.register(slash("fightcooldown", "Sets the timeout between fights.")
        .addOption(OptionType.STRING, "duration", "The cooldown, in hh:mm:ss format", true)
    ) { event ->
        bot.commands.assertAdmin(event)

        val durationString = event.getOption("duration")!!.asString
        val sections = durationString.split(':').toMutableList()
        val seconds = sections.removeLastOrNull()?.toDoubleOrNull() ?: 0.0
        val minutes = sections.removeLastOrNull()?.toIntOrNull() ?: 0
        val hours = sections.removeLastOrNull()?.toIntOrNull() ?: 0

        val sRange = 0.0..60.0
        val mRange = 0 until 60
        val hRange = 0 until 6

        if (seconds !in sRange || seconds == 60.0 || minutes !in mRange || hours !in hRange) throw CommandException("Time must be less than 6:59:59!")
        val secs = seconds + (minutes * 60) + (hours * 3600)
        bot.database.trnsctn {
            val guild = bot.database.guild(event.guild!!)
            guild.fightCooldown = Duration.ofMillis((secs * 1000).roundToLong())
        }
        event.replyEmbeds(Commands.embed("Cooldown Set")).ephemeral().await()
    }

    bot.commands.register(slash("fightcategories", "Pick the categories fight attacks are picked from.")
        .addOption(OptionType.STRING, "categories", "The comma-separated categories", true, true)
    ) { event ->
        bot.commands.assertAdmin(event)

        val categories = event.getOption("categories")!!.asString.split(",\\s?".toRegex()).mapNotNull { try { Attack.Category.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null } }.toSet() + Attack.Category.GENERAL - Attack.Category.BOT
        val bitmask = categories.fold(0UL) { a, b -> a or b.bitmask }

        bot.database.trnsctn {
            val g = bot.database.guild(event.guild!!)
            g.fightCategories = bitmask
        }

        event.replyEmbeds(Commands.embed("Categories Changed", description = categories.joinToString { it.name.lowercase() })).ephemeral().await()
    }

    bot.jda.listener<CommandAutoCompleteInteractionEvent> { event ->
        if (event.name != "fightcategories") return@listener
        val items = event.focusedOption.value.split(',').map { it.dropWhile { v -> v.isWhitespace() }.dropLastWhile { v -> v.isWhitespace() } }
        val value = items.lastOrNull() ?: ""
        val sorted = Attack.Category.values().filter { it.pickable }.sortedBy { biasedLevenshteinInsensitive(it.name, value) }
        val existing = if (items.isEmpty()) "" else items.dropLast(1).joinToString() + ", "
        event.replyChoiceStrings(sorted.map { existing + it }).await()
    }


    // TODO: this is broken
    bot.commands.registerMessage(message("star")) { event ->
        bot.commands.assertPermissions(event, Permission.MESSAGE_MANAGE)

        val msg = event.target

        bot.starboard.star(msg)

        event.replyEmbeds(Commands.embed("Starboard", description = "Starred message.")).ephemeral().await()
    }
}
