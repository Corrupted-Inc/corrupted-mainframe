package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.sq
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Leveling(private val bot: Bot) {
    companion object {
        const val POINTS_PER_MESSAGE = 5.0
    }

    fun pointsToLevel(points: Double) = (sqrt(points + 50) - sqrt(50.0)) / 3

    fun levelToPoints(level: Double) = ((level * 3) + sqrt(50.0)).sq() - 50

    fun level(user: User, guild: Guild) = pointsToLevel(bot.database.points(user, guild))

    suspend fun addPoints(user: User, points: Double, channel: TextChannel) {
        if (user.isBot) return
        try {
            val previousLevel = level(user, channel.guild).toInt()
            bot.database.addPoints(user, channel.guild, points)
            val level = level(user, channel.guild).toInt()
            if (level > previousLevel && bot.database.popups(user, channel.guild)) {
                channel.sendMessageEmbeds(
                    embed(
                        "Level Up",
                        description = "${user.asMention} has leveled up from $previousLevel to $level!  Only ${
                            (levelToPoints(level + 1.0) - levelToPoints(level.toDouble())).roundToInt()
                        } XP to go to level ${level + 1}!", stripPings = false
                    )
                ).complete()
            }
        } catch (e: Exception) {}
    }
}
