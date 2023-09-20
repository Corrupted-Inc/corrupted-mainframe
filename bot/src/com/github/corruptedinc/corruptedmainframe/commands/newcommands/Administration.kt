package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.corruptedinc.corruptedmainframe.annotations.Command
import com.github.corruptedinc.corruptedmainframe.annotations.P
import com.github.corruptedinc.corruptedmainframe.annotations.ParentCommand
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AuditableAction.*
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.replyPaginator
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes

object Administration {
    @Command("admin", "Makes a user a bot admin")
    suspend inline fun CmdCtx.admin(@P("user", "The user") user: User) {
        assertPermaAdmin()
        bot.database.trnsctn {
            user.m.botAdmin = true
            auditLog(BOT_ADMIN, BotAdmin(user.idLong))
        }
        event.replyEmbeds(embed("Admin", description = "Successfully made ${user.asMention} an admin")).ephemeral().await()
    }

    @Command("unadmin", "Revokes a user's bot admin status")
    suspend inline fun CmdCtx.unadmin(@P("user", "The user") user: User) {
        assertPermaAdmin()
        bot.database.trnsctn {
            val u = user.m
            if (!u.botAdmin) throw CommandException("${user.asMention} is not an admin!")
            u.botAdmin = false
            auditLog(UN_BOT_ADMIN, UnBotAdmin(user.idLong))
        }
        event.replyEmbeds(embed("Admin", description = "Successfully demoted ${user.asMention}")).ephemeral().await()
    }

    @Command("globalban", "Bans a user from using the bot")
    suspend inline fun CmdCtx.globalban(@P("user", "The user") user: User) {
        bot.database.trnsctn {
            assertBotAdmin()
            val u = user.m
            u.banned = true
        }
        event.replyEmbeds(embed("Banned", description = "Banned ${user.asMention} from using the bot")).await()
    }

    @Command("globalunban", "Unbans a user from using the bot")
    suspend inline fun CmdCtx.globalunban(@P("user", "The user") user: User) {
        bot.database.trnsctn {
            assertBotAdmin()
            val u = user.m
            if (!u.banned) throw CommandException("${user.asMention} is not banned!")
            u.banned = true
        }
        event.replyEmbeds(embed("Unbanned", description = "Unbanned ${user.asMention} from using the bot")).await()
    }

    @Command("restart", "Restarts the bot")
    suspend inline fun CmdCtx.restart() {
        bot.database.trnsctn {
            assertBotAdmin()
            auditLog(RESTART, Restart())
        }
        event.replyEmbeds(embed("Shutting down...")).ephemeral().await()
        exitProcess(0)
    }

    @ParentCommand("starboard", "Create/edit/remove starboards", false)
    object StarboardCommands {
        val starboardCache = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(10, TimeUnit.MINUTES).build<Long, List<String>>()!!

        @Command("create", "Create a starboard")
        suspend inline fun CmdCtx.create(
            @P("name", "The name of the starboard") name: String,
            @P("channel", "The starboard channel") channel: GuildMessageChannel,
            @P("emote", "The starboard emote") emote: String,
            @P("threshold", "How many reactions are required") threshold: Int,
            @P("multiplier", "The XP gain multiplier") multiplier: Double
        ) {
            assertAdmin()
            val n = name.strp()
            val emoji = Emoji.fromFormatted(emote).apply {
                if (this is UnicodeEmoji) {
                    ast(bot.emoji.isValid(asUnicode().formatted), "Must be a valid emoji!")
                } else {
                    ast(
                        event.guild!!.getEmojiById(this.asCustom().idLong) != null,
                        "Must be a valid emoji from this server!"
                    )
                }
            }
            ast(channel.guild.idLong == event.guild!!.idLong, "Channel must be from this guild!")
            ast(n.length in 1..255, "Name must be less than 255 characters!")
            ast(threshold > 0, "Threshold must be greater than 0!")
            ast(multiplier in 0.0..1_000.0, "XP multiplier must be between 0 and 1000!")

            bot.database.trnsctn {
                val g = event.guild!!.m
                val boards = g.starboards
                ast(boards.none { it.name == n }, "Starboard names must be unique!")
                ast(boards.count() < 20, "Cannot have more than 20 starboards!")
                ExposedDatabase.Starboard.new {
                    this.guild = g
                    this.name = n
                    this.xpMultiplier = multiplier
                    this.channel = channel.idLong
                    this.emote = emoji.formatted
                    this.threshold = threshold
                }

                starboardCache.put(g.discordId, g.starboards.map { it.name })

                auditLog(STARBOARD_ADD, StarboardL(channel.idLong, emoji.formatted, threshold, multiplier, n))
            }
            event.replyEmbeds(embed("Starboard", description = "Starboard created")).await()
        }

        @Command("remove", "Remove a starboard")
        suspend inline fun CmdCtx.remove(@P("name", "The name of the starboard") name: String) {
            assertAdmin()

            bot.database.trnsctn {
                val g = event.guild!!.m
                val starboards = g.starboards
                // yes this isn't the performant way to do this, but there are max 20 starboards, and we need to get them all for the cache anyways
                val b = starboards.singleOrNull { it.name == name } ?: throw CommandException("Starboard not found!")
                val names = starboards.filterNot { it == b }.map { it.name }

                auditLog(STARBOARD_REMOVE, StarboardL(b))

                b.delete()
                starboardCache.put(g.discordId, names)
            }

            event.replyEmbeds(embed("Starboard", description = "Starboard removed")).await()
        }

        @Command("modify", "Modify a starboard")
        suspend inline fun CmdCtx.modify(
            @P("name", "The name of the starboard") name: String,
            @P("new-name", "The new name of the starboard") newName: String?,
            @P("channel", "The new starboard channel") channel: GuildMessageChannel?,
            @P("emote", "The new starboard emote") emote: String?,
            @P("threshold", "How many reactions will be required") threshold: Int?,
            @P("multiplier", "The new XP gain multiplier") multiplier: Double?
        ) {
            assertAdmin()
            val n = newName?.strp()
            val emoji = emote?.let {
                Emoji.fromFormatted(it).apply {
                    if (this is UnicodeEmoji) {
                        ast(bot.emoji.isValid(asUnicode().formatted), "Must be a valid emoji!")
                    } else {
                        ast(
                            event.guild!!.getEmojiById(this.asCustom().idLong) != null,
                            "Must be a valid emoji from this server!"
                        )
                    }
                }
            }
            if (channel != null) {
                ast(channel.guild.idLong == event.guild!!.idLong, "Channel must be from this guild!")
            }
            if (n != null) {
                ast(n.length in 1..255, "Name must be less than 255 characters!")
            }
            if (threshold != null) {
                ast(threshold > 0, "Threshold must be greater than 0!")
            }
            if (multiplier != null) {
                ast(multiplier in 0.0..1_000.0, "XP multiplier must be between 0 and 1000!")
            }
            bot.database.trnsctn {
                val g = event.guild!!.m
                val board = g.starboards.singleOrNull { it.name == name } ?: throw CommandException("Starboard not found!")
                channel?.apply { board.channel = idLong }
                n?.apply { board.name = n }
                threshold?.apply { board.threshold = threshold }
                multiplier?.apply { board.xpMultiplier = multiplier }
                emoji?.apply { board.emote = emoji.formatted }

                auditLog(STARBOARD_MODIFY, StarboardL(board))
            }

            event.replyEmbeds(embed("Starboard", description = "Starboard modified")).await()
        }

        @Command("list", "List the starboards")
        suspend inline fun CmdCtx.list() {
            val fields = mutableListOf<Field>()
            bot.database.trnsctn {
                val g = event.guild!!.m
                for (board in g.starboards) {
                    fields.add(Field(board.name, "<#${board.channel}> ${board.threshold}x ${board.emote}${if (board.xpMultiplier != 1.0) " (${board.xpMultiplier}x XP)" else ""}", false))
                }
            }
            event.replyEmbeds(embed("Starboards", content = fields)).ephemeral().await()
        }
    }

    @Command("sql", "not for you.")
    suspend inline fun CmdCtx.sql(@P("sql", "bad") sql: String, @P("commit", "commit") commit: Boolean) {
        assertPermaAdmin()

        // TODO rework chunking and add limits
        val rows = bot.database.trnsctn {
            exec(sql) { result ->
                val output = mutableListOf<Row>()
                val r = mutableListOf<String>()
                for (c in 1..result.metaData.columnCount) {
                    r.add(result.metaData.getColumnName(c))
                }
                output.add(Row(*r.toTypedArray()))
                r.clear()
                while (result.next()) {
                    for (c in 1..result.metaData.columnCount) {
                        r.add(result.getObject(c)?.toString() ?: "null")
                    }
                    output.add(Row(*r.toTypedArray()))
                    r.clear()
                }
                if (!commit) {
                    rollback()
                }
                output
            }!!
        }
        val stringified = table(rows.toTypedArray())
        event.replyPaginator(*stringified.chunked(1900).map { embed("sql", description = "```\n$it```") }.toTypedArray(), expireAfter = 10.0.minutes).ephemeral().await()
    }

    @Command("auditlog", "Get the audit log for this guild")
    suspend inline fun CmdCtx.auditLog() {
        val chunked = bot.database.trnsctn {
            assertAdmin()
            val g = event.guild?.m
            if (g == null) assertBotAdmin("Must be a bot admin to see the global audit log!")
            val items = ModerationDB.AuditLog.find { ModerationDB.AuditLogs.guild eq g?.id }.limit(1_000)  // fixme
            items.map { Field(it.type.name.lowercase().split('_').joinToString(" ") { s -> s.replaceFirstChar { c -> c.uppercase() } }, it.deserialized.asField(it.user.discordId), false) }
        }.chunked(10)

        val fields = chunked.mapIndexed { index, fields -> embed("Audit Log ${index + 1} / ${chunked.size}", content = fields) }

        event.replyPaginator(*fields.toTypedArray(), expireAfter = 5.0.minutes).ephemeral().await()
    }
}
