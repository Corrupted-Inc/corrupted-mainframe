package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.calculator.Calculator
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.VARCHAR_MAX_LENGTH
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Reminders
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.admin
import com.github.corruptedinc.corruptedmainframe.utils.containsAny
import com.github.corruptedinc.corruptedmainframe.utils.toHumanReadable
import dev.minn.jda.ktx.Message
import dev.minn.jda.ktx.interactions.replyPaginator
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.MiscUtil
import net.dv8tion.jda.api.utils.TimeUtil
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.awt.Color
import java.math.MathContext
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import java.util.*
import kotlin.math.min
import kotlin.random.Random
import kotlin.system.exitProcess

class Commands(val bot: Bot) {

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

        private const val MAX_PURGE = 1000
        private const val PURGE_DELAY = 2000L

        const val BUTTON_TIMEOUT = 120_000L

        private val ERROR_COLOR = Color(235, 70, 70)

        private const val REMINDERS_PER_PAGE = 15
        private const val MAX_REMINDERS = 128

        fun adminInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId&permissions=8&scope=applications.commands%20bot"

        fun basicInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId" +
                    "&permissions=271830080&scope=applications.commands%20bot"

        val unauthorized = EmbedBuilder().setTitle("Insufficient Permissions")
            .setColor(ERROR_COLOR).build()
    }

    val newCommands = mutableListOf<CommandData>()

    // TODO custom listener pool that handles exceptions
    // TODO cleaner way than upserting the command every time
    fun register(data: CommandData, lambda: suspend (SlashCommandEvent) -> Unit) {
        newCommands.add(data)
        bot.jda.listener<SlashCommandEvent> {
            if (it.name == data.name) {
                try {
                    @Suppress("TooGenericExceptionCaught")
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

    @Suppress("SwallowedException")
    fun finalizeCommands() {
        bot.log.info("Registering ${newCommands.size} commands...")
        bot.jda.guilds.map {
            try {
                it.updateCommands().addCommands(newCommands).complete()
            } catch (e: ErrorResponseException) {
                bot.log.error("Failed to register commands in ${it.name}")
            }
        }
        bot.log.info("Done")

        bot.jda.listener<GuildJoinEvent> { event ->
            event.guild.updateCommands().addCommands(newCommands).complete()
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

        register(CommandData("reactionrole", "Reactions are specified with the format " +
                "'\uD83D\uDC4D:rolename, \uD83D\uDCA5:otherrolename'.")
            .addOption(OptionType.STRING, "message", "Message link", true)
            .addOption(OptionType.STRING, "reactions", "Reactions", true)) { event ->

            assertAdmin(event)

            val reactionsMap = event.getOption("reactions")!!.asString.removeSurrounding("\"").split(", ")
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


        register(CommandData("purge", "Purges messages")
            .addOption(OptionType.INTEGER, "count", "The number of messages to purge", true)) { event ->

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
                Commands: ${newCommands.size}
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

        register(CommandData("unmute", "Unmute a user")
            .addOption(OptionType.USER, "user", "The user to unmute")) { event ->

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
            .addOption(OptionType.STRING, "expression", "The expression to evaluate", true)
            .addOption(OptionType.INTEGER, "precision", "The precision in digits")
        ) { event ->
            val exp = (event.getOption("expression")!!.asString).removeSurrounding("\"")  // discord bad
                .replaceBefore("=", "")
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

        register(CommandData("reminders", "Manage reminders").addSubcommands(
            SubcommandData("list", "List your reminders"),
            SubcommandData("add", "Add a reminder")
                .addOption(OptionType.STRING, "name", "The name of the reminder", true)
                .addOption(OptionType.STRING, "time", "The time at which you will be reminded", true),
            SubcommandData("remove", "Remove a reminder")
                .addOption(OptionType.STRING, "name", "The name of the reminder to remove", true))
        ) { event ->
            when (event.subcommandName) {
                "list" -> {
                    val output = mutableListOf<Field>()
                    bot.database.trnsctn {
                        val user = bot.database.user(event.user)
                        val reminders = ExposedDatabase.Reminder.find { Reminders.user eq user.id }
                        for (item in reminders) {
                            output.add(Field(item.text, "<t:${item.time.toEpochSecond(ZoneOffset.UTC)}:R>", false))
                        }
                    }

                    val embeds = output.chunked(REMINDERS_PER_PAGE)
                        .map { embed("${event.user.asTag}'s Reminders", content = it) }
                    if (embeds.isEmpty()) {
                        event.replyEmbeds(embed("No reminders")).complete()
                        return@register
                    }
                    @Suppress("SpreadOperator")  // don't have a choice here
                    event.replyPaginator(*embeds.toTypedArray()).complete()
                }

                "add" -> {
                    val name = event.getOption("name")!!.asString
                    val rawTime = event.getOption("time")!!.asString

                    if (name.length > VARCHAR_MAX_LENGTH - 1)
                        throw CommandException("Name length must be less than 255 characters!")

                    if (name == "all") throw CommandException("Name cannot be 'all'!")

                    val zone = bot.database.trnsctn { bot.database.user(event.user).timezone }

                    val parser = PrettyTimeParser(TimeZone.getTimeZone(ZoneId.of(zone)))

                    @Suppress("TooGenericExceptionCaught")  // I have no idea what goes on in here
                    val instant =
                        try { parser.parse(rawTime).lastOrNull()?.toInstant() } catch (ignored: Exception) { null }

                    instant ?: throw CommandException("Couldn't parse a valid date/time!")

                    if (instant.isBefore(Instant.now())) throw CommandException("Can't add a reminder in the past!")

                    bot.database.trnsctn {
                        val user = bot.database.user(event.user)

                        if (ExposedDatabase.Reminder.find { (Reminders.user eq user.id) and (Reminders.text eq name) }
                                .firstOrNull() != null)
                                    throw CommandException("Can't add two reminders with the same name!")

                        if (ExposedDatabase.Reminder.count(Op.build { Reminders.user eq user.id }) > MAX_REMINDERS)
                            throw CommandException("You have too many reminders!")

                        ExposedDatabase.Reminder.new {
                            text = name
                            time = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                            this.user = user
                            channelId = event.channel.idLong
                        }
                    }

                    // Instant::toString returns an ISO 8601 formatted string
                    event.replyEmbeds(embed("Added a reminder for $instant",
                        description = "<t:${instant.epochSecond}:R>")).complete()
                }
                "remove" -> {
                    val name = event.getOption("name")!!.asString

                    if (name == "all") {
                        val c = bot.database.trnsctn {
                            val user = bot.database.user(event.user)
                            var count = 0
                            for (r in user.reminders) {
                                r.delete()
                                count++
                            }
                            count
                        }
                        event.replyEmbeds(embed("Removed $c reminders")).complete()
                        return@register
                    }
                    val time = bot.database.trnsctn {
                        val user = bot.database.user(event.user)
                        val reminder = ExposedDatabase.Reminder.find {
                            (Reminders.user eq user.id) and (Reminders.text eq name)
                        }.singleOrNull() ?: throw CommandException("Couldn't find a reminder with that name!")
                        reminder.time.toEpochSecond(ZoneOffset.UTC)
                    }
                    event.replyEmbeds(embed("Removed a reminder set for <t:$time>")).complete()
                }
            }
        }

        register(CommandData("restart", "Restart the bot")) { event ->
            // Don't replace with assertAdmin(), this doesn't allow server admins to run
            val isAdmin = bot.database.user(event.user).botAdmin ||
                    bot.config.permaAdmins.contains(event.user.id)

            if (!isAdmin) throw CommandException("You need to be admin to use this command!")

//            bot.audio.gracefulShutdown()  // handled by shutdown hook in Bot.kt
            bot.log.error("${event.user.asTag} (id ${event.user.id}) ran /cleanshutdown")
            event.replyEmbeds(embed("Shutting down...")).complete()
            exitProcess(0)
        }

        register(CommandData("timezone", "Set your timezone (used for reminders)")
            .addOption(OptionType.STRING, "zone", "Your timezone", true)) { event ->
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            val zone = try {
                ZoneId.of(event.getOption("zone")!!.asString).id
            } catch (e: Exception /*no multi-catch...*/) {
                throw CommandException("Couldn't parse a valid time zone!  " +
                        "Make sure you specify it in either region-based (i.e. America/New_York) or UTC+n format")
            }
            bot.database.trnsctn {
                val user = bot.database.user(event.user)
                user.timezone = zone
            }
            event.replyEmbeds(embed("Set your timezone to $zone")).complete()
        }

        register(CommandData("sql", "please don't use this, please I beg you")
            .addOption(OptionType.STRING, "sql", "bad", false)) { event ->
            // do not touch this
            val isAdmin = bot.config.permaAdmins.contains(event.user.id)
            if (!isAdmin) {
                event.reply("no").complete()
                return@register
            }

            val sql = event.getOption("sql")!!.asString

            val output = bot.database.trnsctn {
                exec(sql) { result ->
                    val output = mutableListOf<MutableList<String>>()
                    while (result.next()) {
                        output.add(mutableListOf())
                        for (c in 1..result.metaData.columnCount) {
                            output.last().add(result.getObject(c).toString())
                        }
                    }
                    output
                }
            }
            val count = output?.first()?.size ?: run { event.reply("empty result").complete(); return@register }
            val columnWidths = IntArray(count)

            for (row in output) {
                for ((index, item) in row.withIndex()) {
                    if (item.length > columnWidths[index]) columnWidths[index] = item.length + 1
                }
            }

            val outputTable = StringBuilder()
            coroutineScope {
                launch(Dispatchers.IO) {
                    outputTable.append('+')
                    outputTable.append("-".repeat(columnWidths.sum() + columnWidths.size - 1))
                    outputTable.append('+')
                    outputTable.append('\n')

                    for (row in output) {
                        outputTable.append('|')
                        for ((index, item) in row.withIndex()) {
                            outputTable.append(item.padStart(columnWidths[index], ' '))
                            if (index + 1 in row.indices) outputTable.append('|')
                        }
                        outputTable.append('|')
                        outputTable.append('\n')
                    }
                    outputTable.append('+')
                    outputTable.append("-".repeat(columnWidths.sum() + columnWidths.size - 1))
                    outputTable.append('+')
                }
            }.join()

            val table = outputTable.toString()

            @Suppress("MagicNumber")
            val pages = table.chunked(1900)
                .map { Message("```$it```") }

            @Suppress("SpreadOperator")
            event.replyPaginator(*pages.toTypedArray()).complete()
        }

//        handler.register(
//            CommandBuilder<Message, MessageEmbed>("level").args(UserArg("user", true)).ran { sender, args ->
//                TODO()
//            }
//        )

        registerAudioCommands(bot, this)

        bot.scope.launch(Dispatchers.IO) {
            bot.jda.awaitReady()
            finalizeCommands()
        }
    }
}
