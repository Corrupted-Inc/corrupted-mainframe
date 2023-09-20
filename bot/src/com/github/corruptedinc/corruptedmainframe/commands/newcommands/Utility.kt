package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.utils.CmdCtx
import com.github.corruptedinc.corruptedmainframe.utils.toHumanReadable
import dev.minn.jda.ktx.coroutines.await
import net.dv8tion.jda.api.EmbedBuilder
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

object Utility {
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
