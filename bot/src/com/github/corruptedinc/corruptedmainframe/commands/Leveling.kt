package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.P
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.Commands.user
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.and
import kotlin.math.*

@Suppress("MagicNumber")
class Leveling(private val bot: Bot) {
    companion object {
        const val POINTS_PER_MESSAGE = 2.0
        const val LEVEL_BAR_WIDTH = 24

        fun fightPoints(level: Double, zeroToOne: Double) = ((10 * (level + 2).pow(0.25) + 10) * zeroToOne) + 5

        // TODO: move some other methods here as well
    }

    init {
        bot.jda.listener<MessageReceivedEvent> { event ->
            if (!event.isFromGuild) return@listener
            if (bot.database.bannedT(event.author)) return@listener
            if (event.author == bot.jda.selfUser) return@listener
            bot.scope.launch {
                addPoints(event.author, POINTS_PER_MESSAGE, event.guildChannel)
            }
        }
    }

    fun pointsToLevel(points: Double) = ln((points + 60) / 10).sq() - 3

    fun levelToPoints(level: Double) = (exp(sqrt(level + 3)) * 10) - 60

    fun starboardPoints(level: Double) = 5 * (level + 2).pow(0.25) + 10

    fun level(user: User, guild: Guild) = pointsToLevel(bot.database.points(user, guild))

    fun points(user: User, guild: Guild) = bot.database.points(user, guild)

    suspend fun addPoints(user: User, points: Double, channel: GuildMessageChannel) {
        if (user.isBot) return

        // I'm aware this is bad, but this is run in a spot where there isn't any catching
        @Suppress("TooGenericExceptionCaught")
        try {
            val previousLevel = level(user, channel.guild).toInt()
            bot.database.addPoints(user, channel.guild, points)
            val level = level(user, channel.guild).toInt()
            if (level > previousLevel && bot.database.popups(user, channel.guild)) {
                if (!bot.database.trnsctn { channel.guild.m.levelsEnabled }) return
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
        } catch (e: Exception) {
            bot.log.error("Exception while adding points:\n${e.stackTraceToString()}")
        }
    }

    @Command("level", "Gets your current level")
    suspend inline fun CmdCtx.levelCommand(@P("user", "The user to get the level of") target: User?) {
        val user = target ?: event.user
        val xp = bot.leveling.points(user, event.guild!!)
        val level = bot.leveling.pointsToLevel(xp)
        val levelStartXP = bot.leveling.levelToPoints(floor(level))
        val levelEndXP = bot.leveling.levelToPoints(ceil(level))

        val portion = (xp - levelStartXP) / (levelEndXP - levelStartXP)

        val parts = " ▏▎▍▌▋▊▉█"
        val blocks = LEVEL_BAR_WIDTH * portion
        @Suppress("MagicNumber")
        val out = (parts.last().toString().repeat(blocks.toInt()) +
                parts[((blocks - blocks.toInt().toDouble()) * 8).toInt()]).padEnd(LEVEL_BAR_WIDTH, ' ')

        val start = levelStartXP.toInt().coerceAtLeast(0).toString()
        val end = levelEndXP.toInt().toString()

        event.replyEmbeds(
            Commands.embed(
                "Level ${level.toInt()}", description = "${user.asMention} has " +
                        "${xp.toInt()} points\nonly ${(levelEndXP - xp).roundToInt()} points to " +
                        "go until level ${level.toInt() + 1}!\n" +
                        "`" + start.padEnd(LEVEL_BAR_WIDTH + 2 - end.length, ' ') + end + "`\n" +
                        "`|$out|`",
                thumbnail = user.effectiveAvatarUrl,
                stripPings = false
            )
        ).await()
    }

    @Command("levelnotifs", "Enable or disable level notifications")
    suspend inline fun CmdCtx.levelNotifs(@P("enabled", "If level notifications should be shown") enabled: Boolean, @P("guild", "If this is setting for the whole guild or just your user") guild: Boolean) {
        if (guild) {
            bot.commands.assertPermissions(event, Permission.ADMINISTRATOR)
            bot.database.trnsctn {
                event.guild!!.m.levelsEnabled = enabled
            }
            event.replyEmbeds(Commands.embed("Successfully ${if (enabled) "enabled" else "disabled"} guild level notifications")).await()
        } else {
            bot.database.setPopups(event.user, event.guild ?: throw CommandException("This command must be run in a server!"), enabled)
            event.replyEmbeds(Commands.embed("Set level popups to $enabled")).await()
        }
    }

    val rankStatementPrepared = bot.database.trnsctn {
        @Language("sql") val rankStatement = """
            UPDATE points SET rank = RankTable.rank 
            FROM (SELECT id, DENSE_RANK() OVER(ORDER BY points DESC, id) 
            AS rank FROM points WHERE guild = ?) AS RankTable WHERE RankTable.id = points.id""".trimIndent()
        connection.prepareStatement(rankStatement, false)
    }

    @Command("leaderboard", "Show the guild leaderboard")
    suspend inline fun CmdCtx.leaderboard() {
        // update rank column
        bot.database.trnsctn {
            val g = event.guild!!.m
            rankStatementPrepared.fillParameters(listOf(LongColumnType() to g.id.value))
            rankStatementPrepared.executeQuery()
        }

        val guildid = event.guild!!.idLong

        val area = { start: Long, end: Long ->
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

            Commands.embed(
                "Leaderboard $start - $end",
                description = "```" + table(rankCol, nameCol, pntsCol) + "```"
            )
        }

        val perMessage = 15
        val size = event.guild!!.memberCount / perMessage  // todo: dejankify

        event.replyLambdaPaginator(size.toLong()) { v ->
            area(v * perMessage, (v + 1) * perMessage)
        }.await()
    }

    fun registerCommands() {
        bot.commands.registerUser(user("level")) { event ->
            val user = event.targetMember!!.user
            val xp = bot.leveling.points(user, event.guild!!)
            val level = bot.leveling.pointsToLevel(xp)
            val levelStartXP = bot.leveling.levelToPoints(floor(level))
            val levelEndXP = bot.leveling.levelToPoints(ceil(level))

            val portion = (xp - levelStartXP) / (levelEndXP - levelStartXP)

            val parts = " ▏▎▍▌▋▊▉█"
            val blocks = LEVEL_BAR_WIDTH * portion
            @Suppress("MagicNumber")
            val out = (parts.last().toString().repeat(blocks.toInt()) +
                    parts[((blocks - blocks.toInt().toDouble()) * 8).toInt()]).padEnd(LEVEL_BAR_WIDTH, ' ')

            val start = levelStartXP.toInt().coerceAtLeast(0).toString()
            val end = levelEndXP.toInt().toString()

            event.replyEmbeds(embed("Level ${level.toInt()}", description = "${user.asMention} has " +
                    "${xp.toInt()} points\nonly ${(levelEndXP - xp).roundToInt()} points to " +
                    "go until level ${level.toInt() + 1}!\n" +
                    "`" + start.padEnd(LEVEL_BAR_WIDTH + 2 - end.length, ' ') + end + "`\n" +
                    "`|$out|`",
                thumbnail = user.effectiveAvatarUrl,
                stripPings = false)).ephemeral().await()
        }
    }
}
