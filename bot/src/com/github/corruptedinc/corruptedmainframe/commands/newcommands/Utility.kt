package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.corruptedinc.corruptedmainframe.annotations.Autocomplete
import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.Param
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.ThrustCurve
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.utils.FileUpload
import java.time.Duration
import java.time.Instant
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
}
