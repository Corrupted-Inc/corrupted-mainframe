package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.calculator.Calculator
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.*
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.Command.CommandBuilder
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.admin
import com.github.corruptedinc.corruptedmainframe.utils.containsAny
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
import com.github.corruptedinc.corruptedmainframe.utils.toHumanReadable
import com.jagrosh.jdautilities.command.CommandClientBuilder
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.option
import dev.minn.jda.ktx.listener
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.BaseCommand
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import java.awt.Color
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import kotlin.math.min
import kotlin.random.Random

class Commands(val bot: Bot) {
    val handler = CommandHandler<Message, MessageEmbed>({ commandResult ->
        commandResult.sender.replyEmbeds(commandResult.value ?: return@CommandHandler).complete()
    }, { _, exception -> embed("Error", description = exception.message, color = ERROR_COLOR) })

    companion object {
        private fun String.stripPings() = this.replace("@", "\\@")

        @Suppress("LongParameterList")  // They've got default arguments
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
            description: String? = null,
            stripPings: Boolean = true
        ): MessageEmbed {
            val builder = EmbedBuilder()
            builder.setTitle(title, url)
            builder.fields.addAll(if (stripPings) content.map {
                Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline)
            } else content)
            builder.setImage(imgUrl)
            builder.setThumbnail(thumbnail)
            builder.setAuthor(author, authorUrl)
            builder.setTimestamp(timestamp)
            builder.setColor(color)
            builder.setDescription(if (stripPings) description?.stripPings() else description)
            return builder.build()
        }

        private const val MIN_CALCULATOR_PRECISION = 2
        private const val MAX_CALCULATOR_PRECISION = 512
        private const val DEF_CALCULATOR_PRECISION = 20

        private const val SLOTS_HEIGHT = 3
        private const val SLOTS_WIDTH = 6

        private const val MAX_PURGE = 1_000
        private const val PURGE_DELAY = 2000L

        const val BUTTON_TIMEOUT = 120_000L

        private const val HELP_PAGE_LENGTH = 10

        private val ERROR_COLOR = Color(235, 70, 70)

        fun adminInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId&permissions=8&scope=bot"

        fun basicInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId&permissions=271830080&scope=bot"

        val unauthorized = EmbedBuilder().setTitle("Insufficient Permissions")
            .setColor(ERROR_COLOR).build()
    }

    // TODO custom listener pool that handles exceptions
    // TODO cleaner way than upserting the command every time
    fun register(data: CommandData, lambda: suspend (SlashCommandEvent) -> Unit) {
        bot.jda.upsertCommand(data).complete()
        bot.jda.listener<SlashCommandEvent> {
            if (it.name == data.name) {
                try {
                    try {
                        lambda(it)
                    } catch (e: CommandException) {
                        it.replyEmbeds(embed("Error", description = e.message, color = ERROR_COLOR)).complete()
                    } catch (e: Exception) {
                        it.replyEmbeds(embed("Internal Error", color = ERROR_COLOR)).complete()
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
            }
        }
    }

    fun assertAdmin(event: SlashCommandEvent) {
        if (!(bot.database.user(event.user).botAdmin || event.member.admin))
            throw CommandException("You need to be an administrator to run this command!")
    }

    init {
        register(CommandData("invite", "Invite Link")) {
            it.replyEmbeds(embed("Invite Link",
                description = "[Admin invite](${adminInvite(bot.jda.selfUser.id)})\n" +
                              "[Basic permissions](${basicInvite(bot.jda.selfUser.id)})"
            )).complete()
        }

        register(CommandData("reactionrole", "description")
            .addOption(OptionType.STRING, "message", "Message link", true)
            .addOption(OptionType.STRING, "reactions", "Reactions", true)) { event ->

            assertAdmin(event)

            val reactionsMap = event.getOption("reactions")!!.asString.split(", ")
                    // Split by colon into emote and role name, stripping spaces from the start of the latter
                    .map { Pair(
                        it.substringBeforeLast(":"),
                        it.substringAfterLast(":").dropWhile { c -> c == ' ' })
                    }

                    // Retrieve all the roles
                    .mapNotNull {
                        event.guild?.getRolesByName(it.second, true)
                            ?.firstOrNull()?.run { Pair(it.first, this) }
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
            )?.complete() ?: throw CommandException("Invalid message link")

            bot.database.moderationDB.addAutoRole(msg, reactionsMap)

            for (reaction in reactionsMap) {
                msg.addReaction(reaction.key).complete()
            }

            event.replyEmbeds(embed("Successfully added ${reactionsMap.size} reaction roles:",
                content = reactionsMap.map {
                    Field(it.key, event.guild?.getRoleById(it.value)?.name, false)
                })).complete()
        }


        register(CommandData("purge", "Purges messages").addOption(OptionType.INTEGER, "count", "The number of messages to purge", true)) { event ->
            val count = (event.getOption("count")?.asLong ?: throw CommandException("Invalid number")).toInt()
                .coerceIn(1, MAX_PURGE) + 1

            assertAdmin(event)

            event.deferReply().complete()

            delay(PURGE_DELAY)
            var yetToDelete = count
            while (yetToDelete > 0) {
                @Suppress("MagicNumber")
                val maxDeletion = 100

                val messages = event.channel.history.retrievePast(min(maxDeletion, yetToDelete))
                    .complete()
                if (messages.size == 1) {
                    messages.first().delete().complete()
                } else {
                    @Suppress("MagicNumber")
                    val twoWeeksAgo = System.currentTimeMillis() -
                            Duration.of(14, ChronoUnit.DAYS).toMillis()

                    val bulkDeletable = messages.filter {
                        TimeUtil.getDiscordTimestamp(twoWeeksAgo) < MiscUtil.parseSnowflake(
                            it.id
                        )
                    }
                    event.textChannel.deleteMessages(bulkDeletable).complete()
                    for (item in messages - bulkDeletable) {
                        item.delete().complete()
                        delay(maxDeletion.toLong())
                    }
                }
                if (messages.size != min(maxDeletion, yetToDelete)) break
                yetToDelete -= messages.size
                @Suppress("MagicNumber")
                delay(1100)  // To prevent ratelimit being exceeded
            }
        }

        register(CommandData("stats", "Shows bot statistics")) { event ->
            // TODO daily commands executed, new servers, etc

            val builder = EmbedBuilder()
            builder.setTitle("Statistics and Info")
            builder.setThumbnail(event.guild?.iconUrl)
            val id = bot.jda.selfUser.id
            builder.setDescription("""
                **Bot Info**
                Members: ${bot.database.users().size}
                Guilds: ${bot.database.guildCount()}
                Commands: ${handler.commands.size}
                Gateway ping: ${bot.jda.gatewayPing}ms
                Rest ping: ${bot.jda.restPing.complete()}ms
                Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable()}
                Git: ${bot.config.gitUrl}
                Invite: [Admin invite](${adminInvite(id)})  [basic permissions](${basicInvite(id)})
            """.trimIndent())
            event.replyEmbeds(builder.build()).complete()
        }

        register(CommandData("ban", "Ban a user")
            .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
            assertAdmin(event)
            val user = event.getOption("user")?.asUser
                ?: throw CommandException("Failed to find user")

            event.guild?.ban(user, 0)?.complete() ?: throw CommandException("Must be run in a server!")
            event.replyEmbeds(embed("Banned", description = "Banned ${user.asMention}")).complete()
        }

        register(CommandData("unban", "Unban a user")
            .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
            assertAdmin(event)
            val user = event.getOption("user")?.asUser ?: throw CommandException("Failed to find user!")

            event.guild?.unban(user)?.complete() ?: throw CommandException("Couldn't unban user!")
            event.replyEmbeds(embed("Unbanned", description = "Unbanned ${user.asMention}"))
        }

        register(CommandData("kick", "Kick a user")
            .addOption(OptionType.USER, "user", "The user to kick", true)) { event ->
            assertAdmin(event)
            val user = event.getOption("user")?.asUser
                ?: throw CommandException("Failed to find user")

            event.guild?.kick(user.id)?.complete() ?: throw CommandException("Must be run in a server!")
            event.replyEmbeds(embed("Kicked", description = "Kicked ${user.asMention}")).complete()
        }

        register(CommandData("slots", "Play the slots")) { event ->
            val emotes = ":cherries:, :lemon:, :seven:, :broccoli:, :peach:, :green_apple:".split(", ")

//                val numberCorrect = /*weightedRandom(
//                    listOf(1,     2,     3,     4,     5,      6),
//                    listOf(0.6,   0.2,   0.15,  0.03,  0.0199, 0.000001)
//                )*/ Random.nextInt(1, 6)

            fun section(count: Int, index: Int): List<String> {
                return emotes.plus(emotes).slice(index..(index + emotes.size)).take(count)
            }

            val indexes = MutableList(SLOTS_WIDTH) { Random.nextInt(0, emotes.size) }
//                for (i in 0 until numberCorrect) {
//                    indexes.add(indexes[0])
//                }
//                for (i in 0 until emotes.size - numberCorrect) {
//                    indexes.add(Random.nextInt(emotes.size))
//                }
            indexes.shuffle()
            val wheels = indexes.map { section(SLOTS_HEIGHT, it) }
                val out = (0 until SLOTS_HEIGHT).joinToString("\n") { n ->
                    wheels.joinToString("   ") { it[n] }
                }
            event.replyEmbeds(embed("${event.user.name} is playing...", description = out)).complete()
        }

        register(CommandData("admin", "Makes a user an admin")
            .addOption(OptionType.USER, "user", "The user to make an admin")) { event ->
                // Don't replace with assertAdmin(), this doesn't allow server admins to make changes
                val isAdmin = bot.database.user(event.user).botAdmin ||
                        bot.config.permaAdmins.contains(event.user.id)

                if (!isAdmin) throw CommandException("You must be a bot admin to use this command!")

                val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find that user!")

                transaction(bot.database.db) { bot.database.user(user).botAdmin = true }

                event.replyEmbeds(embed("Successfully made @${user.asTag} a global admin")).complete()
        }

        register(CommandData("globalban", "Ban a user from using the bot")
            .addOption(OptionType.USER, "user", "The user to ban", true)) { event ->
                if (!bot.database.user(event.user).botAdmin)
                    throw CommandException("You must be a bot admin to use this command!")

                val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find the user!")

                bot.database.ban(user)

                event.replyEmbeds(embed("Banned ${user.asMention}")).complete()
        }

        register(CommandData("globalunban", "Unban a user from using the bot")
            .addOption(OptionType.USER, "user", "The user to unban", true)) { event ->
            if (!bot.database.user(event.user).botAdmin)
                throw CommandException("You must be a bot admin to use this command!")

            val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find the user!")

            bot.database.unban(user)

            event.replyEmbeds(embed("Unbanned ${user.asMention}")).complete()
        }

        register(CommandData("unadmin", "Revokes a user's bot admin status")
            .addOption(OptionType.USER, "user", "The user to make no longer an admin")) { event ->

            // Don't replace with assertAdmin(), this doesn't allow server admins to make changes
            val isAdmin = bot.database.user(event.user).botAdmin ||
                    bot.config.permaAdmins.contains(event.user.id)

            if (!isAdmin) throw CommandException("You must be a bot admin to use this command!")

            val user = event.getOption("user")?.asUser ?: throw CommandException("Couldn't find that user!")

            if (bot.config.permaAdmins.contains(user.id)) throw CommandException("That user is a permanent admin!")

            transaction(bot.database.db) { bot.database.user(user).botAdmin = false }

            event.replyEmbeds(embed("Successfully made @${user.asTag} not a global admin")).complete()
        }

        register(CommandData("mute", "Mute a user")
            .addOption(OptionType.USER, "user", "The user to mute", true)
            .addOption(OptionType.INTEGER, "seconds", "The number of seconds to mute the user for", true)) { event ->

            assertAdmin(event)

            val user = event.getOption("user")?.asUser
            val time = event.getOption("seconds")?.asLong?.coerceAtLeast(0) ?: 0
            val member = user?.let { event.guild?.getMember(it) }
                ?: throw CommandException("Must be a member of this server")

            val end = Instant.now().plusSeconds(time)
            bot.database.moderationDB.addMute(user, member.roles, end, event.guild!!)

            member.guild.modifyMemberRoles(member, listOf()).complete()

            event.replyEmbeds(
                embed("Muted ${user.asTag} for ${Duration.ofSeconds(time).toHumanReadable()}")).complete()
        }

        register(CommandData("unmute", "Unmute a user").addOption(OptionType.USER, "user", "The user to unmute")) { event ->

            assertAdmin(event)
            val user = event.getOption("user")?.asUser!!

            val mute = bot.database.moderationDB.findMute(user, event.guild
                ?: throw CommandException("This command must be run in a guild!")
            ) ?: throw CommandException("${user.asMention} isn't muted!")

            event.guild!!.modifyMemberRoles(
                event.guild!!.getMember(user)!!,
                bot.database.moderationDB.roleIds(mute).map { event.guild!!.getRoleById(it) }).complete()

            event.replyEmbeds(embed("Unmuted ${user.asTag}")).complete()
        }

        register(CommandData("math", "Evaluate arbitrary-precision math")
            .addOption(OptionType.INTEGER, "precision", "The precision in digits")
            .addOption(OptionType.STRING, "expression", "The expression to evaluate", true)
        ) { event ->
            val exp = (event.getOption("expression")!!.asString).replaceBefore("=", "")
                .removePrefix(" ")  // for now variables are not allowed

            if (exp.containsAny(listOf("sqrt", "sin", "cos", "tan"))) {
                throw CommandException("This is a very rudimentary calculator that does not support anything" +
                        " except `+`, `-`, `*`, `/`, and `^`.")
            }
            val precision = (event.getOption("precision")?.asLong?.toInt() ?: DEF_CALCULATOR_PRECISION)
                .coerceIn(MIN_CALCULATOR_PRECISION, MAX_CALCULATOR_PRECISION)

            // User doesn't need to see an exception
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            try {
                val result = Calculator(MathContext(precision, RoundingMode.HALF_UP)).evaluate(exp)
                event.replyEmbeds(embed("Result", description = "$exp = ${result.toPlainString()}")).complete()
            } catch (e: Exception) {
                throw CommandException("Failed to evaluate '$exp'!")
            }
        }

        register(CommandData("levelnotifs", "Toggle level notifications")
            .addOption(OptionType.BOOLEAN, "enabled", "If you should be shown level notifications")) { event ->
                val enabled = event.getOption("enabled")!!.asBoolean
                bot.database.setPopups(event.user, event.guild ?:
                throw CommandException("This command must be run in a server!"), enabled)
                event.replyEmbeds(embed("Set level popups to $enabled")).complete()
            }

//        handler.register(
//            CommandBuilder<Message, MessageEmbed>("level").args(UserArg("user", true)).ran { sender, args ->
//                TODO()
//            }
//        )

        registerAudioCommands(bot, this)
    }

    fun handle(message: Message) {
        bot.scope.launch {
            @Suppress("TooGenericExceptionCaught")
            try {
                val prefix = bot.database.guild(message.guild).prefix
                handler.handleAndSend(prefix, message.contentRaw, message)
            } catch (e: Exception) {
                bot.log.warn("ERROR FROM COMMAND '${message.contentRaw}':")
                bot.log.warn(e.stackTraceToString())
            }
        }
    }
}
