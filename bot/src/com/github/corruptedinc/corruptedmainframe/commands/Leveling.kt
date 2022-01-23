package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.sq
import dev.minn.jda.ktx.await
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.User
import kotlin.math.*

class Leveling(private val bot: Bot) {
    companion object {
        const val POINTS_PER_MESSAGE = 1.0
        private const val ADDEND = 60
        private const val DIVISOR = 10
        private const val SUBTRACT = 3
        private const val STARBOARD_MUL = 5
        private const val STARBOARD_ADDEND = 2
        private const val STARBOARD_POW = 0.25
        private const val STARBOARD_OTHER_ADDEND = 10
    }

    fun pointsToLevel(points: Double) = ln((points + ADDEND) / DIVISOR).sq() - SUBTRACT

    fun levelToPoints(level: Double) = (exp(sqrt(level + SUBTRACT)) * DIVISOR) - ADDEND

    fun starboardPoints(level: Double) = STARBOARD_MUL * (level + STARBOARD_ADDEND).pow(STARBOARD_POW) +
            STARBOARD_OTHER_ADDEND

    fun level(user: User, guild: Guild) = pointsToLevel(bot.database.points(user, guild))

    fun points(user: User, guild: Guild) = bot.database.points(user, guild)

    suspend fun addPoints(user: User, points: Double, channel: TextChannel) {
        if (user.isBot) return

        // I'm aware this is bad, but this is run in a spot where there isn't any catching
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        try {
            val previousLevel = level(user, channel.guild).toInt()
            bot.database.addPoints(user, channel.guild, points)
            val level = level(user, channel.guild).toInt()
            if (level > previousLevel && bot.database.popups(user, channel.guild)) {
                if (!bot.database.trnsctn { bot.database.guild(channel.guild).levelsEnabled }) return
                channel.sendMessageEmbeds(
                    embed(
                        "Level Up",
                        description = "${user.asMention} has leveled up from $previousLevel to $level!  Only ${
                            (levelToPoints(level + 1.0) - levelToPoints(level.toDouble())).roundToInt()
                        } XP to go to level ${level + 1}!\nTo disable these notifications, run /levelnotifs false, " +
                                "or /togglelevels for a guild-wide switch",
                        stripPings = false,
                    )
                ).await()
            }
        } catch (ignored: Exception) {}
    }
}
