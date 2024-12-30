package com.github.corruptedinc.corruptedmainframe.commands.newcommands

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.corruptedinc.corruptedmainframe.annotations.*
import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AuditableAction.*
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.interactions.components.paginator
import dev.minn.jda.ktx.interactions.components.replyPaginator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Guild.Ban
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.UnicodeEmoji
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.not
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

object Administration {
    @Command("admin", "Makes a user a bot admin", global = true)
    suspend inline fun CmdCtx.admin(@P("user", "The user") user: User) {
        assertPermaAdmin()
        bot.database.trnsctn {
            user.m.botAdmin = true
            auditLog(BOT_ADMIN, BotAdmin(user.idLong))
        }
        event.replyEmbeds(embed("Admin", description = "Successfully made ${user.asMention} an admin")).ephemeral().await()
    }

    @Command("unadmin", "Revokes a user's bot admin status", global = true)
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

    @Command("globalban", "Bans a user from using the bot", global = true)
    suspend inline fun CmdCtx.globalban(@P("user", "The user") user: User) {
        bot.database.trnsctn {
            assertBotAdmin()
            val u = user.m
            if (u.botAdmin && user.id !in bot.config.permaAdmins) throw CommandException("Can't ban another bot admin!")
            u.banned = true
            auditLog(GLOBAL_BAN, GlobalBan(user.idLong))
        }
        event.replyEmbeds(embed("Banned", description = "Banned ${user.asMention} from using the bot")).await()
    }

    @Command("globalunban", "Unbans a user from using the bot", global = true)
    suspend inline fun CmdCtx.globalunban(@P("user", "The user") user: User) {
        bot.database.trnsctn {
            assertBotAdmin()
            val u = user.m
            if (!u.banned) throw CommandException("${user.asMention} is not banned!")
            u.banned = true
            auditLog(GLOBAL_UNBAN, GlobalUnBan(user.idLong))
        }
        event.replyEmbeds(embed("Unbanned", description = "Unbanned ${user.asMention} from using the bot")).await()
    }

    @Command("restart", "Restarts the bot", global = true)
    suspend inline fun CmdCtx.restart() {
        bot.database.trnsctn {
            assertBotAdmin()
            auditLog(RESTART, Restart())
        }
        event.replyEmbeds(embed("Shutting down...")).ephemeral().await()
        exitProcess(0)
    }

    @ParentCommand("starboard", "Create/edit/remove starboards")
    object StarboardCommands {
        val starboardCache = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(10, TimeUnit.MINUTES).build<Long, List<String>>()!!

        @Autocomplete("starboard/modify/name")
        @Autocomplete("starboard/remove/name")
        suspend inline fun AutocompleteContext.starboardNameAutocomplete(): List<Choice> {
            val value = event.focusedOption.value.dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }
            val starboards = bot.database.trnsctn { event.guild!!.m.starboards.map { it.name } }.sortedBy { biasedLevenshteinInsensitive(it, value) }
            return starboards.take(5).map { Choice(it, it) }
        }

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

    @Command("sql", "not for you.", global = true)
    suspend inline fun CmdCtx.sql(@P("sql", "bad") sql: String, @P("commit", "commit") commit: Boolean) {
        assertPermaAdmin()

        // TODO rework chunking and add limits
        val rows = bot.database.trnsctn {
            auditLog(SQL, Sql(sql, commit))
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
        // TODO: is this right??
        assertAdmin()
        val perPage = 10
        val (pageCount, gid) = bot.database.trnsctn {
            assertBotAdmin()
            val g = event.guild?.m
            ceil(ModerationDB.AuditLog.find { ModerationDB.AuditLogs.guild eq g?.id }.count().toDouble() / perPage).toLong() to g?.id?.value
        }

        event.replyLambdaPaginator(pageCount) { pageNum ->
            val fields = bot.database.trnsctn {
                val items = ModerationDB.AuditLog.find { ModerationDB.AuditLogs.guild eq gid }.limit(perPage, pageNum * perPage).orderBy(ModerationDB.AuditLogs.timestamp to SortOrder.DESC)
                items.map { Field(it.type.name.lowercase().split('_').joinToString(" ") { s -> s.replaceFirstChar { c -> c.uppercase() } }, it.deserialized.asField(it.user.discordId), false) }
            }
            embed("Audit Log ${pageNum + 1} / $pageCount", content = fields, stripPings = false)
        }.ephemeral().await()
    }

    @ParentCommand("rolemenu", "Edits a reaction role menu")
    object RoleMenuCommands {
        // create - creates a menu in the given channel
        // delete - deletes by message link or name (autocompleted) (also delete on message deletion?  maybe not, no point)
        // edit
        //  additem - adds an item, takes a name (autocompleted), emoji, and role, optionally inserting it at the nth index
        //  removeitem - removes an item by emoji
        //  move item - moves an item to the nth index (TODO)
        // TODO: name autocomplete
        // TODO: limit number of roles per message

        data class RoleData(val role: Long, val description: String?, val emoji: String)

        fun generateEmbed(name: String, roles: List<RoleData>): MessageEmbed {
            return embed(name, description = roles.joinToString("\n") { "<@&${it.role}>${it.description?.let { d -> " (${d})" } ?: ""}:  ${it.emoji}" }, stripPings = false)
        }

        @Command("create", "Creates a role menu")
        suspend inline fun CmdCtx.create(@P("menuname", "The name of the menu") name: String, @P("channel", "The channel to put the menu in") channel: TextChannel) {
            assertAdmin()

            val nme = name.strp()
            if (nme.length > 255) {
                throw CommandException("Name too long (limit 255 characters)!")
            }

            bot.database.trnsctn {
                val g = event.guild!!.m
                if (g.roleMenus.any { it.name == nme }) {
                    throw CommandException("That name is taken!")
                }
            }

            if (!event.guild!!.selfMember.hasPermission(channel, listOf(Permission.MESSAGE_ADD_REACTION, Permission.MESSAGE_SEND, Permission.MANAGE_ROLES))) {
                throw CommandException("Missing permissions!")
            }

            val message = channel.sendMessageEmbeds(generateEmbed(name, listOf())).await()

            bot.database.trnsctn {
                val g = event.guild!!.m
                val menu = ModerationDB.RoleMenu.new {
                    this.guild = g
                    this.message = message.idLong
                    this.name = name
                    this.channel = channel.idLong
                }

                auditLog(REACTIONMENU_CREATE, ReactionMenuL(channel.idLong, menu.id.value, menu.name, listOf()))
            }

            event.replyEmbeds(embed("Success", description = "Successfully created role menu '$name', add roles with `/rolemenu edit additem`")).ephemeral().await()
        }

        @Command("delete", "Deletes a role menu")
        suspend inline fun CmdCtx.delete(@P("menuname", "The name of the role menu (in the title)") name: String) {
            assertAdmin()
            // TODO
        }

        @CommandGroup("edit", "Edits a role menu")
        object EditMenu {
            @Command("additem", "Adds a role to the role menu")
            suspend inline fun CmdCtx.addItem(@P("menuname", "The name of the role menu (in the title)") menuName: String, @P("emoji", "The emoji") emoji: String, @P("role", "The role") role: Role, @P("index", "The index to insert the role after (optional)") index: Int?) {
                assertAdmin()

                if (!event.guild!!.selfMember.canInteract(role)) {
                    throw CommandException("Can't interact with that role!")
                }

                val em = try { Emoji.fromFormatted(emoji) } catch (e: Exception) { throw CommandException("Invalid emoji!") }
                val emote = when (em.type) {
                    Emoji.Type.UNICODE -> {
                        val e = em.asUnicode().name
                        if (!bot.emoji.isValid(e)) {
                            throw CommandException("Invalid emoji!")
                        }
                        e
                    }
                    Emoji.Type.CUSTOM -> em.asCustom().asMention
                }

                val (new, channel, message) = bot.database.trnsctn {
                    val g = event.guild!!.m
                    val menu = g.roleMenus.singleOrNull { it.name == menuName } ?: throw CommandException("Menu not found!")
                    val idx = index ?: menu.roles.count().toInt()

                    if (idx < menu.roles.count()) {
                        // shift others up
                        for (v in menu.roles) {
                            if (v.index < idx) {
                                continue
                            }
                            v.index += 1
                        }
                    }

                    ModerationDB.RoleMenuItem.new {
                        this.menu = menu
                        this.role = role.idLong
                        this.emote = emote
                        this.index = idx
                        this.description = null
                    }

                    auditLog(REACTIONMENU_ADDITEM, ReactionMenuL(menu.channel, menu.id.value, menu.name, menu.roles.map { RoleData(it.role, it.description, it.emote) }))

                    Triple(generateEmbed(menu.name, menu.roles.sortedBy { it.index }.map { RoleData(it.role, it.description, it.emote) }), menu.channel, menu.message)
                }

                val msg = bot.jda.getTextChannelById(channel)!!.retrieveMessageById(message).await()!!
                msg.editMessageEmbeds(new).await()
                msg.addReaction(em).await()

                event.replyEmbeds(embed("Success", description = "Successfully modified menu '${menuName.strp()}'")).ephemeral().await()
            }

            @Command("removeitem", "Removes a role from the role menu")
            suspend inline fun CmdCtx.removeItem(@P("menuname", "The name of the role menu (in the title)") menuName: String, @P("emoji", "The emoji") emoji: String) {
                assertAdmin()

                val em = try { Emoji.fromFormatted(emoji) } catch (e: Exception) { throw CommandException("Invalid emoji!") }
                val emote = when (em.type) {
                    Emoji.Type.UNICODE -> {
                        val e = em.asUnicode().name
                        if (!bot.emoji.isValid(e)) {
                            throw CommandException("Invalid emoji!")
                        }
                        e
                    }
                    Emoji.Type.CUSTOM -> em.asCustom().asMention
                }

                val (new, channel, message) = bot.database.trnsctn {
                    val g = event.guild!!.m
                    val menu = g.roleMenus.singleOrNull { it.name == menuName } ?: throw CommandException("Menu not found!")
                    val found = menu.roles.firstOrNull { it.emote == emote } ?: throw CommandException("Emoji not found!")
                    val idx = found.index
                    found.delete()
                    for (item in menu.roles) {
                        if (item.index > idx) {
                            item.index -= 1
                        }
                    }

                    auditLog(REACTIONMENU_REMOVEITEM, ReactionMenuL(menu.channel, menu.id.value, menu.name, menu.roles.map { RoleData(it.role, it.description, it.emote) }))

                    Triple(generateEmbed(menu.name, menu.roles.sortedBy { it.index }.map { RoleData(it.role, it.description, it.emote) }), menu.channel, menu.message)
                }

                val msg = bot.jda.getTextChannelById(channel)!!.retrieveMessageById(message).await()!!
                msg.editMessageEmbeds(new).await()
                msg.clearReactions(em).await()

                event.replyEmbeds(embed("Success", description = "Successfully modified menu '${menuName.strp()}'")).ephemeral().await()
            }
        }
    }

    object Warnings {
        suspend fun listener(bot: Bot) {
            while (true) {
                // find expiring warnings, expire them, update to expired
                val toUnwarn = bot.database.trnsctn {
                    ModerationDB.UserLog.find { not(ModerationDB.UserLogs.warnExpired) and (ModerationDB.UserLogs.warnExpiry lessEq Instant.now()) }
                        .map { Triple(it.id.value, it.user.discordId, Pair(it.guild.discordId, it.guild.modChannel)) }
                }
                for ((_, u, g) in toUnwarn) {
                    val user = try { bot.jda.retrieveUserById(u).await() } catch (e: Throwable) { continue }  // if the user doesn't exist, skip it
                    val guild = bot.jda.getGuildById(g.first) ?: continue  // if it isn't in the guild anymore, skip it
                    val data = doUnWarn(bot, user, guild, null, "warn expired")
                    val ch = g.second?.let { guild.getTextChannelById(it) }
                    if (data.actionTaken != "none" && ch != null) {
                        val expiry = data.punishment.warnExpiry?.let { "<t:${it.first.epochSecond}> (${it.second.toHumanReadable()})" } ?: "never"
                        ch.sendMessageEmbeds(
                            embed(
                                "Unwarned",
                                description = "Unwarned ${user.asMention} (${user.name}) for reason warn expired\nNow at level ${data.punishment.level ?: 0}\nActions taken: ${data.actionTaken}\nCurrent warn level decreases $expiry",
                                stripPings = false
                            )
                        ).await()
                    }
                }
                bot.database.trnsctn {
                    for ((id, _, _) in toUnwarn) {
                        ModerationDB.UserLog[id].warnExpired = true
                    }
                }
                delay(1000L)
            }
        }

        context(ExposedDatabase, Transaction)
        fun level(u: ExposedDatabase.UserM, g: ExposedDatabase.GuildM): Int? {
            return ModerationDB.UserLog.find { (ModerationDB.UserLogs.user eq u.id) and (ModerationDB.UserLogs.guild eq g.id) and (ModerationDB.UserLogs.warnLevel.isNotNull()) }.orderBy(ModerationDB.UserLogs.timestamp to SortOrder.DESC).firstOrNull()?.warnLevel
        }

        data class Punishment(val level: Int?, val channel: TextChannel?, val role: Role?, val type: ModerationDB.PunishmentType, val duration: Duration?, val warnExpiry: Pair<Instant, Duration>?)
        suspend fun replacePunishment(bot: Bot, user: User, guild: Guild, newPunishment: Punishment?, reason: String): String {
            val actionsTaken = mutableListOf<String>()

            val member = guild.getMember(user)!!

            // get a list of all the warning roles, then intersect it with the user roles.  This results in the set of
            // warning roles that the user in question has
            val rolesToRemove = bot.database.trnsctn {
                guild.m.warningTypes.mapNotNull { it.role }
            }.map { guild.getRoleById(it)!! }.toSet().intersect(member.roles.toSet()).toMutableSet()
            val newWarnRole = newPunishment?.role
            // remove the new warning role from that set if applicable
            rolesToRemove.remove(newWarnRole)
            // update roles
            guild.modifyMemberRoles(member, newWarnRole?.let { listOf(it) }.orEmpty(), rolesToRemove).await()

            if (newPunishment?.type == ModerationDB.PunishmentType.BAN) {
                // if they should be banned, ban them (TODO: check if already banned)
                guild.ban(user, 0, TimeUnit.SECONDS).reason(reason).await()
                actionsTaken.add("banned")
            } else {
                // if they shouldn't be banned, then check if they are.  If they are banned, unban them
                val ban: Ban? = guild.retrieveBan(user).map { it }.onErrorMap { null }.await()
                ban?.run {
                    guild.unban(user).reason(reason).await()
                    actionsTaken.add("unbanned")
                }
            }

            if (newPunishment?.type == ModerationDB.PunishmentType.MUTE) {
                // if they should be muted, mute them (TODO: check if already muted)
                member.timeoutFor(newPunishment.duration!!).reason(reason).await()
                actionsTaken.add("timed out for ${newPunishment.duration}")
            } else {
                // if they shouldn't be muted, then check if they are.  If they are muted, unmute them
                if (member.isTimedOut) {
                    member.removeTimeout().reason(reason).await()
                    actionsTaken.add("removed timeout")
                }
            }

            if (newPunishment?.type == ModerationDB.PunishmentType.KICK) {
                // kick user
                member.kick().reason(reason).await()
                actionsTaken.add("kicked")
            }

            return if (actionsTaken.isEmpty()) "none" else actionsTaken.joinToString()
        }

        data class WarnData(val actionTaken: String, val punishment: Punishment, val modChannelID: Long?, val logID: Long)

        suspend fun doWarn(bot: Bot, user: User, guild: Guild, admin: User?, reason: String): WarnData {
            val (newPunishment, channel, id) = bot.database.trnsctn {
                val g = guild.m
                val u = user.m

                val currentLevel = level(u, g)
                val newLevel = currentLevel?.plus(1) ?: 1

                val newPunishment = g.warningTypes.find { it.index == newLevel } ?: throw CommandException("Warning level $newLevel not found!")

                // set all other active warnings as expired
                ModerationDB.UserLog.find { (ModerationDB.UserLogs.user eq u.id) and (ModerationDB.UserLogs.guild eq g.id) and not(ModerationDB.UserLogs.warnExpired) }
                    .forEach { it.warnExpired = true }

                val log = ModerationDB.UserLog.new {
                    this.user = u
                    this.guild = g
                    this.warnLevel = newLevel
                    this.reason = reason
                    this.type = ModerationDB.LogType.WARN.ordinal
                    this.admin = admin?.m
                    this.timestamp = Instant.now()
                    this.warnExpired = newPunishment.expiry == null
                    this.warnExpiry = newPunishment.expiry?.let { Instant.now() + it }
                    this.punishmentDuration = newPunishment.punishmentDuration
                }

                return@trnsctn Triple(Punishment(newLevel, g.modChannel?.let { guild.getTextChannelById(it) }, newPunishment.role?.let { guild.getRoleById(it) }, ModerationDB.PunishmentType.entries[newPunishment.punishmentType], newPunishment.punishmentDuration, newPunishment.expiry?.let { Pair(Instant.now() + it, it) }), g.modChannel, log.id.value)
            }

            // TODO: assert permissions

//            removeAllPunishments(bot, user, guild)
//            val actionTaken = applyPunishment(bot, user, guild, newPunishment, reason)
            val actionTaken = replacePunishment(bot, user, guild, newPunishment, reason)

            return WarnData(actionTaken, newPunishment, channel, id)
        }

        suspend fun doUnWarn(bot: Bot, user: User, guild: Guild, admin: User?, reason: String): WarnData {
            val (newPunishment, channel, id) = bot.database.trnsctn {
                val g = guild.m
                val u = user.m

                val currentLevel = level(u, g)
                val newLevel = currentLevel?.minus(1) ?: throw CommandException("User is not warned!")

                var newPunishment = if (newLevel == 0) { null } else { g.warningTypes.find { it.index == newLevel } ?: throw CommandException("No warning level $newLevel found!") }
                // TODO: is this right?
                newPunishment = null

                // set all other active warnings as expired
                ModerationDB.UserLog.find { (ModerationDB.UserLogs.user eq u.id) and (ModerationDB.UserLogs.guild eq g.id) and not(ModerationDB.UserLogs.warnExpired) }
                    .forEach { it.warnExpired = true }

                val log = ModerationDB.UserLog.new {
                    this.user = u
                    this.guild = g
                    this.warnLevel = newLevel
                    this.reason = reason
                    this.type = ModerationDB.LogType.UNWARN.ordinal
                    this.admin = admin?.m
                    this.timestamp = Instant.now()
                    this.warnExpiry = newPunishment?.expiry?.let { Instant.now() + it }
                    this.warnExpired = newPunishment?.expiry == null
                    this.punishmentDuration = newPunishment?.punishmentDuration
                }

                return@trnsctn Triple(
                    Punishment(
                        newLevel,
                        g.modChannel?.let { guild.getTextChannelById(it) },
                        newPunishment?.role?.let { guild.getRoleById(it) },
                        newPunishment?.punishmentType?.let { ModerationDB.PunishmentType.entries[it] } ?: ModerationDB.PunishmentType.NONE,
                        newPunishment?.punishmentDuration,
                        newPunishment?.expiry?.let { Pair(Instant.now() + it, it) }
                    ),
                    g.modChannel,
                    log.id.value
                )
            }

            // TODO: assert permissions

//            removeAllPunishments(bot, user, guild)
//            val actionTaken = applyPunishment(bot, user, guild, newPunishment, reason)
            val actionTaken = replacePunishment(bot, user, guild, newPunishment, reason)

            return WarnData(actionTaken, newPunishment, channel, id)
        }

        @Command("warn", "Warns a user")
        suspend inline fun CmdCtx.warnCommand(@P("user", "The user to warn") user: User, @P("reason", "The reason to warn") reason: String, @P("publish", "If true, logs to the mod log channel") publish: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(user) ?: throw CommandException("User is not a member of this guild!")

            val data = doWarn(bot, user, event.guild!!, event.user, reason)

            auditLogT(GUILDLOGGED_ACTION, GuildLoggedActionL(data.logID))

            event.replyEmbeds(embed("Warned", description = "Warned ${user.asMention} (${user.name}), actions taken: ${data.actionTaken}", stripPings = false)).ephemeral().await()

            val ch = data.modChannelID?.let { event.guild!!.getTextChannelById(it) }
            if (publish && ch != null) {
                val expiry = data.punishment.warnExpiry?.let { "<t:${it.first.epochSecond}> (${it.second.toHumanReadable()})" } ?: "never"
                ch.sendMessageEmbeds(
                    embed(
                        "Warned",
                        description = "Warned ${user.asMention} (${user.name}) for reason $reason\nNow at level ${data.punishment.level ?: 0}\nActions taken: ${data.actionTaken}\nExpires $expiry\nPerformed by ${event.user.asMention}",
                        stripPings = false
                    )
                ).await()
            }
        }

        @Command("unwarn", "Un-warns a user")
        suspend inline fun CmdCtx.unwarnCommand(@P("user", "The user to un-warn") user: User, @P("reason", "The reason to warn") reason: String, @P("publish", "If true, logs to the mod log channel") publish: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            // you have to be able to unwarn non-members if they were kicked or banned
//            val member = event.guild!!.getMember(user) ?: throw CommandException("User is not a member of this guild!")

            val data = doUnWarn(bot, user, event.guild!!, event.user, reason)
            auditLogT(GUILDLOGGED_ACTION, GuildLoggedActionL(data.logID))

            event.replyEmbeds(embed("Unwarned", description = "Unwarned ${user.asMention} (${user.name}), actions taken: ${data.actionTaken}", stripPings = false)).ephemeral().await()

            val ch = data.modChannelID?.let { event.guild!!.getTextChannelById(it) }
            if (publish && ch != null) {
                val expiry = data.punishment.warnExpiry?.let { "<t:${it.first.epochSecond}> (${it.second.toHumanReadable()})" } ?: "never"
                ch.sendMessageEmbeds(
                    embed(
                        "Unwarned",
                        description = "Unwarned ${user.asMention} (${user.name}) for reason $reason\nNow at level ${data.punishment.level ?: 0}\nActions taken: ${data.actionTaken}\nCurrent warn level expires $expiry\n" +
                                "Performed by ${event.user.asMention}",
                        stripPings = false
                    )
                ).await()
            }
        }

        @Command("modnote", "Add a note in the moderation log for a user")
        suspend inline fun CmdCtx.modnoteCommand(@P("user", "The user to add the note for") user: User, @P("note", "The note") note: String, @P("publish", "If true, logs to the mod log channel") publish: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val ch = bot.database.trnsctn {
                ModerationDB.UserLogs.logNote(user, event.guild!!, event.user, note)
                event.guild!!.m.modChannel
            }?.let { event.guild!!.getTextChannelById(it) }

            event.replyEmbeds(embed("Note added", description = "Added note '$note' to user ${user.asMention}")).await()

            if (publish && ch != null) {
                ch.sendMessageEmbeds(
                    embed(
                        "Note added",
                        description = "Added a note to ${user.asMention}: '$note'\nPerformed by ${event.user.asMention}",
                        stripPings = false
                    )
                ).await()
            }
        }

        @Command("kick", "Kicks a user")
        suspend inline fun CmdCtx.kick(@P("user", "The user to kick") victim: User, @P("reason", "The kick reason") reason: String, @P("publish", "If true, logs to the mod log channel") publish: Boolean) {
            assertPermissions(Permission.KICK_MEMBERS, Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.KICK_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")
            member.kick().reason(reason).await()
            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logKick(victim, event.guild!!, event.user, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Kicked", description = "Kicked ${victim.asMention}", stripPings = false)).ephemeral().await()

            if (publish && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Kicked", description = "Kicked ${victim.asMention} for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("ban", "Bans a user")
        suspend inline fun CmdCtx.ban(@P("user", "The user to ban") victim: User, @P("reason", "The ban reason") reason: String, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.BAN_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")
            member.ban(0, TimeUnit.SECONDS).reason(reason).await()
            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logBan(victim, event.guild!!, event.user, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Banned", description = "Banned ${victim.asMention}", stripPings = false)).ephemeral().await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Banned", description = "Banned ${victim.asMention} for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("unban", "Unbans a user")
        suspend inline fun CmdCtx.unban(@P("user", "The user to unban") victim: User, @P("reason", "The unban reason") reason: String, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS, channel = null)

            if (!event.guild!!.selfMember.hasPermission(Permission.BAN_MEMBERS)) throw CommandException("Insufficient bot permissions!")
            event.guild!!.unban(victim).reason(reason).await()
            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logUnban(victim, event.guild!!, event.user, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Unbanned", description = "Unbanned ${victim.asMention}", stripPings = false)).ephemeral().await()

            victim.openPrivateChannel().onSuccess { it.sendMessageEmbeds(embed("Unbanned", description = "${event.user.asMention} unbanned you for reason: $reason", stripPings = false)) }.await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Unbanned", description = "Unbanned ${victim.asMention} for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("timeout", "Times out a user")
        suspend inline fun CmdCtx.timeout(@P("user", "The user to time out") victim: User, @P("reason", "The time out reason") reason: String, @P("hours", "The number of hours to mute for") hours: Double, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.MODERATE_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")
            val duration = hours.hours
            if (duration !in kotlin.time.Duration.ZERO..28.days) throw CommandException("Invalid duration!")
            member.timeoutFor(duration.toJavaDuration()).reason(reason).await()
            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logTimeout(victim, event.guild!!, event.user, duration, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Timed out", description = "Timed out ${victim.asMention} for ${duration.inWholeHours} hours", stripPings = false)).ephemeral().await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Timed out", description = "Timed out ${victim.asMention} for ${duration.inWholeHours} hours for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("unmute", "Unmutes a user")
        suspend inline fun CmdCtx.untimeout(@P("user", "The user to unmute") victim: User, @P("reason", "The unmute reason") reason: String, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.MODERATE_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")
            if (!member.isTimedOut) throw CommandException("User is not timed out!")
            member.removeTimeout().reason(reason).await()
            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logUnTimeout(victim, event.guild!!, event.user, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Unmuted", description = "Unmuted ${victim.asMention}", stripPings = false)).ephemeral().await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Unmute", description = "Unmuted ${victim.asMention} for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("selfdeafen", "Temporarily your message read permissions")
        suspend inline fun CmdCtx.selfdeafen(@P("hours", "The number of hours to deafen for") hours: Double, @P("reason", "The reason for self-deafening") reason: String?) {
            val member = event.member ?: throw CommandException("Must be run in a guild!")

            if (!event.guild!!.selfMember.hasPermission(Permission.MODERATE_MEMBERS) /*|| !event.guild!!.selfMember.canInteract(member) */) throw CommandException("Insufficient bot permissions!")
            val duration = hours.hours
            if (duration !in kotlin.time.Duration.ZERO..28.days) throw CommandException("Invalid duration!")
            val r = bot.database.trnsctn {
                event.guild!!.m.deafenRole
            } ?: throw CommandException("Deafens are not configured in this guild")

            val role = event.guild!!.getRoleById(r) ?: throw CommandException("Deafen role does not exist!")

            try {
                event.guild!!.addRoleToMember(member, role).await()
            } catch (_: Throwable) {
                throw CommandException("Failed to add deafen role!")
            }

            bot.database.trnsctn {
                ModerationDB.UserLogs.logDeafen(event.user, event.guild!!, event.user, duration, reason ?: "")
            }

            event.replyEmbeds(embed("Deafening", description = "Deafening you for ${duration.inWholeHours} hours", stripPings = false)).ephemeral().await()
        }

        @Command("deafen", "Deafens a user")
        suspend inline fun CmdCtx.deafen(@P("user", "The user to deafen") victim: User, @P("reason", "The deafen reason") reason: String, @P("hours", "The number of hours to deafen for") hours: Double, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.MODERATE_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")
            val duration = hours.hours
            if (duration !in kotlin.time.Duration.ZERO..28.days) throw CommandException("Invalid duration!")
            val r = bot.database.trnsctn {
                event.guild!!.m.deafenRole
            } ?: throw CommandException("No guild deafen role configured!")

            val role = event.guild!!.getRoleById(r) ?: throw CommandException("Deafen role does not exist!")

            try {
                event.guild!!.addRoleToMember(member, role).await()
            } catch (_: Throwable) {
                throw CommandException("Failed to add deafen role to member!")
            }

            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logDeafen(victim, event.guild!!, event.user, duration, reason)
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Deafened", description = "Deafened ${victim.asMention} for ${duration.inWholeHours} hours", stripPings = false)).ephemeral().await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Deafened", description = "Deafened ${victim.asMention} for ${duration.inWholeHours} hours for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("undeafen", "Undeafens a user")
        suspend inline fun CmdCtx.undeafen(@P("user", "The user to undeafen") victim: User, @P("reason", "The undeafen reason") reason: String, @P("publish", "If true, logs to the mod log channel") log: Boolean) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val member = event.guild!!.getMember(victim) ?: throw CommandException("User is not a member of this guild!")
            if (!event.guild!!.selfMember.hasPermission(Permission.MODERATE_MEMBERS) || !event.guild!!.selfMember.canInteract(member)) throw CommandException("Insufficient bot permissions!")

            val r = bot.database.trnsctn {
                event.guild!!.m.deafenRole
            } ?: throw CommandException("No guild deafen role configured!")

            val role = event.guild!!.getRoleById(r) ?: throw CommandException("Deafen role does not exist!")

            try {
                if (member.roles.contains(role)) {
                    event.guild!!.removeRoleFromMember(member, role).await()
                }
            } catch (_: Throwable) {
                throw CommandException("Failed to remove deafen role from member!")
            }

            val channel = bot.database.trnsctn {
                ModerationDB.UserLogs.logUndeafen(victim, event.guild!!, event.user, reason)
                val u = victim.m
                for (p in ModerationDB.UserLog.find { (ModerationDB.UserLogs.user eq u.id) and (ModerationDB.UserLogs.type eq ModerationDB.LogType.DEAFEN.ordinal) and (ModerationDB.UserLogs.punishmentExpired eq false) }) {
                    p.punishmentExpired = true
                }
                event.guild!!.m.modChannel
            }

            event.replyEmbeds(embed("Undeafened", description = "Undeafened ${victim.asMention}", stripPings = false)).ephemeral().await()

            if (log && channel != null) {
                event.guild!!.getTextChannelById(channel)!!.sendMessageEmbeds(embed("Undeafened", description = "Undeafened ${victim.asMention} for reason: $reason\n" +
                        "Performed by ${event.user.asMention}", stripPings = false)).await()
            }
        }

        @Command("setdeafenrole", "Sets the guild's deafen role")
        suspend inline fun CmdCtx.setDeafenRole(@P("role", "The role that deafened members are assigned") role: Role) {
            assertAdmin()

            if (!event.guild!!.selfMember.canInteract(role) || !event.guild!!.selfMember.hasPermission(Permission.MANAGE_ROLES)) {
                throw CommandException("Missing permissions to assign role")
            }

            bot.database.trnsctn {
                event.guild!!.m.deafenRole = role.idLong
            }

            event.replyEmbeds(Commands.embed("Set role", description = "Deafened members will be assigned the ${role.asMention} role.  The role will not be automatically configured, manually disable the view messages permission for this role in each category", stripPings = false)).ephemeral().await()
        }

        @Command("modlog", "View the moderation log for a user")
        suspend inline fun CmdCtx.modlog(@P("user", "The user to view the log for") victim: User) {
            assertPermissions(Permission.MODERATE_MEMBERS, channel = null)

            val items = bot.database.trnsctn {
                val u = victim.m
                val g = event.guild!!.m
                val lvl = level(u, g) ?: 0
                ModerationDB.UserLog.find { (ModerationDB.UserLogs.user eq u.id) and (ModerationDB.UserLogs.guild eq g.id) }
                    .orderBy(ModerationDB.UserLogs.timestamp to SortOrder.DESC)
                    .map { it.format() }
                    .withIndex()
                    .chunked(10)
                    .map { embed("Log items ${it[0].index}-${it.last().index} for user ${victim.effectiveName}", description = "currently at level $lvl\n" + it.joinToString("\n") { v -> v.value }, stripPings = false) }
            }

            event.replyPaginator(paginator(*items.toTypedArray(), expireAfter = 5.minutes)).ephemeral().await()
        }

        @Command("modchannel", "Set the guild mod log channel")
        suspend inline fun CmdCtx.modchannel(@P("channel", "The channel to send mod logs in") channel: TextChannel) {
            assertAdmin()

            bot.database.trnsctn {
                val g = event.guild!!.m
                g.modChannel = channel.idLong
            }

            event.replyEmbeds(embed("Success", description = "Set mod channel to ${channel.asMention}", stripPings = false)).ephemeral().await()
        }

        @ParentCommand("warnlevels", "Edit the guild's warning levels")
        object WarnLevels {
            // C - Append warn levels (done)
            // R - List warn levels and punishments (done)
            // U - Edit warn levels (duration, type, roles) (TODO)
            // D - I don't feel like it

            @Command("list", "List the guild's warn levels")
            suspend inline fun CmdCtx.listWarnLevels() {
                val fields = bot.database.trnsctn {
                    val g = event.guild!!.m

                    // good code
                    g.warningTypes.map { Field(it.index.toString(), "${it.role?.let { id -> "<@&$id> " } ?: ""}expires ${it.expiry?.let { d -> "after ${d.toHumanReadable()}" } ?: "never"}\npunishment: ${ModerationDB.PunishmentType.entries[it.punishmentType].name.lowercase()}${it.punishmentDuration?.let { d -> " for ${d.toHumanReadable()}" } ?: ""}", false) }
                }

                event.replyEmbeds(embed("Warn Levels", content = fields, stripPings = false)).ephemeral().await()
            }

            @Command("add", "Add a warn level")
            suspend inline fun CmdCtx.addWarnLevel(@P("role", "The role associated with the warning level") role: Role, @P("type", "The punishment type (none, mute, kick, or ban)") type: String, @P("duration", "The duration for the **punishment** (DD:HH:MM:SS)") punishmentDuration: String?, @P("expiry", "The duration until the **warning** expires, will also undo punishment (DD:HH:MM:SS)") expiry: String?) {
                assertAdmin()

                val punishmentDur = punishmentDuration?.let { it.DDHHMMSStoDuration() ?: throw CommandException("Illegal punishment duration (specify DD:HH:MM:SS)") }
                val warnDur = expiry?.let { it.DDHHMMSStoDuration() ?: throw CommandException("Illegal warn duration (specify DD:HH:MM:SS)") }

                val punishment = ModerationDB.PunishmentType.entries.singleOrNull { it.name.lowercase() == type.lowercase() } ?: throw CommandException("Illegal punishment type (must be none, mute, kick, or ban)")

                when (punishment) {
                    ModerationDB.PunishmentType.NONE -> if (punishmentDur != null) throw CommandException("Punishment duration cannot be specified for type 'none'!")
                    ModerationDB.PunishmentType.MUTE -> if (punishmentDur == null) throw CommandException("Punishment duration must be specified for type 'mute'!")
                    ModerationDB.PunishmentType.KICK -> if (punishmentDur != null) throw CommandException("Punishment duration cannot be specified for type 'kick'!")
                    ModerationDB.PunishmentType.BAN -> if (punishmentDur != null) throw CommandException("Punishment duration cannot be specified for type 'ban'!")
                }

                val level = bot.database.trnsctn {
                    val g = event.guild!!.m

                    val level = g.warningTypes.maxOfOrNull { it.index }?.plus(1) ?: 1

                    ModerationDB.WarningType.new {
                        this.guild = g
                        this.role = role.idLong
                        this.expiry = warnDur?.toJavaDuration()
                        this.index = level
                        this.punishmentType = punishment.ordinal
                        this.punishmentDuration = punishmentDur?.toJavaDuration()
                    }

                    level
                }

                event.replyEmbeds(embed("Created Warning", description = "Successfully created warning level $level")).ephemeral().await()
            }
        }
    }

    @Command("deafenrole", "Set the guild deafened role")
    suspend inline fun CmdCtx.deafenrole(@P("role", "The new role to set, or null") role: Role?) {
        assertAdmin()

        bot.database.trnsctn {
            val guild = event.guild!!.m
            guild.deafenRole = role?.idLong
        }

        event.replyEmbeds(Commands.embed("Set Role", description = "Successfully set deafen role to ${role?.asMention ?: "none"}", stripPings = false)).ephemeral().await()
    }

    fun launchListeners(bot: Bot) {
        // TODO: figure out a better way to do this than just doing a query every second
        bot.scope.launch {
            while (true) {
                delay(1000L)

                bot.database.trnsctn {
                    val now = Instant.now()
                    // slightly inefficient, but I don't want to figure out how to do null checks in exposed
                    for (punishment in ModerationDB.UserLog.find { ModerationDB.UserLogs.punishmentExpired eq false }) {
                        if (((punishment.timestamp + punishment.punishmentDuration) ?: continue) <= now) {
                            // Make copies of relevant things for thread safety
                            val channelId = punishment.guild.modChannel
                            val userId = punishment.user.discordId
                            val type = punishment.type
                            if (type != ModerationDB.LogType.DEAFEN.ordinal) {
                                bot.log.error("Tried to remove expiring punishment type $type")
                            }
                            val guildId = punishment.guild.discordId
                            val roleId = punishment.guild.deafenRole
                            punishment.punishmentExpired = true
                            launch inner@{
                                val user = bot.jda.getUserById(userId) ?: return@inner
                                val guild = bot.jda.getGuildById(guildId) ?: return@inner
                                val role = guild.getRoleById(roleId ?: return@inner) ?: return@inner

                                try {
                                    guild.removeRoleFromMember(user, role).await()
                                } catch (e: Throwable) {
                                    bot.log.error("Failed to remove deafen role from user:\n${e.stackTraceToString()}")
                                }

                                val channel = bot.jda.getTextChannelById(channelId ?: return@inner) ?: return@inner

                                channel.sendMessageEmbeds(
                                    embed(
                                        "Undeafened",
                                        description = "Undeafened ${user.asMention} (${user.name}) for reason deafen expired",
                                        stripPings = false
                                    )
                                ).await()
                            }
                        }
                    }
                }
            }
        }
    }
}
