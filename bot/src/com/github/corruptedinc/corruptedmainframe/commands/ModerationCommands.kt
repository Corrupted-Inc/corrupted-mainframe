package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AuditableAction.*
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AutoRoleMessages
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AutoRoles
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.CommandContext
import com.github.corruptedinc.corruptedmainframe.utils.CommandContext.Companion.auditLog
import com.github.corruptedinc.corruptedmainframe.utils.biasedLevenshteinInsensitive
import com.github.corruptedinc.corruptedmainframe.utils.ephemeral
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.OptionData.MAX_CHOICES
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.MiscUtil
import net.dv8tion.jda.api.utils.TimeUtil
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.time.toKotlinDuration

private const val MAX_PURGE = 1000
private const val PURGE_DELAY = 2000L

fun registerCommands(bot: Bot) {
    bot.commands.register(
        slash("purge", "Purges messages")
        .addOption(OptionType.INTEGER, "count", "The number of messages to purge", true)) { event ->

        val count = (event.getOption("count")?.asLong ?: throw CommandException("Invalid number")).toInt()
            .coerceIn(1, MAX_PURGE) + 1

        bot.commands.assertPermissions(event, Permission.MESSAGE_MANAGE)
        CommandContext.auditLogT(bot, event.user, event.guild, PURGE, Purge(event.channel.idLong, count))

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
                event.messageChannel.purgeMessages(bulkDeletable).forEach { it.await() }
                for (item in messages - bulkDeletable.toSet()) {
                    item.delete().await()
                    delay(maxDeletion.toLong())
                }
            }
            if (messages.size != min(maxDeletion, yetToDelete)) break
            yetToDelete -= messages.size
            @Suppress("MagicNumber")
            delay(1100)  // To prevent ratelimit being exceeded
        }
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
//            SubcommandData("addoption", "Adds an option to an existing reaction role message")
//                .addOption(OptionType.STRING, "url", "Message URL", true)
//                .addOption(OptionType.STRING, "emote", "The emote to add", true)
//                .addOption(OptionType.ROLE, "role", "The role to add", true)
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

                // TODO: remove double transaction
                bot.database.moderationDB.addAutoRole(msg, reactionsMap)
                CommandContext.auditLogT(bot, event.user, event.guild, REACTIONROLE_ADD, ReactionroleL(channelId, messageId.toLong()))

                for (reaction in reactionsMap) {
                    if (reaction.key.startsWith(":")) {
                        // sketchy
                        msg.addReaction(event.guild!!.getEmojisByName(
                            reaction.key.removeSurrounding(":"), false).first()
                        ).await()
                    } else {
                        msg.addReaction(Emoji.fromFormatted(reaction.key)).await()
                    }
                }
            }
            "list" -> {
                // don't do expensive API calls within a transaction
                val output = mutableListOf<Pair<Long, MutableList<Pair<String, Long>>>>()
                bot.database.trnsctn {
                    val g = event.guild!!.m
                    for (item in g.autoRoles) {
                        val l = mutableListOf<Pair<String, Long>>()
                        output.add(Pair(item.message, l))
                        for (role in item.roles) {
                            l.add(Pair(role.emote, role.role))
                        }
                    }
                }

                val fields = mutableListOf<Field>()

                for (item in output) {
                    val v = StringBuilder()

                    for (role in item.second) {
                        v.append(role.first)
                        v.append(" - ")
                        v.appendLine(event.guild!!.getRoleById(role.second)!!.name)
                    }

                    fields.add(Field(event.guild!!.getTextChannelById(item.first)!!.name, v.toString(), false))
                }

                event.replyEmbeds(embed("Reaction Roles", content = fields)).ephemeral().await()
            }
            "delete" -> {
                bot.commands.assertAdmin(event)

                val messageId = event.getOption("url")!!.asString.substringAfterLast("/").toLong()

                bot.database.trnsctn {
                    val item = ModerationDB.AutoRoleMessage.find { AutoRoleMessages.message eq messageId }.firstOrNull()
                        ?: throw CommandException("No reaction role found!")
                    AutoRoles.deleteWhere { AutoRoles.message eq item.id }
                    item.delete()
                    auditLog(bot, event.user, event.guild, REACTIONROLE_REMOVE, ReactionroleL(0L/*fixme*/, messageId))
                }

                event.replyEmbeds(embed("Successfully removed")).ephemeral().await()
            }
            "removeoption" -> {
                bot.commands.assertAdmin(event)

                val messageId = event.getOption("url")!!.asString.substringAfterLast("/").toLongOrNull()
                    ?: throw CommandException("Invalid link!")

                val role = event.getOption("role")!!.asRole.idLong

                val c = bot.database.trnsctn {
                    val item = ModerationDB.AutoRoleMessage.find { AutoRoleMessages.message eq messageId }.firstOrNull()
                        ?: throw CommandException("No reaction role found!")
                    auditLog(bot, event.user, event.guild, REACTIONROLE_MODIFY, ReactionroleL(0L /*fixme*/, item.message))
                    AutoRoles.deleteWhere { (AutoRoles.message eq item.id) and (AutoRoles.role eq role) }
                }

                event.replyEmbeds(embed("Removed $c role${if (c == 1) "" else "s"}")).ephemeral().await()
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

    // TODO: add back star and unstar in the form of message commands

    bot.commands.register(slash("say", "Says something in a channel.")
        .addOption(OptionType.STRING, "title", "The title", true)
        .addOption(OptionType.STRING, "content", "The thing to say", true)
        .addOption(OptionType.CHANNEL, "channel", "The channel to send it in", true)
    ) { event ->
        val title = event.getOption("title")!!.asString
        val content = event.getOption("content")!!.asString
        val channel = event.getOption("channel")!!.asChannel as? TextChannel

        bot.commands.assertAdmin(event)

        channel!!.sendMessageEmbeds(embed(title, description = content, footer = "Requested by <@${event.user.id}>", stripPings = false)).await()
        event.replyEmbeds(embed("Sent")).ephemeral().await()
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
            val guild = event.guild!!.m
            val d = Duration.ofMillis((secs * 1000).roundToLong())
            guild.fightCooldown = d
            auditLog(bot, event.user, event.guild, FIGHT_COOLDOWN, FightCooldown(d.toKotlinDuration()))
        }
        event.replyEmbeds(embed("Cooldown Set")).ephemeral().await()
    }

    bot.commands.register(slash("fightcategories", "Pick the categories fight attacks are picked from.")
        .addOption(OptionType.STRING, "categories", "The comma-separated categories", true, true)
    ) { event ->
        bot.commands.assertAdmin(event)

        val categories = event.getOption("categories")!!.asString.split(",\\s?".toRegex()).mapNotNull { try { Attack.Category.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null } }.toSet() + Attack.Category.GENERAL - Attack.Category.BOT
        val bitmask = categories.fold(0UL) { a, b -> a or b.bitmask }

        bot.database.trnsctn {
            val g = event.guild!!.m
            g.fightCategories = bitmask
            auditLog(bot, event.user, event.guild, FIGHT_CATEGORIES, FightCategories(categories.toList()))
        }

        event.replyEmbeds(embed("Categories Changed", description = categories.joinToString { it.name.lowercase() })).ephemeral().await()
    }

    bot.jda.listener<CommandAutoCompleteInteractionEvent> { event ->
        if (event.name != "fightcategories") return@listener
        val items = event.focusedOption.value.split(',').map { it.dropWhile { v -> v.isWhitespace() }.dropLastWhile { v -> v.isWhitespace() } }
        val value = items.lastOrNull() ?: ""
        val sorted = Attack.Category.values().filter { it.pickable }.sortedBy { biasedLevenshteinInsensitive(it.name, value) }
        val existing = if (items.isEmpty()) "" else items.dropLast(1).joinToString() + ", "
        event.replyChoiceStrings(sorted.map { existing + it }).await()
    }
}
