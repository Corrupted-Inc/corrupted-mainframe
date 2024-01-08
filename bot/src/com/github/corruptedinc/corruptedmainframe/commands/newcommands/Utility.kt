package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.corruptedinc.corruptedmainframe.annotations.Autocomplete
import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.P
import com.github.corruptedinc.corruptedmainframe.annotations.Param
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.ThrustCurve
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.math.InfixNotationParser
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.utils.FileUpload
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
}
