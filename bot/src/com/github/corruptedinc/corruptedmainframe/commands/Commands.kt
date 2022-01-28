package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.calculator.Calculator
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.VARCHAR_MAX_LENGTH
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Reminders
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.Message
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.replyPaginator
import dev.minn.jda.ktx.listener
import dev.minn.jda.ktx.messages.editMessage_
import kotlinx.coroutines.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildChannel
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.TextChannel
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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.toKotlinDuration

class Commands(val bot: Bot) {

    companion object {
        fun String.stripPings() = this.replace("@", "\\@")

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

        const val BUTTON_TIMEOUT = 120_000L

        private val ERROR_COLOR = Color(235, 70, 70)

        private const val REMINDERS_PER_PAGE = 15
        private const val MAX_REMINDERS = 128

        fun adminInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId&permissions=8&scope=applications.commands%20bot"

        fun basicInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId" +
                    "&permissions=271830080&scope=applications.commands%20bot"
    }

    internal val newCommands = mutableListOf<CommandData>()

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
                        it.replyEmbeds(embed("Error", description = e.message, color = ERROR_COLOR)).await()
                    } catch (e: Exception) {
                        it.replyEmbeds(embed("Internal Error", color = ERROR_COLOR)).await()
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
            }
        }
    }

    @Suppress("SwallowedException")
    suspend fun finalizeCommands() {
        bot.log.info("Registering ${newCommands.size} commands...")
        bot.jda.guilds.map {
            bot.scope.launch {
                try {
                    it.updateCommands().addCommands(newCommands).await()
                } catch (e: ErrorResponseException) {
                    bot.log.error("Failed to register commands in ${it.name}")
                }
            }
        }.joinAll()
        bot.log.info("Done")

        bot.jda.listener<GuildJoinEvent> { event ->
            event.guild.updateCommands().addCommands(newCommands).await()
        }
    }

    fun assertAdmin(event: SlashCommandEvent) {
//        if (!(bot.database.user(event.user).botAdmin || event.member.admin))
//            throw CommandException("You need to be an administrator to run this command!")
        assertPermissions(event, Permission.ADMINISTRATOR)
    }

    fun assertPermissions(event: SlashCommandEvent, vararg permissions: Permission,
                          channel: GuildChannel = event.guildChannel) {
        if (bot.database.trnsctn { bot.database.user(event.user).botAdmin }) return
        val member = event.member ?: throw CommandException("Missing permissions!")
        if (!member.getPermissions(channel).toSet().containsAll(permissions.toList())) {
            throw CommandException("Missing permissions!")
        }
    }

    @Suppress("ComplexMethod", "LongMethod", "ThrowsCount")
    fun registerAll() {
        register(CommandData("invite", "Invite Link")) {
            it.replyEmbeds(embed("Invite Link",
                description = "[Admin invite](${adminInvite(bot.jda.selfUser.id)})\n" +
                              "[Basic permissions](${basicInvite(bot.jda.selfUser.id)})"
            )).await()
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
            event.replyEmbeds(embed("${event.user.name} is playing...", description = out)).await()
        }

        // built into discord now, TODO remove completely (including tables)
//        register(CommandData("mute", "Mute a user")
//            .addOption(OptionType.USER, "user", "The user to mute", true)
//            .addOption(OptionType.INTEGER, "seconds", "The number of seconds to mute the user for", true)) { event ->
//
//            assertAdmin(event)
//
//            val user = event.getOption("user")?.asUser
//            val time = event.getOption("seconds")?.asLong?.coerceAtLeast(0) ?: 0
//            val member = user?.let { event.guild?.getMember(it) }
//                ?: throw CommandException("Must be a member of this server")
//
//            val end = Instant.now().plusSeconds(time)
//            bot.database.moderationDB.addMute(user, member.roles, end, event.guild!!)
//
//            member.guild.modifyMemberRoles(member, listOf()).await()
//
//            event.replyEmbeds(
//                embed("Muted ${user.asTag} for ${Duration.ofSeconds(time).toHumanReadable()}")).await()
//        }
//
//        register(CommandData("unmute", "Unmute a user")
//            .addOption(OptionType.USER, "user", "The user to unmute")) { event ->
//
//            assertAdmin(event)
//            val user = event.getOption("user")?.asUser!!
//
//            val mute = bot.database.moderationDB.findMute(user, event.guild
//                ?: throw CommandException("This command must be run in a guild!")
//            ) ?: throw CommandException("${user.asMention} isn't muted!")
//
//            event.guild!!.modifyMemberRoles(
//                event.guild!!.getMember(user)!!,
//                bot.database.moderationDB.roleIds(mute).map { event.guild!!.getRoleById(it) }).await()
//
//            event.replyEmbeds(embed("Unmuted ${user.asTag}")).await()
//        }

        // TODO replace with a better backend
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
                event.replyEmbeds(embed("Result", description = "$exp = ${result.toPlainString()}")).await()
            } catch (e: Exception) {
                throw CommandException("Failed to evaluate '$exp'!")
            }
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
                        event.replyEmbeds(embed("No reminders")).await()
                        return@register
                    }
                    event.replyPaginator(pages = embeds.toTypedArray(), Duration.of(BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                        .toKotlinDuration()).await()
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
                        description = "<t:${instant.epochSecond}:R>")).await()
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
                        event.replyEmbeds(embed("Removed $c reminders")).await()
                        return@register
                    }
                    val time = bot.database.trnsctn {
                        val user = bot.database.user(event.user)
                        val reminder = ExposedDatabase.Reminder.find {
                            (Reminders.user eq user.id) and (Reminders.text eq name)
                        }.singleOrNull() ?: throw CommandException("Couldn't find a reminder with that name!")
                        reminder.time.toEpochSecond(ZoneOffset.UTC)
                    }
                    event.replyEmbeds(embed("Removed a reminder set for <t:$time>")).await()
                }
            }
        }

        // TODO better parsing
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
            event.replyEmbeds(embed("Set your timezone to $zone")).await()
        }

        register(CommandData("sql", "not for you")
            .addOption(OptionType.STRING, "sql", "bad", true)
            .addOption(OptionType.BOOLEAN, "commit", "keep changes?", false)
        ) { event ->
            // do not touch this
            val isAdmin = bot.config.permaAdmins.contains(event.user.id)
            if (!isAdmin) {
                event.reply("no").await()
                return@register
            }

            val sql = event.getOption("sql")!!.asString
            val rollback = event.getOption("commit")?.asBoolean != true

            val output = bot.database.trnsctn {
                exec(sql) { result ->
                    val output = mutableListOf<MutableList<String>>()
                    while (result.next()) {
                        output.add(mutableListOf())
                        for (c in 1..result.metaData.columnCount) {
                            output.last().add(result.getObject(c).toString())
                        }
                    }
                    if (rollback) {
                        rollback()
                    }
                    output
                }
            }
            val count = output?.firstOrNull()?.size ?: run { event.reply("empty result").await(); return@register }
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
                .map { embed("a", description = "```\n$it```") }

            @Suppress("SpreadOperator")
            event.replyPaginator(pages = pages.toTypedArray(), Duration.of(BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                .toKotlinDuration()).await()
        }

        register(CommandData("tba", "Get information from The Blue Alliance about a team.")
            .addOption(OptionType.INTEGER, "number", "The team number", true)
            .addOption(OptionType.INTEGER, "year", "The year", false)
            .addOption(OptionType.STRING, "event", "The event name", false)
        ) { event ->
            event.deferReply().await()
            bot.scope.launch {
                val hook = event.hook
                try {
                    val number = event.getOption("number")!!.asLong
                    val teamInfo = bot.theBlueAlliance.teamInfo(number.toInt())
                    val year = event.getOption("year")?.asLong
                    val eventName = event.getOption("event")?.asString
                    if (eventName != null && year == null)
                        throw CommandException("Year must be specified to get event info!")
                    teamInfo ?: throw CommandException("Failed to get info on team '$number'!")
                    val fields = mutableListOf<Field>()
                    if (teamInfo.nickname != null) fields.add(Field("Name", teamInfo.name, false))
                    if (teamInfo.country != null) fields.add(Field("Country", teamInfo.country, true))
                    if (teamInfo.city != null) fields.add(Field("City", teamInfo.city, true))
                    if (teamInfo.school != null) fields.add(Field("School", teamInfo.school, true))
                    if (teamInfo.rookieYear != null) fields.add(
                        Field(
                            "Rookie Year",
                            teamInfo.rookieYear.toString(),
                            true
                        )
                    )
                    if (teamInfo.website != null) fields.add(Field("Website", teamInfo.website, false))

                    if (year != null && eventName == null) {
                        // TODO: general team performance
                    } else if (year != null && eventName != null) {
                        // 1d7ce
                        val eventObj = bot.theBlueAlliance.simpleEventByName(eventName, year.toInt())
                            ?: throw CommandException("Couldn't find event '$eventName'!")
                        val teamStatus = bot.theBlueAlliance.teamEventStatus(teamInfo.teamNumber, eventObj.key)
                            ?: throw CommandException("Couldn't find $number's performance at ${eventObj.name}!")
                        val matches = bot.theBlueAlliance.matches(number.toInt(), eventObj.key) ?: emptyList()

                        hook.editOriginalEmbeds(
                            embed(
                                "$number at ${eventObj.name} in $year",
                                content = listOf(
                                    Field(
                                        "Status",
                                        teamStatus.overallStatusString
                                            ?.replace("</?b>".toRegex(), "**")
                                        , false)
                                ),
                                description = "```" + table(
                                    arrayOf(
                                        Row("R1", "R2", "R3", "B1", "B2", "B3", "Red", "Blue")
                                    ) + matches.map {
                                        val red = it.alliances!!.red.teamKeys.map { item -> item.removePrefix("frc") }
                                        val blue = it.alliances.blue.teamKeys.map { item -> item.removePrefix("frc") }
                                        val redWon = it.winningAlliance == "red"
                                        val blueWon = it.winningAlliance == "blue"
                                        Row(red[0], red[1], red[2], blue[0], blue[1], blue[2],
                                            it.alliances.red.score.run { if (redWon) "*$this*" else toString() },
                                            it.alliances.blue.score.run { if (blueWon) "*$this*" else toString() },
                                        )
                                    }
                                ) + "```"
                            )
                        ).await()
                        return@launch
                    }

                    hook.editOriginalEmbeds(
                        embed(
                            teamInfo.nickname ?: teamInfo.name,
                            url = "https://thebluealliance.com/team/$number",
                            fields,
                            description = number.toString()
                        )
                    ).await()
                } catch (e: CommandException) {
                    hook.editOriginalEmbeds((embed("Error", color = ERROR_COLOR, description = e.message))).await()
                }
            }
        }

        registerAudioCommands(bot, this)

        bot.scope.launch(Dispatchers.IO) {
            bot.jda.awaitReady()
            finalizeCommands()
        }
    }
}
