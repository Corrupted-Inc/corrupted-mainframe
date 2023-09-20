package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.FRCDB.CachedZebra
import com.github.corruptedinc.corruptedmainframe.core.db.FRCDB.CachedZebras
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.RGB
import org.jetbrains.exposed.sql.and

class RobotPaths(private val bot: Bot) {

    private fun fetchPath(team: Int, year: Int, match: TheBlueAlliance.Match, type: Type): String? {
        val matchZebra = bot.theBlueAlliance.zebra(match) ?: return null
        val teamZebra = matchZebra.alliances.run { red + blue }.singleOrNull { it.teamKey == "frc$team" } ?: return null

        val autoIndex = matchZebra.times.indexOfLast { it < 15.0 }  // TODO: set by year
        val autoX = teamZebra.xs.take(autoIndex).filterNotNull().toDoubleArray()
        val autoY = teamZebra.ys.take(autoIndex).filterNotNull().toDoubleArray()

        val autoPathData = bot.pathDrawer.robotPathData(autoX, autoY)

        val fullMatchPathData = bot.pathDrawer.robotPathData(teamZebra.xs.filterNotNull().toDoubleArray(), teamZebra.ys.filterNotNull().toDoubleArray())

        bot.database.trnsctn {
            CachedZebra.new {
                this.autoPath = autoPathData.toString()
                this.fullPath = fullMatchPathData.toString()
                this.team = team
                this.eventKey = match.eventKey
                this.year = year
                this.matchKey = match.key
            }
        }

        return when (type) {
            Type.FULL -> fullMatchPathData.toString()
            Type.AUTO -> autoPathData.toString()
        }
    }

    enum class Type {
        FULL, AUTO
    }

    private fun getPath(team: Int, year: Int, match: TheBlueAlliance.Match, type: Type): String? {
        bot.database.trnsctn {
            val existing = CachedZebra.find { (CachedZebras.matchKey eq match.key) and (CachedZebras.team eq team) and (CachedZebras.eventKey eq match.eventKey) }.singleOrNull()
            existing ?: return@trnsctn null
            return@trnsctn when (type) {
                Type.FULL -> existing.fullPath
                Type.AUTO -> existing.autoPath
            }
        }

        return fetchPath(team, year, match, type)
    }

    private fun color(isRed: Boolean) = if (isRed) RGB(162U, 3U, 17U) else RGB(1U, 71U, 166U)

//    suspend fun renderMatch(team: Int, year: Int, match: TheBlueAlliance.Match, type: Type): ByteArray? {
//        val data = getPath(team, year, match, type) ?: return null
//        return bot.pathDrawer.robotPaths(listOf(data), year, color(match.alliances?.red?.teamKeys?.contains("frc$team") ?: true))
//    }

    suspend fun renderMatches(team: Int, year: Int, eventKey: String, xInverted: Boolean = false, yInverted: Boolean = false): ByteArray? {
        val matches = bot.theBlueAlliance.matches(team, eventKey) ?: return null
        if (matches.isEmpty()) return null

        val data = matches.mapNotNull { getPath(team, year, it, Type.FULL) }

        return bot.pathDrawer.robotPaths(data, year, RGB(255U, 255U, 255U), xInverted, yInverted)
    }

    suspend fun renderAutos(team: Int, year: Int, eventKey: String, xInverted: Boolean = false, yInverted: Boolean = false): ByteArray? {
        val matches = bot.theBlueAlliance.matches(team, eventKey) ?: return null
        if (matches.isEmpty()) return null

        val data = matches.mapNotNull { getPath(team, year, it, Type.AUTO) }

        return bot.pathDrawer.robotPaths(data, year, RGB(255U, 255U, 255U), xInverted, yInverted)
    }
}
