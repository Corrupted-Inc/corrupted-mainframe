package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.math.InfixNotationParser
import com.github.corruptedinc.corruptedmainframe.utils.Row
import com.github.corruptedinc.corruptedmainframe.utils.biasedLevenshteinInsensitive
import com.github.corruptedinc.corruptedmainframe.utils.ephemeral
import com.github.corruptedinc.corruptedmainframe.utils.table
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.replyPaginator
import dev.minn.jda.ktx.messages.MessageEditBuilder
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageEditData
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.math.MathContext
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.time.toKotlinDuration

fun registerUtilityCommands(bot: Bot) {
    bot.commands.register(
        slash(
            "tba",
            "Get information from The Blue Alliance about a team."
        )
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
                val fields = mutableListOf<MessageEmbed.Field>()
                if (teamInfo.nickname != null) fields.add(MessageEmbed.Field("Name", teamInfo.name, false))
                if (teamInfo.country != null) fields.add(MessageEmbed.Field("Country", teamInfo.country, true))
                if (teamInfo.city != null) fields.add(MessageEmbed.Field("City", teamInfo.city, true))
                if (teamInfo.school != null) fields.add(MessageEmbed.Field("School", teamInfo.school, true))
                if (teamInfo.rookieYear != null) fields.add(
                    MessageEmbed.Field(
                        "Rookie Year",
                        teamInfo.rookieYear.toString(),
                        true
                    )
                )
                if (teamInfo.website != null) fields.add(MessageEmbed.Field("Website", teamInfo.website, false))

                if (year != null && eventName == null) {
                    val events = bot.theBlueAlliance.events(number.toInt(), year.toInt())
                    fields.add(MessageEmbed.Field("Events", events.joinToString { it.shortName ?: it.name }, false))
                    // TODO: general team performance
                } else if (year != null && eventName != null) {
                    val eventObj = bot.theBlueAlliance.simpleEventByName(eventName, year.toInt())
                        ?: throw CommandException("Couldn't find event '$eventName'!")
                    val teamStatus = bot.theBlueAlliance.teamEventStatus(teamInfo.teamNumber, eventObj.key)
                        ?: throw CommandException("Couldn't find $number's performance at ${eventObj.name}!")
                    val matches = bot.theBlueAlliance.matches(number.toInt(), eventObj.key) ?: emptyList()

                    val embed = Commands.embed(
                        "$number at ${eventObj.name} in $year",
                        content = listOf(
                            MessageEmbed.Field(
                                "Status",
                                teamStatus.overallStatusString
                                    ?.replace("</?b>".toRegex(), "**"), false
                            )
                        )
                    )
                    hook.editOriginal(
                        MessageEditBuilder("```\n" + table(
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
                    ) + "```", listOf(embed)).build()).await()
                    return@launch
                }

                hook.editOriginalEmbeds(
                    Commands.embed(
                        teamInfo.nickname ?: teamInfo.name,
                        url = "https://thebluealliance.com/team/$number",
                        fields,
                        description = number.toString()
                    )
                ).await()
            } catch (e: CommandException) {
                hook.editOriginalEmbeds((Commands.embed("Error", color = Commands.ERROR_COLOR, description = e.message))).await()
            }
        }
    }

    bot.commands.register(
        slash(
            "zebra",
            "Plots the Zebra Motionworks data from a team's autos at an event"
        )
        .addOption(OptionType.INTEGER, "team", "Team number", true)
        .addOption(OptionType.INTEGER, "year", "The year to get data from", true)
        .addOption(OptionType.STRING, "event", "The event to get data from", true, true)
//            .addOption(OptionType.STRING, "match", "The match ")
        .addOption(OptionType.BOOLEAN, "full", "Whether the full match should be drawn as well as the auto", false)
    ) { event ->
        val hook = event.hook
        event.deferReply().await()

        val team = event.getOption("team")!!.asInt
        val year = event.getOption("year")!!.asInt
        val full = event.getOption("full")?.asBoolean == true
        val eventObj = bot.theBlueAlliance.eventByName(event.getOption("event")!!.asString, year) ?: throw CommandException("Event not found!  Try another name for it")

        val drawn = (if (full) bot.paths.renderMatches(team, year, eventObj.key) else bot.paths.renderAutos(team, year, eventObj.key)) ?: throw CommandException("No data found!")

        hook.editOriginalEmbeds(Commands.embed("$team's ${if (full) "matches" else "autos"} at ${eventObj.shortName ?: eventObj.name}"))
            .setFiles(FileUpload.fromData(drawn, "autos.png"))
            .await()
    }

    bot.commands.autocomplete("zebra", "event") { value, event ->
        val year = event.getOption("year")!!.asLong
        val ev = bot.theBlueAlliance.autocompleteEventName(value, year.toInt())

        return@autocomplete ev.map { Command.Choice(it, it) }
    }

    bot.commands.register(
        slash("userinfo", "Gets info on a user")
        .addOption(OptionType.USER, "user", "The user to get the information on", true)) { event ->
        val user = event.getOption("user")!!.asUser
        val member = event.getOption("user")!!.asMember

        val fields = mutableListOf<MessageEmbed.Field>()
        if (member != null) {
            if (member.nickname != null) fields.add(MessageEmbed.Field("Nickname", member.nickname, false))
            fields.add(MessageEmbed.Field("Permissions", member.permissions.joinToString(), false))
            fields.add(MessageEmbed.Field("Server Join", "<t:${member.timeJoined.toInstant().epochSecond}>", false))
        }

        fields.add(MessageEmbed.Field("Account Creation", "<t:${user.timeCreated.toInstant().epochSecond}>", false))

        event.replyEmbeds(Commands.embed(title = user.effectiveName, content = fields)).ephemeral().await()
    }


    bot.commands.register(
        slash("math", "Evaluate arbitrary-precision math")
        .addOption(OptionType.STRING, "expression", "The expression to evaluate", true)
        .addOption(OptionType.INTEGER, "precision", "The precision")
    ) { event ->
        val exp = event.getOption("expression")!!.asString

        val precision = (event.getOption("precision")?.asLong?.toInt() ?: Commands.DEF_CALCULATOR_PRECISION)
            .coerceIn(Commands.MIN_CALCULATOR_PRECISION, Commands.MAX_CALCULATOR_PRECISION)

        // User doesn't need to see an exception
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        try {
            val result = InfixNotationParser(precision).parse(exp)
            val mc = MathContext(precision - 1, RoundingMode.HALF_UP)
            event.replyEmbeds(Commands.embed("Result", description = "$exp = ${result.round(mc).toPlainString()}")).await()
        } catch (e: Exception) {
            throw CommandException("Failed to evaluate '$exp'!")
        }
    }

    bot.commands.register(
        slash("reminders", "Manage reminders").addSubcommands(
        SubcommandData("list", "List your reminders"),
        SubcommandData("add", "Add a reminder")
            .addOption(OptionType.STRING, "name", "The name of the reminder", true)
            .addOption(OptionType.STRING, "time", "The time at which you will be reminded", true),
        SubcommandData("remove", "Remove a reminder")
            .addOption(OptionType.STRING, "name", "The name of the reminder to remove", true, true))
    ) { event ->
        when (event.subcommandName) {
            "list" -> {
                val output = mutableListOf<MessageEmbed.Field>()
                bot.database.trnsctn {
                    val user = event.user.m
                    val reminders = ExposedDatabase.Reminder.find { ExposedDatabase.Reminders.user eq user.id }
                    for (item in reminders) {
                        output.add(
                            MessageEmbed.Field(
                                item.text,
                                "<t:${item.time.toEpochSecond(ZoneOffset.UTC)}:R>",
                                false
                            )
                        )
                    }
                }

                val embeds = output.chunked(Commands.REMINDERS_PER_PAGE)
                    .map { Commands.embed("${event.user.effectiveName}'s Reminders", content = it) }
                if (embeds.isEmpty()) {
                    event.replyEmbeds(Commands.embed("No reminders")).await()
                    return@register
                }
                event.replyPaginator(pages = embeds.toTypedArray(), Duration.of(Commands.BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                    .toKotlinDuration()).ephemeral().await()
            }

            "add" -> {
                val name = event.getOption("name")!!.asString
                val rawTime = event.getOption("time")!!.asString

                if (name.length > ExposedDatabase.VARCHAR_MAX_LENGTH - 1)
                    throw CommandException("Name length must be less than 255 characters!")

                if (name == "all") throw CommandException("Name cannot be 'all'!")

                val zone = bot.database.trnsctn { event.user.m.timezone }

                val parser = PrettyTimeParser(TimeZone.getTimeZone(ZoneId.of(zone)))

                @Suppress("TooGenericExceptionCaught")  // I have no idea what goes on in here
                val instant =
                    try { parser.parse(rawTime).lastOrNull()?.toInstant() } catch (ignored: Exception) { null }

                instant ?: throw CommandException("Couldn't parse a valid date/time!")

                if (instant.isBefore(Instant.now())) throw CommandException("Can't add a reminder in the past!")

                bot.database.trnsctn {
                    val user = event.user.m

                    if (ExposedDatabase.Reminder.find { (ExposedDatabase.Reminders.user eq user.id) and (ExposedDatabase.Reminders.text eq name) }
                            .firstOrNull() != null)
                        throw CommandException("Can't add two reminders with the same name!")

                    if (ExposedDatabase.Reminder.count(Op.build { ExposedDatabase.Reminders.user eq user.id }) > Commands.MAX_REMINDERS)
                        throw CommandException("You have too many reminders!")

                    ExposedDatabase.Reminder.new {
                        text = name
                        time = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
                        this.user = user
                        channelId = event.channel.idLong
                    }
                }

                // Instant::toString returns an ISO 8601 formatted string
                event.replyEmbeds(
                    Commands.embed(
                        "Added a reminder for $instant",
                        description = "<t:${instant.epochSecond}:R>"
                    )
                ).ephemeral().await()
            }
            "remove" -> {
                val name = event.getOption("name")!!.asString

                if (name == "all") {
                    val c = bot.database.trnsctn {
                        val user = event.user.m
                        var count = 0
                        for (r in user.reminders) {
                            r.delete()
                            count++
                        }
                        count
                    }
                    event.replyEmbeds(Commands.embed("Removed $c reminders")).ephemeral().await()
                    return@register
                }
                val time = bot.database.trnsctn {
                    val user = event.user.m
                    val reminder = ExposedDatabase.Reminder.find {
                        (ExposedDatabase.Reminders.user eq user.id) and (ExposedDatabase.Reminders.text eq name)
                    }.singleOrNull() ?: throw CommandException("Couldn't find a reminder with that name!")
                    val t = reminder.time.toEpochSecond(ZoneOffset.UTC)
                    reminder.delete()
                    t
                }
                event.replyEmbeds(Commands.embed("Removed a reminder set for <t:$time>")).ephemeral().await()
            }
        }
    }

    bot.commands.autocomplete("reminders/remove", "name") { value, event ->
        val reminders = bot.database.trnsctn {
            val u = event.user.m
            u.reminders.map { it.text }
        }.sortedBy { biasedLevenshteinInsensitive(it, value) }

        reminders.take(25).map { Command.Choice(it, it) }
    }

    // TODO better parsing
    bot.commands.register(
        slash(
            "timezone",
            "Set your timezone (used for reminders)"
        )
        .addOption(OptionType.STRING, "zone", "Your timezone", true)) { event ->
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val zone = try {
            ZoneId.of(event.getOption("zone")!!.asString).id
        } catch (e: Exception /*no multi-catch...*/) {
            throw CommandException("Couldn't parse a valid time zone!  " +
                    "Make sure you specify it in [tz database](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones) format")
        }
        bot.database.trnsctn {
            val user = event.user.m
            user.timezone = zone
        }
        event.replyEmbeds(Commands.embed("Set your timezone to $zone")).ephemeral().await()
    }
}
