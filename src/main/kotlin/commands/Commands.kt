package commands

import commands.CommandHandler.*
import commands.CommandHandler.Command.CommandBuilder
import discord.Bot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.interactions.components.Button
import net.dv8tion.jda.api.utils.MiscUtil
import net.dv8tion.jda.api.utils.TimeUtil
import org.jetbrains.exposed.sql.transactions.transaction
import utils.admin
import utils.toHumanReadable
import java.awt.Color
import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAccessor
import kotlin.math.min
import kotlin.random.Random

class Commands(val bot: Bot) {
    private val handler = CommandHandler<Message, MessageEmbed> { commandResult ->
        commandResult.sender.replyEmbeds(commandResult.value ?: return@CommandHandler).queue()
    }

    companion object {
        private fun String.stripPings() = this.replace("@", "\\@")

        fun embed(
            title: String,
            url: String? = null,
            content: List<Field> = emptyList(),
            imgUrl: String? = null,
            thumbnail: String? = null,
            author: String? = null,
            authorUrl: String? = null,
            timestamp: TemporalAccessor? = null,
            color: Color? = null,
            description: String? = null
        ): MessageEmbed {
            val builder = EmbedBuilder()
            builder.setTitle(title, url)
            builder.fields.addAll(content.map { Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline) })
            builder.setImage(imgUrl)
            builder.setThumbnail(thumbnail)
            builder.setAuthor(author, authorUrl)
            builder.setTimestamp(timestamp)
            builder.setColor(color)
            builder.setDescription(description?.stripPings())
            return builder.build()
        }
    }

    init {
        class UserArg(name: String, optional: Boolean = false, vararg: Boolean = false) : Argument<User>(User::class, { bot.jda.retrieveUserById(it.removeSurrounding("<@!", ">")).complete()!! }, { bot.jda.retrieveUserById(it.removeSurrounding("<@!", ">")).complete() != null }, name, optional, vararg)

        val unauthorized = EmbedBuilder().setTitle("Insufficient Permissions").setColor(Color(235, 70, 70)).build()
        handler.register(
            CommandBuilder<Message, MessageEmbed>("help", "commands").arg(IntArg("page", true))
                .ran { sender, args ->
                    val page = args.getOrDefault("page", 1) as Int - 1

                    val helpPages = handler.commands.sortedBy { it.base.contains("help") }.chunked(10)
                    fun helpPage(page: Int): MessageEmbed {
                        val p = page.coerceIn(helpPages.indices)

                        val commands = helpPages[p]
                        val builder = EmbedBuilder()
                        builder.setTitle("Help ${p + 1}/${helpPages.size}")
                        for (command in commands) {
                            val base = if (command.base.size == 1) command.base.first() else command.base.joinToString(
                                prefix = "(",
                                separator = "/",
                                postfix = ")"
                            )
                            builder.addField(
                                base + " " + command.arguments.joinToString(" ") { if (it.optional) "[${it.name}]" else "<${it.name}>" },
                                command.help,
                                false
                            )
                        }
                        return builder.build()
                    }

                    var p = page

                    fun actionRow() = listOf(Button.primary("help-prev", "Previous Page").withDisabled(p == 0), Button.primary("help-next", "Next Page").withDisabled(p == helpPages.size - 1))

                    val message = sender.replyEmbeds(helpPage(page))
                        .setActionRow(actionRow())
                        .complete()
                    val lambda = { event: ButtonClickEvent ->
                        if (event.messageId == message.id && event.user == sender.author) {
                            if (event.button?.id == "help-prev") {
                                p--
                                p = p.coerceIn(helpPages.indices)
                                event.editMessageEmbeds(helpPage(p.coerceIn(helpPages.indices))).setActionRow(actionRow()).queue()
                            } else {
                                p++
                                p = p.coerceIn(helpPages.indices)
                                event.editMessageEmbeds(helpPage(p)).setActionRow(actionRow()).queue()
                            }
                        } else if (event.messageId == message.id) {
                            event.message?.embeds?.let { event.editMessageEmbeds(it) }
                        }
                    }
                    bot.buttonListeners.add(lambda)
                    bot.scope.launch {
                        delay(120_000)
                        bot.buttonListeners.remove(lambda)
                    }
                    InternalCommandResult(null, true)
            }.help("Shows a list of commands and their descriptions.")
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("invite").ran { _, _ ->
                return@ran InternalCommandResult(embed("Invite Link",
                    description = "Admin invite: https://discord.com/api/oauth2/authorize?client_id=${bot.jda.selfUser.id}&permissions=8&scope=bot\n" +
                            "Basic permissions: https://discord.com/api/oauth2/authorize?client_id=${bot.jda.selfUser.id}&permissions=271830080&scope=bot"
                ), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("reactionrole").args(StringArg("message link"), StringArg("reactions", vararg = true))
                .help("Takes a message link and a space-separated list following the format \"emote name:role name\" (including the quotes)")
                .ran { sender, args ->
                    val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                    if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                    val reactionsMap = (args["reactions"] as List<Any?>)
                            .mapNotNull { it as? String }  // Cast to List<String>, weeding out any nulls
                            .map { Pair(it.substringBeforeLast(":"), it.substringAfterLast(":").dropWhile { c -> c == ' ' }) }  // Split by colon into emote and role name, stripping spaces from the start of the latter
                            .mapNotNull { sender.guild.getRolesByName(it.second, true).firstOrNull()?.run { Pair(it.first, this) } }  // Retrieve all the roles
                            .filter { sender.member?.canInteract(it.second) == true }  // Prevent privilege escalation
                            .associate { Pair(it.first, it.second.idLong) }  // Convert to map for use by the database

                    val link = args["message link"] as String
                    val messageId = link.substringAfterLast("/")
                    val channelId = link.removeSuffix("/$messageId").substringAfterLast("/")
                    val msg = sender.guild.getTextChannelById(
                        channelId.toLongOrNull() ?: return@ran InternalCommandResult(embed("Invalid message link"), false))
                        ?.retrieveMessageById(
                            messageId.toLongOrNull() ?: return@ran InternalCommandResult(embed("Invalid message link"), false))
                        ?.complete() ?: return@ran InternalCommandResult(embed("Invalid message link"), false)
                    bot.database.addAutoRole(msg, reactionsMap)

                    for (reaction in reactionsMap) {
                        msg.addReaction(reaction.key).queue()
                    }

                    return@ran InternalCommandResult(embed("Successfully added ${reactionsMap.size} reaction roles:", content = reactionsMap.map { Field(it.key, sender.guild.getRoleById(it.value)?.name, false) }), true)
                }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("purge").arg(IntArg("count")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                val count = (args["count"] as? Int ?: 10).coerceIn(1, 1000) + 1
                bot.scope.launch {
                    delay(2000)
                    var yetToDelete = count
                    while (yetToDelete > 0) {
                        val messages = sender.channel.history.retrievePast(min(100, yetToDelete)).complete()
                        if (messages.size == 1) {
                            messages.first().delete().complete()
                        } else {
                            val bulkDeletable = messages.filter { TimeUtil.getDiscordTimestamp(System.currentTimeMillis() - 14 * 24 * 60 * 60 * 1000) < MiscUtil.parseSnowflake(it.id) }
                            sender.textChannel.deleteMessages(bulkDeletable).complete()
                            for (item in messages - bulkDeletable) {
                                item.delete().queue()
                                delay(100)
                            }
                        }
                        if (messages.size != min(100, yetToDelete)) break
                        yetToDelete -= messages.size
                        delay(1100)
                    }
                }
                return@ran InternalCommandResult(embed("Purging ${count - 1} messages..."), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("stats")
                .help("Shows bot statistics")
                .ran { sender, _ ->
                    val builder = EmbedBuilder()
                    builder.setTitle("Statistics and Info")
                    builder.setThumbnail(sender.guild.iconUrl)
                    builder.setDescription("""
                        **Bot Info**
                        Members: ${bot.database.users().size}
                        Guilds: ${bot.database.guilds().size}
                        Commands: ${handler.commands.size}
                        Gateway ping: ${bot.jda.gatewayPing}ms
                        Rest ping: ${bot.jda.restPing.complete()}ms
                        Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable()}
                        Git: ${bot.config.gitUrl}
                        Invite: _see ${bot.database.trnsctn { bot.database.guild(sender.guild).prefix} }invite_
                    """.trimIndent())
                    InternalCommandResult(builder.build(), true)
                }
        )

        val administration = CommandCategory("Administration", mutableListOf())
        handler.register(
            CommandBuilder<Message, MessageEmbed>("ban").arg(StringArg("user id"))
                .help("Bans a user.")
                .ran { sender, args ->
                    val id = (args["user id"] as String).removeSurrounding(prefix = "<@!", suffix = ">")
                    val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                    if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                    sender.guild.ban(id, 0).complete()
                    return@ran InternalCommandResult(embed("Banned", description = "Banned <@!$id>"), true)
                }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unban").arg(StringArg("user id"))
                .help("Unbans a user.")
                .ran { sender, args ->
                    val id = (args["user id"] as String).removeSurrounding(prefix = "<@!", suffix = ">")
                    val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                    if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                    sender.guild.unban(id).complete()
                    return@ran InternalCommandResult(embed("Unbanned", description = "Unbanned <@!$id>"), true)
                }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("kick").arg(StringArg("user id"))
                .help("Kicks a user.")
                .ran { sender, args ->
                    val id = (args["user id"] as String).removeSurrounding(prefix = "<@!", suffix = ">")
                    val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                    if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                    sender.guild.kick(id).complete()
                    return@ran InternalCommandResult(embed("Kicked", description = "Kicked <@!$id>"), true)
                }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("slots", "gamble")
                .help("Play the slots.")
                .ran { sender, _ ->
                val emotes = ":cherries:, :lemon:, :seven:, :broccoli:, :peach:, :green_apple:".split(", ")

//                val numberCorrect = /*weightedRandom(
//                    listOf(1,     2,     3,     4,     5,      6),
//                    listOf(0.6,   0.2,   0.15,  0.03,  0.0199, 0.000001)
//                )*/ Random.nextInt(1, 6)

                fun section(count: Int, index: Int): List<String> {
                    return emotes.plus(emotes).slice(index..(index + emotes.size)).take(count)
                }

                val indexes = MutableList(6) { Random.nextInt(0, 6) }
//                for (i in 0 until numberCorrect) {
//                    indexes.add(indexes[0])
//                }
//                for (i in 0 until emotes.size - numberCorrect) {
//                    indexes.add(Random.nextInt(emotes.size))
//                }
                indexes.shuffle()
                val wheels = indexes.map { section(3, it) }
                var output = ""
                try {
                    output = (0 until 3).joinToString("\n") { n -> wheels.joinToString("   ") { it[n] } }
                } catch (e: Exception) { e.printStackTrace() }
                InternalCommandResult(embed("${sender.author.name} is playing...", description = output), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("trace", "lookup").arg(UserArg("ID"))
                .help("Shows information about a user.")
                .ran { sender, args ->
                val user = args["ID"] as User
                val botAdmin = bot.database.user(sender.author).botAdmin
                val isAdmin = sender.member.admin || botAdmin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                val dbUser = bot.database.user(user)

                val embed = EmbedBuilder()
                val dmEmbed = EmbedBuilder()
                embed.setTitle("User ${user.asTag} (${user.id})")
                dmEmbed.setTitle("User ${user.asTag} (${user.id})")

                embed.setThumbnail(user.avatarUrl)
                dmEmbed.setThumbnail(user.avatarUrl)

                embed.addField("Account Creation Date:", user.timeCreated.toString(), true)
                dmEmbed.addField("Account Creation Date:", user.timeCreated.toString(), true)

                embed.addField("Flags:", if (user.flags.isEmpty()) "None" else user.flags.joinToString { it.getName() }, false)
                dmEmbed.addField("Flags:", if (user.flags.isEmpty()) "None" else user.flags.joinToString { it.getName() }, false)

                dmEmbed.addField("**Database Info**", "", false)
                if (botAdmin) {
                    for (guild in transaction(bot.database.db) { dbUser.guilds.toList() }) {
                        val resolved = sender.jda.getGuildById(guild.discordId) ?: continue
                        val member = resolved.getMember(user) ?: continue
                        dmEmbed.addField(
                            resolved.name,
                            "Join date: ${member.timeJoined}\nNickname: ${member.effectiveName}\nRoles: ${member.roles.joinToString { it.name }}\nAdmin: ${member.admin}",
                            false
                        )
                    }
                } else {
                    dmEmbed.addField("Insufficient permissions", "To view information from the database you must be a global admin.", false)
                }

                sender.author.openPrivateChannel().complete().sendMessageEmbeds(dmEmbed.build()).queue()
                return@ran InternalCommandResult(embed.build(), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("prefix", "setprefix").arg(StringArg("prefix"))
                .help("Sets the bot's prefix.")
                .ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val prefix = args["prefix"] as? String ?: return@ran InternalCommandResult(embed("Invalid prefix"), false)

                if (!prefix.matches(".{1,30}".toRegex())) {
                    return@ran InternalCommandResult(embed("Invalid prefix"), false)
                }

                transaction(bot.database.db) { bot.database.guild(sender.guild).prefix = prefix }

                return@ran InternalCommandResult(embed("Successfully set prefix"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("admin").arg(UserArg("user"))
                .help("Makes a user a bot admin.")
                .ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                transaction(bot.database.db) { bot.database.user(user).botAdmin = true }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a global admin"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unadmin", "deadmin").arg(UserArg("user"))
                .help("Removes a user's bot admin status.")
                .ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                if (user.id in bot.config.permaAdmins) return@ran InternalCommandResult(embed("Cannot un-admin a permanent admin.", color = Color(235, 70, 70)), false)

                transaction(bot.database.db) { bot.database.user(user).botAdmin = false }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a non-admin"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("mute").args(UserArg("user"), LongArg("seconds"))
                .help("Mutes a user (prevents them from sending messages) for the given time in seconds.")
                .ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin

                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User
                val time = (args["seconds"] as Long).coerceAtLeast(15)
                val member = sender.guild.getMember(user) ?: return@ran InternalCommandResult(embed("Must be a member of this server"), false)

                val end = Instant.now().plusSeconds(time.toLong())
                bot.database.addMute(user, member.roles, end, sender.guild)

                member.guild.modifyMemberRoles(member, listOf()).complete()

                return@ran InternalCommandResult(embed("Muted ${user.asTag} for ${Duration.ofSeconds(time.toLong()).toHumanReadable()}"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unmute").args(UserArg("user"))
                .help("Ends a mute.")
                .ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin

                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                val mute = bot.database.findMute(user, sender.guild) ?: return@ran InternalCommandResult(embed("${user.asMention} isn't muted!"), false)

                sender.guild.modifyMemberRoles(sender.guild.getMember(user)!!, bot.database.roleIds(mute).map { sender.guild.getRoleById(it) }).complete()

                return@ran InternalCommandResult(embed("Unmuted ${user.asTag}"), true)
            }
        )

        registerAudioCommands(bot, handler)
    }

    fun handle(message: Message) {
        bot.scope.launch {
            try {
                val prefix = bot.database.guild(message.guild).prefix
                handler.handleAndSend(prefix, message.contentRaw, message)
            } catch (e: Exception) {
                bot.log.warning("ERROR FROM COMMAND '${message.contentRaw}':")
                bot.log.warning(e.stackTraceToString())
            }
        }
    }
}
