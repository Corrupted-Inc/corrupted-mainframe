package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
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
import net.dv8tion.jda.api.entities.MessageEmbed.Field
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
            delay(1100)  // To prevent ratelimit being exceeded
        }
    }

    bot.commands.register(slash("stats", "Shows bot statistics")) { event ->
        // TODO new servers, performance info

        val builder = EmbedBuilder()
        builder.setTitle("Statistics and Info")
        builder.setThumbnail(event.guild?.iconUrl)
        val id = bot.jda.selfUser.id
        val ping = bot.jda.restPing.await()
        bot.database.trnsctn {
            builder.setDescription(
                """
                **Bot Info**
                Guilds: ${bot.database.guildCount()}
                Commands: ${bot.commands.newCommands.size}
                Gateway ping: ${bot.jda.gatewayPing}ms
                Rest ping: ${ping}ms
                Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable() /* TODO: remove unnecessary precision */ }
                Git: ${bot.config.gitUrl}
                Invite: [Admin invite](${Commands.adminInvite(id)})  [basic permissions](${Commands.basicInvite(id)})
                Commands Run Today: ${bot.database.commandsRun(Instant.now().minus(24, ChronoUnit.HOURS), Instant.now())}
                Commands Run Total: ${bot.database.commandsRun(Instant.EPOCH, Instant.now())}
                **Guild Info**
                Owner: ${event.guild?.owner?.asMention}
                Creation Date: <t:${event.guild?.timeCreated?.toEpochSecond()}> UTC
                Members: ${event.guild?.memberCount}
                Boost Level: ${event.guild?.boostTier?.name?.lowercase()?.replace('_', ' ')}
            """.trimIndent()
            )
        }
        event.replyEmbeds(builder.build()).await()
    }

    bot.commands.register(
        slash("ban", "Ban a user")
        .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild?.ban(user, 0)?.await() ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(embed("Banned", description = "Banned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("unban", "Unban a user")
        .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
        bot.commands.assertPermissions(event, Permission.BAN_MEMBERS)
        val user = event.getOption("user")?.asUser ?: throw CommandException("Failed to find user!")

        event.guild?.unban(user)?.await() ?: throw CommandException("Couldn't unban user!")
        event.replyEmbeds(embed("Unbanned", description = "Unbanned ${user.asMention}")).ephemeral().await()
    }

    bot.commands.register(
        slash("kick", "Kick a user")
        .addOption(OptionType.USER, "user", "The user to kick", true)) { event ->
        bot.commands.assertPermissions(event, Permission.KICK_MEMBERS)
        val user = event.getOption("user")?.asUser
            ?: throw CommandException("Failed to find user")

        event.guild!!.kick(user.id).await()// ?: throw CommandException("Must be run in a server!")
        event.replyEmbeds(embed("Kicked", description = "Kicked ${user.asMention}", stripPings = false)).ephemeral().await()
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
                    val g = event.guild!!.m
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

    // TODO: autocomplete names
    bot.commands.register(slash("starboards", "Manage starboards")
        .addSubcommands(
            SubcommandData("list", "List starboards"),
            SubcommandData("add", "Add a starboard")
                .addOption(OptionType.STRING, "name", "The starboard title", true)
                .addOption(OptionType.CHANNEL, "channel", "The starboard channel", true)
                .addOption(OptionType.STRING, "emote", "The starboard emote", true)
                .addOption(OptionType.INTEGER, "threshold", "The number of reactions to star a message", true)
                .addOption(OptionType.NUMBER, "multiplier", "The XP gain multiplier", true),
            SubcommandData("remove", "Remove a starboard")
                .addOption(OptionType.STRING, "name", "The name of the starboard to be removed", true),
            SubcommandData("modify", "Modify a starboard")
                .addOption(OptionType.STRING, "name", "The starboard to be modified", true)
                .addOption(OptionType.STRING, "new-name", "new name (optional)", false)
                .addOption(OptionType.CHANNEL, "channel", "The new starboard channel (optional)", false)
                .addOption(OptionType.STRING, "emote", "The new starboard emote (optional)", false)
                .addOption(OptionType.INTEGER, "threshold", "The new number of reactions to star a message (optional)", false)
                .addOption(OptionType.NUMBER, "multiplier", "The new XP gain multiplier (optional)", false)
            )
    ) { event ->
        if (!event.isFromGuild) return@register
        when (event.subcommandName) {
            "list" -> {
                val starboards = bot.database.trnsctn {
                    val g = event.guild!!.m
                    g.starboards.map { Field(it.name, "<#${it.channel}> ${it.threshold}x ${it.emote}${if (it.xpMultiplier != 1.0) "(${it.xpMultiplier}x XP gain)" else ""}", false) }
                }
                event.replyEmbeds(embed("Starboards", content = starboards)).ephemeral().await()
            }
            "remove" -> {
                bot.commands.assertAdmin(event)
                val name = event.getOption("name")!!.asString.trim()
                bot.database.trnsctn {
                    val g = event.guild!!.m
                    val found = ExposedDatabase.Starboard.find { (ExposedDatabase.Starboards.name eq name) and (ExposedDatabase.Starboards.guild eq g.id) }.firstOrNull()
                    found ?: throw CommandException("Starboard '$name' not found!")
                    found.delete()
                }
                event.replyEmbeds(embed("Starboard Deleted")).await()
            }
            "add" -> {
                bot.commands.assertAdmin(event)
                val name = event.getOption("name")!!.asString.trim()
                val channel = event.getOption("channel")!!.asTextChannel ?: throw CommandException("Must be a text channel!")
                val emoteName = event.getOption("emote")!!.asString.trim()
                val threshold = event.getOption("threshold")!!.asInt
                val multiplier = event.getOption("multiplier")!!.asDouble

                val emote = event.guild!!.getEmoteById(emoteName.substringAfterLast(':').removeSuffix(">"))?.asMention
                    ?: if (Emotes.isValid(emoteName)) emoteName else null
                emote ?: throw CommandException("Emoji not found!")
                if (threshold !in 1..1_000_000) throw CommandException("Invalid threshold!")
                if (multiplier !in 0.0..1000.0) throw CommandException("Invalid multiplier!")
                bot.database.trnsctn {
                    val g = event.guild!!.m
                    if (g.starboards.any { it.name == name }) throw CommandException("A starboard called '$name' already exists!")
                    if (g.starboards.count() > 20) throw CommandException("A guild can only have 20 starboards!")
                    if (g.starboards.any { it.emote == emote && it.channel == channel.idLong }) throw CommandException("Can't have two starboards with the same emote in the same channel!")
                    ExposedDatabase.Starboard.new {
                        this.guild = g
                        this.threshold = threshold
                        this.emote = emote
                        this.channel = channel.idLong
                        this.name = name
                        this.xpMultiplier = multiplier
                    }
                }
                event.replyEmbeds(embed("Starboard Created")).await()
            }
            "modify" -> {
                bot.commands.assertAdmin(event)
                bot.database.trnsctn {
                    val oldName = event.getOption("name")!!.asString.trim()

                    val g = event.guild!!.m
                    val existing = ExposedDatabase.Starboard.find { (ExposedDatabase.Starboards.name eq oldName) and (ExposedDatabase.Starboards.guild eq g.id) }.firstOrNull() ?: throw CommandException("Starboard '$oldName' not found!")

                    val name = event.getOption("new-name")?.asString?.trim() ?: oldName
                    val channel = event.getOption("channel")?.asTextChannel?.idLong ?: existing.channel
                    val emoteName = event.getOption("emote")?.asString?.trim() ?: existing.emote
                    val threshold = event.getOption("threshold")?.asInt ?: existing.threshold
                    val multiplier = event.getOption("multiplier")?.asDouble ?: existing.xpMultiplier

                    val emote = event.guild!!.getEmoteById(emoteName.substringAfterLast(':').removeSuffix(">"))?.asMention
                        ?: if (Emotes.isValid(emoteName)) emoteName else null
                    emote ?: throw CommandException("Emote not found!")
                    if (threshold !in 1..1_000_000) throw CommandException("Invalid threshold!")
                    if (multiplier !in 0.0..1000.0) throw CommandException("Invalid multiplier!")

                    if (g.starboards.any { it.name == name } && name != oldName) throw CommandException("A starboard called '$name' already exists!")
                    if (g.starboards.any { it.emote == emote && it.channel == channel } && emoteName != existing.emote) throw CommandException("Can't have two starboards with the same emote in the same channel!")
                    existing.guild = g
                    existing.threshold = threshold
                    existing.emote = emote
                    existing.channel = channel
                    existing.name = name
                    existing.xpMultiplier = multiplier
                }
                event.replyEmbeds(embed("Starboard Modified")).await()
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
        val channel = event.getOption("channel")!!.asMessageChannel

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
            guild.fightCooldown = Duration.ofMillis((secs * 1000).roundToLong())
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
