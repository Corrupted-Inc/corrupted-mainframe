package com.github.corruptedinc.corruptedmainframe.utils

import com.github.corruptedinc.corruptedmainframe.commands.CommandException
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.stripPings
import com.github.corruptedinc.corruptedmainframe.commands.newcommands.Administration.admin
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB
import com.github.corruptedinc.corruptedmainframe.core.db.ModerationDB.AuditableAction.*
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.awt.Color
import java.time.Instant
import java.time.temporal.TemporalAccessor

data class CommandContext(val bot: Bot, val event: SlashCommandInteractionEvent) {
    @Suppress("LongParameterList")  // They've got default arguments
    fun embed(
        title: String,
        url: String? = null,
        content: List<MessageEmbed.Field> = emptyList(),
        imgUrl: String? = null,
        thumbnail: String? = null,
        author: String? = null,
        authorUrl: String? = null,
        timestamp: TemporalAccessor? = null,
        color: Color? = null,
        description: String? = null,
        stripPings: Boolean = true,
        footer: String? = null
    ): MessageEmbed {
        val builder = EmbedBuilder()
        builder.setTitle(title, url)
        builder.fields.addAll(if (stripPings) content.map {
            MessageEmbed.Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline)
        } else content)
        builder.setImage(imgUrl)
        builder.setThumbnail(thumbnail)
        builder.setAuthor(author, authorUrl)
        builder.setTimestamp(timestamp)
        builder.setColor(color)
        builder.setDescription(if (stripPings) description?.stripPings() else description)
        builder.setFooter(footer)
        return builder.build()
    }

    fun assertPermaAdmin() {
        if (event.user.id !in bot.config.permaAdmins) throw CommandException("no")
    }

    // TODO add to rest of assertions
    fun assertBotAdminT(customError: String = "Missing permissions!") {
        bot.database.trnsctn { assertBotAdmin(customError) }
    }

    context(Transaction)
    fun assertBotAdmin(customError: String = "Missing permissions!") {
        if (!(bot.database.admin(event.user) || event.user.id in bot.config.permaAdmins)) throw CommandException(customError)
    }

    fun assertPermissions(vararg perms: Permission, channel: GuildChannel?) {
        if (bot.database.adminT(event.user) || event.user.id in bot.config.permaAdmins) return
        val member = event.member ?: throw CommandException("Must be run in a server!")
        val p = if (channel != null) member.getPermissions(channel) else member.permissions
        if (!p.containsAll(perms.toList())) throw CommandException("Missing permissions!")
    }

    fun assertAdmin() {
        assertPermissions(Permission.ADMINISTRATOR, channel = null)
    }

    fun ast(value: Boolean, error: String) {
        if (!value) throw CommandException(error)
    }

    context(Transaction)
    fun auditLog(type: ModerationDB.AuditableAction, item: AuditLogItem) {
        auditLog(bot, event.user, event.guild, type, item)
    }

    fun auditLogT(type: ModerationDB.AuditableAction, item: AuditLogItem) {
        bot.database.trnsctn { auditLog(type, item) }
    }

    companion object {
        fun Transaction.auditLog(bot: Bot, user: User, guild: Guild?, type: ModerationDB.AuditableAction, item: AuditLogItem) {
            bot.database.apply {
                val u = user.m
                val g = guild?.m
                ModerationDB.AuditLog.new {
                    this.user = u
                    this.guild = g
                    this.timestamp = Instant.now()
                    this.type = type
                    this.data = ExposedBlob(item.serialize())
                }
            }
        }

        fun auditLogT(bot: Bot, user: User, guild: Guild?, type: ModerationDB.AuditableAction, item: AuditLogItem) {
            bot.database.trnsctn { auditLog(bot, user, guild, type, item) }
        }
    }
}
