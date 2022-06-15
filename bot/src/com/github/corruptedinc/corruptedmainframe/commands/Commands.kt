package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.VARCHAR_MAX_LENGTH
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Reminders
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.math.InfixNotationParser
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.CoroutineEventListener
import dev.minn.jda.ktx.Message
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.replyPaginator
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildChannel
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.Commands.user
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.context.MessageContextInteraction
import net.dv8tion.jda.api.interactions.commands.context.UserContextInteraction
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.awt.Color
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import java.util.*
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
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
            stripPings: Boolean = true,
            footer: String? = null
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
            builder.setFooter(footer)
            return builder.build()
        }

        private const val MIN_CALCULATOR_PRECISION = 2
        private const val MAX_CALCULATOR_PRECISION = 1024
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
    val listeners = mutableListOf<CoroutineEventListener>()

    // TODO custom listener pool that handles exceptions
    // TODO cleaner way than upserting the command every time
    fun register(data: CommandData, lambda: suspend (SlashCommandInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<SlashCommandInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\nCommand: ${it.commandPath} ${it.options.joinToString { c -> "${c.name}: ${c.asString}" }}\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun registerUser(data: CommandData, lambda: suspend (UserContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<UserContextInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun registerMessage(data: CommandData, lambda: suspend (MessageContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<MessageContextInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun autocomplete(commandPath: String, param: String, lambda: (value: String, event: CommandAutoCompleteInteractionEvent) -> List<Command.Choice>) {
        bot.jda.listener<CommandAutoCompleteInteractionEvent> { event ->
            if (event.commandPath != commandPath) return@listener
            val op = event.focusedOption
            if (op.name != param) return@listener

            event.replyChoices(lambda(op.value, event)).await()
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

    fun assertAdmin(event: CommandInteraction) {
        assertPermissions(event, Permission.ADMINISTRATOR)
    }

    fun assertPermissions(event: CommandInteraction, vararg permissions: Permission,
                          channel: GuildChannel = event.guildChannel) {
        if (bot.database.trnsctn { bot.database.user(event.user).botAdmin }) return
        val member = event.member ?: throw CommandException("Missing permissions!")
        if (!member.getPermissions(channel).toSet().containsAll(permissions.toList())) {
            throw CommandException("Missing permissions!")
        }
    }

    @OptIn(ExperimentalTime::class)
    @Suppress("ComplexMethod", "LongMethod", "ThrowsCount")
    fun registerAll() {
        register(slash("invite", "Invite Link")) {
            it.replyEmbeds(embed("Invite Link",
                description = "[Admin invite](${adminInvite(bot.jda.selfUser.id)})\n" +
                              "[Basic permissions](${basicInvite(bot.jda.selfUser.id)})"
            )).await()
        }
        
        register(slash("slots", "Play the slots")) { event ->
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

        register(slash("math", "Evaluate arbitrary-precision math")
            .addOption(OptionType.STRING, "expression", "The expression to evaluate", true)
            .addOption(OptionType.INTEGER, "precision", "The precision in digits")
        ) { event ->
            val exp = event.getOption("expression")!!.asString

            val precision = (event.getOption("precision")?.asLong?.toInt() ?: DEF_CALCULATOR_PRECISION)
                .coerceIn(MIN_CALCULATOR_PRECISION, MAX_CALCULATOR_PRECISION)

            // User doesn't need to see an exception
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            try {
                val result = InfixNotationParser(precision).parse(exp)
                event.replyEmbeds(embed("Result", description = "$exp = ${result.toPlainString()}")).await()
            } catch (e: Exception) {
                throw CommandException("Failed to evaluate '$exp'!")
            }
        }

        register(slash("reminders", "Manage reminders").addSubcommands(
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
                        .toKotlinDuration()).ephemeral().await()
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
                        description = "<t:${instant.epochSecond}:R>")).ephemeral().await()
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
                        event.replyEmbeds(embed("Removed $c reminders")).ephemeral().await()
                        return@register
                    }
                    val time = bot.database.trnsctn {
                        val user = bot.database.user(event.user)
                        val reminder = ExposedDatabase.Reminder.find {
                            (Reminders.user eq user.id) and (Reminders.text eq name)
                        }.singleOrNull() ?: throw CommandException("Couldn't find a reminder with that name!")
                        reminder.time.toEpochSecond(ZoneOffset.UTC)
                    }
                    event.replyEmbeds(embed("Removed a reminder set for <t:$time>")).ephemeral().await()
                }
            }
        }

        register(slash("fight", "Fight another user")
            .addOption(OptionType.USER, "user", "The user to fight", true)) { event ->
            val user = event.getOption("user")!!.asMember ?: throw CommandException("You can't fight someone in a different server!")
            val attacker = event.member!!

            if (user == attacker) throw CommandException("You can't fight yourself!")

            val guild = event.guild!!
//            if (bot.leveling.level(attacker.user, guild) > bot.leveling.level(user.user, guild) + 5.0 && user.idLong != bot.jda.selfUser.idLong)
//                throw CommandException("Can't fight someone more than 5 levels lower than you!")

            bot.database.trnsctn {
                val u = bot.database.user(attacker.user)
                val g = bot.database.guild(guild)
                val pts = ExposedDatabase.Point.find { (ExposedDatabase.Points.user eq u.id) and (ExposedDatabase.Points.guild eq g.id) }.first()
                val cooldown = pts.fightCooldown.plus(g.fightCooldown)
                val now = Instant.now()
                if (cooldown.isAfter(now)) {
                    throw CommandException("Can't fight again until <t:${cooldown.epochSecond}>!")
                }

                pts.fightCooldown = now
            }
            bot.fights.sendFight(event, attacker.user, user.user, guild)
        }

        // TODO better parsing
        register(slash("timezone", "Set your timezone (used for reminders)")
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
            event.replyEmbeds(embed("Set your timezone to $zone")).ephemeral().await()
        }

        register(slash("sql", "not for you")
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
            val count = output?.firstOrNull()?.size ?: run { event.reply("empty result").ephemeral().await(); return@register }
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
                .toKotlinDuration()).ephemeral().await()
        }

        register(slash("tba", "Get information from The Blue Alliance about a team.")
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
                        val events = bot.theBlueAlliance.events(number.toInt(), year.toInt())
                        fields.add(Field("Events", events.joinToString { it.shortName ?: it.name }, false))
                        // TODO: general team performance
                    } else if (year != null && eventName != null) {
                        val eventObj = bot.theBlueAlliance.simpleEventByName(eventName, year.toInt())
                            ?: throw CommandException("Couldn't find event '$eventName'!")
                        val teamStatus = bot.theBlueAlliance.teamEventStatus(teamInfo.teamNumber, eventObj.key)
                            ?: throw CommandException("Couldn't find $number's performance at ${eventObj.name}!")
                        val matches = bot.theBlueAlliance.matches(number.toInt(), eventObj.key) ?: emptyList()

                        val embed = embed(
                            "$number at ${eventObj.name} in $year",
                            content = listOf(
                                Field(
                                    "Status",
                                    teamStatus.overallStatusString
                                        ?.replace("</?b>".toRegex(), "**")
                                    , false)
                            )
                        )
                        hook.editOriginal(Message("```\n" + table(
                            arrayOf(Row("R1", "R2", "R3", "B1", "B2", "B3", "Red", "Blue"))
                                    + matches.sortedBy { it.matchNumber }
                                .map {
                                    it.alliances!!
                                    val red = it.alliances.red.teamKeys.map { item ->
                                        item.removePrefix("frc").run { if (this == number.toString()) "$this*" else "$this " }
                                    }
                                    val blue = it.alliances.blue.teamKeys.map { item ->
                                        item.removePrefix("frc").run { if (this == number.toString()) "$this*" else "$this " }
                                    }
                                    val redWon = it.winningAlliance == "red"
                                    val blueWon = it.winningAlliance == "blue"
                                    Row(red[0], red[1], red[2], blue[0], blue[1], blue[2],
                                        it.alliances.red.score.run { if (redWon) "$this*" else "$this " },
                                        it.alliances.blue.score.run { if (blueWon) "$this*" else "$this " },
                                    )
                            }
                        ) + "```", embed)).await()
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

        register(slash("zebra", "Plots the Zebra Motionworks data from a team's autos at an event")
            .addOption(OptionType.INTEGER, "team", "Team number", true)
            .addOption(OptionType.INTEGER, "year", "The year to get data from", true)
            .addOption(OptionType.STRING, "event", "The event to get data from", true, true)
//            .addOption(OptionType.STRING, "match", "The match ")
        ) { event ->
            val hook = event.hook
            event.deferReply().await()

            val team = event.getOption("team")!!.asInt
            val year = event.getOption("year")!!.asInt
            val eventObj = bot.theBlueAlliance.eventByName(event.getOption("event")!!.asString, year) ?: throw CommandException("Event not found!  Try another name for it")

            val drawn = bot.paths.renderAutos(team, year, eventObj.key) ?: throw CommandException("No data found!")

            hook.editOriginalEmbeds(embed("$team's Autos at ${eventObj.shortName ?: eventObj.name}"))
                .addFile(drawn, "autos.png")
                .await()
        }

        autocomplete("zebra", "event") { value, event ->
            val year = event.getOption("year")!!.asLong
            val ev = bot.theBlueAlliance.autocompleteEventName(value, year.toInt())

            return@autocomplete ev.map { Command.Choice(it, it) }
        }

        register(slash("userinfo", "Gets info on a user")
            .addOption(OptionType.USER, "user", "The user to get the information on", true)) { event ->
            val user = event.getOption("user")!!.asUser
            val member = event.getOption("user")!!.asMember

            val fields = mutableListOf<Field>()
            if (member != null) {
                if (member.nickname != null) fields.add(Field("Nickname", member.nickname, false))
                fields.add(Field("Permissions", member.permissions.joinToString(), false))
                fields.add(Field("Server Join", "<t:${member.timeJoined.toInstant().epochSecond}>", false))
            }

            fields.add(Field("Account Creation", "<t:${user.timeCreated.toInstant().epochSecond}>", false))

            event.replyEmbeds(embed(title = user.asTag, content = fields)).ephemeral().await()
        }

        @Language("sql") val rankStatement =
                "UPDATE points SET rank = RankTable.rank " +
                        "FROM (SELECT id, DENSE_RANK() OVER(ORDER BY points DESC, id) AS rank FROM points WHERE guild = ?) " +
                        "AS RankTable WHERE RankTable.id = points.id"

        register(slash("leaderboard", "Shows the server level leaderboard.")) { event ->
            // update rank column
            bot.database.trnsctn {
                val g = bot.database.guild(event.guild!!)
                // yes, I'm aware this is awful, but because it must be an integer and isn't user provided it's safe
                // if it was user provided I would have put more effort into a prepared statement
                exec(rankStatement.replace("?", g.id.value.toString()))
            }

            val guildid = event.guild!!.idLong

            fun area(start: Long, end: Long): MessageEmbed {
                val idsRanksPoints = bot.database.trnsctn {
                    val g = bot.database.guild(guildid).id
                    // TODO: add secondary sort column somehow
                    val items = ExposedDatabase.Point.find { (ExposedDatabase.Points.guild eq g) and (ExposedDatabase.Points.rank greaterEq start) and (ExposedDatabase.Points.rank less end) }.limit(30)
                        .sortedByDescending { it.points }

                    items.map { Triple(it.user.discordId, it.rank, it.points.roundToLong()) }
                }

                val g = bot.jda.getGuildById(guildid)!!

                val rankCol = Col(Align.RIGHT, *(arrayOf("Rank") + idsRanksPoints.map { it.second.toString() }))
                val nameCol = Col(Align.LEFT, *(arrayOf("Name") + idsRanksPoints.map { g.getMemberById(it.first)?.effectiveName ?: "bug" }))
                val pntsCol = Col(Align.LEFT, *(arrayOf("Points") + idsRanksPoints.map { it.third.toString() }))

                return embed("Leaderboard $start - $end", description = "```" + table(rankCol, nameCol, pntsCol) + "```")
            }

            val perMessage = 15
            val size = event.guild!!.memberCount / perMessage  // todo: dejankify

            event.replyLambdaPaginator(size.toLong()) { v ->
                area(v * perMessage, (v + 1) * perMessage)
            }
        }

        registerUser(user("fight")) { event ->
            val user = event.target
            val attacker = event.member!!

            if (user == attacker) throw CommandException("You can't fight yourself!")

            val guild = event.guild!!

            bot.database.trnsctn {
                val u = bot.database.user(attacker.user)
                val g = bot.database.guild(guild)
                val pts = ExposedDatabase.Point.find { (ExposedDatabase.Points.user eq u.id) and (ExposedDatabase.Points.guild eq g.id) }.first()
                val cooldown = pts.fightCooldown.plus(g.fightCooldown)
                val now = Instant.now()
                if (cooldown.isAfter(now)) {
                    throw CommandException("Can't fight again until <t:${cooldown.epochSecond}>!")
                }

                pts.fightCooldown = now
            }
            bot.fights.sendFight(event, attacker.user, user, guild)
        }

        register(
            slash("autoreact", "Automatically react to a user's message")
                .addSubcommands(SubcommandData("list", "Lists the autoreactions"))
                .addSubcommands(SubcommandData("add", "Add an autoreaction").addOption(OptionType.USER, "user", "The user", true).addOption(OptionType.STRING, "reaction", "The reaction", true))
                .addSubcommands(SubcommandData("remove", "Remove an autoreaction").addOption(OptionType.USER, "user", "The user", true).addOption(OptionType.STRING, "reaction", "The reaction", true))
        ) { event ->
            val guild = event.guild!!
            when (event.subcommandName) {
                "list" -> {
                    val autoreactions = bot.database.trnsctn {
                        val g = bot.database.guild(guild)
                        val selection = ExposedDatabase.Autoreaction.find { ExposedDatabase.Autoreactions.guild eq g.id }
                        selection.map { it.user.discordId to it.reaction }
                    }.map { Row(guild.getMemberById(it.first)?.effectiveName ?: "null", it.second) }

                    val content = table((listOf(Row("Name", "Reaction")) + autoreactions).toTypedArray())
                    val embeds = content.chunked(1000).map { embed("Autoreactions", description = it) }.toTypedArray()

                    event.replyPaginator(pages = embeds, expireAfter = 5L.minutes)
                }
                "add" -> {
//                    assertAdmin(event)
                    val user = event.getOption("user")!!.asMember!!
                    val reaction = event.getOption("reaction")!!.asString.dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }
                    val r = event.guild!!.getEmotesByName(reaction, true).singleOrNull()?.asMention
                        ?: reaction.filter { it.isDigit() }.toLongOrNull()?.let { event.guild!!.getEmoteById(it)?.asMention }
                        ?: if (Emotes.isValid(reaction)) reaction else null
//                        ?: Emotes.builtinEmojiByNameFuzzy(reaction).first()
                    r ?: throw CommandException("No emoji found!")
                    bot.database.trnsctn {
                        val u = bot.database.user(user.user)
                        val g = bot.database.guild(guild)

                        if (g.starboardReaction == r) throw CommandException("nice try")
                        ExposedDatabase.Autoreaction.new {
                            this.user = u
                            this.guild = g
                            this.reaction = r
                        }
                    }
                    event.replyEmbeds(embed("Added Autoreaction", description = "<@${user.idLong}>'s messages will be reacted to with $r", stripPings = false)).await()
                }
                "remove" -> {
//                    assertAdmin(event)
                    val user = event.getOption("user")!!.asMember!!
                    val reaction = event.getOption("reaction")!!.asString.dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }
                    val r = event.guild!!.getEmotesByName(reaction, true).singleOrNull()?.asMention
                        ?: reaction.filter { it.isDigit() }.toLongOrNull()?.let { event.guild!!.getEmoteById(it)?.asMention }
                        ?: if (Emotes.isValid(reaction)) reaction else null
//                        ?: Emotes.builtinEmojiByNameFuzzy(reaction).first()
                    r ?: throw CommandException("No emoji found!")
                    val res = bot.database.trnsctn {
                        val u = bot.database.user(user.user)
                        val g = bot.database.guild(guild)
                        ExposedDatabase.Autoreaction.find { (ExposedDatabase.Autoreactions.guild eq g.id) and (ExposedDatabase.Autoreactions.user eq u.id) and (ExposedDatabase.Autoreactions.reaction eq r) }
                            .singleOrNull()?.delete()?.run { true } ?: false
                    }
                    if (!res) throw CommandException("No existing reaction")
                    event.replyEmbeds(embed("Removed Autoreaction", description = "<@${user.idLong}>'s messages will no longer be reacted to with $r", stripPings = false)).await()
                }
            }
        }

        registerAudioCommands(bot, this)
        registerCommands(bot)
        registerBotCommands(bot)
        bot.leveling.registerCommands()

        bot.scope.launch(Dispatchers.IO) {
            bot.jda.awaitReady()
            finalizeCommands()
        }
    }
}
