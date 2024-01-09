package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.corruptedinc.corruptedmainframe.annotations.*
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.ThrustCurve
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.math.InfixNotationParser
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.replyPaginator
import dev.minn.jda.ktx.messages.MessageEditBuilder
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.utils.FileUpload
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.math.MathContext
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.time.toKotlinDuration

object Utility {
    val tc = ThrustCurve()

    @Command("thrustcurve", "Get model rocket performance information")
    suspend inline fun CmdCtx.thrustcurve(@Param("motor", "The name of the motor") motor: String) {
        val found = tc.motors.find { it.name == motor } ?: throw CommandException("Motor not found!")

        val xValues = found.data.map { it[0] }.toDoubleArray()
        val yValues = found.data.map { it[1] }.toDoubleArray()

        val graph = Graph(found.name, "time (s)", "thrust (N)", xValues, listOf(Series(yValues, "", RGB(30U, 30U, 255U))))
        val rendered = graph.render(bot)!!

        val fields = "diameter = ${found.diameterMM}mm\nlength = ${found.lengthMM}mm\ndelay options = ${if (found.delays.isEmpty()) { "plugged" } else { found.delays.joinToString() } }\nmass = ${found.totalMass}kg\npropellant mass = ${found.propMass}kg\navg thrust = ${found.avgThrust}N\npeak thrust = ${found.peakThrust}N\nburn time = ${found.burnTime} s"

//        event.reply("## ${found.manufacturer.replaceFirstChar { it.uppercaseChar() }} ${found.name}\n$fields")

        event.replyEmbeds(embed(
            "${found.manufacturer.replaceFirstChar { it.uppercaseChar() }} ${found.name}",
            description = fields
        )).addFiles(FileUpload.fromData(rendered, "img.png")).await()
    }

    @Autocomplete("/thrustcurve/motor")
    suspend inline fun AutocompleteContext.thrustcurveNameAutocomplete(): List<Choice> {
        // possible forms:
        //  H100 - find motors with the proper name H100 or similar
        //  Aerotech H100 - find motors made by aerotech or quest with proper name like H100
        //  H100W - find motors with name like H100W
        // for now, just work on form #3

        val desired = event.focusedOption.value
        val sorted = tc.motors.sortedBy { biasedLevenshteinInsensitive(it.name, desired) }

        return sorted.take(5).map { Choice("${it.manufacturer} ${it.name}", it.name) }
    }

    @Command("zebra", "Plots the Zebra Motionworks data from a team's autos at an event")
    suspend inline fun CmdCtx.zebra(@P("team", "The FRC team number to get data for") team: Int, @P("year", "The year to get data for") year: Int, @P("event", "The event to get data for") eventName: String, @P("full", "If true, plots the whole matches instead of just autos") full: Boolean?) {
        val hook = event.hook
        event.deferReply().await()

        val eventObj = bot.theBlueAlliance.eventByName(eventName, year) ?: throw CommandException("Event not found!  Try another name for it")

        val drawn = (if (full == true) bot.paths.renderMatches(team, year, eventObj.key) else bot.paths.renderAutos(team, year, eventObj.key)) ?: throw CommandException("No data found!")

        hook.editOriginalEmbeds(Commands.embed("$team's ${if (full == true) "matches" else "autos"} at ${eventObj.shortName ?: eventObj.name}"))
            .setFiles(FileUpload.fromData(drawn, "autos.png"))
            .await()
    }

    @Autocomplete("/zebra/event")
    suspend inline fun AtcmpCtx.zebraEventAutocomplete(): List<Choice> {
        val year = event.getOption("year")!!.asLong
        val ev = bot.theBlueAlliance.autocompleteEventName(event.focusedOption.value, year.toInt())

        return ev.map { Choice(it, it) }
    }

    @Command("userinfo", "Gets info on a user", global = true)
    suspend inline fun CmdCtx.userinfo(@P("user", "The user to get info on") user: User) {
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


    @Command("math", "Evaluate arbitrary-precision math", global = true)
    suspend inline fun CmdCtx.math(@P("expression", "The expression to evaluate") exp: String, @P("precision", "The precision") prec: Int?) {
        val precision = (prec ?: Commands.DEF_CALCULATOR_PRECISION)
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

    @Command("stats", "Get bot statistics")
    suspend inline fun CmdCtx.stats() {
        // TODO new servers, performance info

        val builder = EmbedBuilder()
        builder.setTitle("Statistics and Info")
        builder.setThumbnail(event.guild?.iconUrl)
        val id = bot.jda.selfUser.id
        val ping = bot.jda.restPing.await()
        val guild = event.guild
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
            """.trimIndent() + (guild?.run { "\n" + """
                **Guild Info**
                Owner: ${event.guild?.owner?.asMention}
                Creation Date: <t:${event.guild?.timeCreated?.toEpochSecond()}> UTC
                Members: ${event.guild?.memberCount}
                Boost Level: ${event.guild?.boostTier?.name?.lowercase()?.replace('_', ' ')}
            """.trimIndent() } ?: "")
            )
        }
        event.replyEmbeds(builder.build()).await()
    }

    @Command("invite", "Get an invite link to the bot")
    suspend inline fun CmdCtx.invite() {
        event.replyEmbeds(embed("Invite Link", description = "Admin invite: ${Commands.adminInvite(bot.jda.selfUser.id)}\nBasic permissions: ${Commands.basicInvite(bot.jda.selfUser.id)}")).ephemeral().await()
    }

    @Command("timezone", "Set your timezone (used for reminders)")
    suspend inline fun CmdCtx.timezone(@P("timezone", "Your timezone") name: String) {
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        val zone = try {
            ZoneId.of(name).id
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

    @ParentCommand("tba", "Get information from The Blue Alliance")
    object TBACommands {
        @Command("team", "Gets general info on a team")
        suspend inline fun CmdCtx.team(@P("number", "The team number") number: Int, @P("year", "The year to get data for") year: Int?, @P("event", "The event to get data for") eventName: String?) {
            event.deferReply().await()
            bot.scope.launch {
                val hook = event.hook
                try {
                    val teamInfo = bot.theBlueAlliance.teamInfo(number.toInt())
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
    }

    @ParentCommand("reminders", "Manage reminders")
    object ReminderCommands {
        @Command("list", "Lists your reminders")
        suspend inline fun CmdCtx.list() {
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
                return
            }
            event.replyPaginator(pages = embeds.toTypedArray(), Duration.of(Commands.BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                .toKotlinDuration()).ephemeral().await()
        }

        @Command("add", "Add a reminder")
        suspend inline fun CmdCtx.addReminder(@P("name", "The title of the reminder") name: String, @P("time", "The time at which you will be reminded") rawTime: String) {
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

        @Command("remove", "Remove a reminder")
        suspend inline fun CmdCtx.removeReminder(@P("name", "The title of the reminder") name: String) {
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
                return
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

        @Autocomplete("/reminders/remove/name")
        suspend inline fun AtcmpCtx.reminderName(): List<Choice> {
            val reminders = bot.database.trnsctn {
                val u = event.user.m
                u.reminders.map { it.text }
            }.sortedBy { biasedLevenshteinInsensitive(it, event.focusedOption.value) }

            return reminders.take(25).map { Choice(it, it) }
        }
    }
}
