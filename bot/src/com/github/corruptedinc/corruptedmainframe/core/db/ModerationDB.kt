package com.github.corruptedinc.corruptedmainframe.core.db

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

class ModerationDB(private val database: ExposedDatabase) {
    fun tables() = arrayOf(AutoRoleMessages, AutoRoles)

    object AutoRoleMessages : LongIdTable(name = "auto_role_messages") {
        val guild = reference("guild", ExposedDatabase.GuildMs)
        val message = long("message")
    }

    class AutoRoleMessage(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AutoRoleMessage>(AutoRoleMessages)
        var guild   by ExposedDatabase.GuildM referencedOn AutoRoleMessages.guild
        var message by AutoRoleMessages.message
        val roles   by AutoRole referrersOn AutoRoles.message
    }

    object AutoRoles : LongIdTable(name = "auto_roles") {
        val message = reference("message", AutoRoleMessages)
        val emote = varchar("emote", ExposedDatabase.VARCHAR_MAX_LENGTH)
        val role = long("role")
    }

    class AutoRole(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AutoRole>(AutoRoles)
        var message by AutoRoleMessage referencedOn AutoRoles.message
        var emote   by AutoRoles.emote
        var role    by AutoRoles.role
    }

    fun autoRoleMessages(guild: Guild): List<AutoRoleMessage> {
        return database.trnsctn {
            val guildm = guild.m
            AutoRoleMessage.find { AutoRoleMessages.guild eq guildm.id }.toList()
        }
    }

    fun autoRole(message: Long, emote: Emoji): Long? {
        return database.trnsctn {
            val msg = AutoRoleMessage.find { AutoRoleMessages.message eq message }.firstOrNull()?.id
                ?: return@trnsctn null
            AutoRole.find { AutoRoles.message eq msg }.find { it.emote.run { if (startsWith(':')) this == ":${emote.name}:" else this == emote.name } }?.role
        }
    }

    fun addAutoRole(message: Message, mapping: Map<String, Long>) {
        database.trnsctn {
            val guildm = message.guild.m
            val roleSet = AutoRoleMessage.new {
                this.guild = guildm
                this.message = message.idLong
            }

            for (item in mapping) {
                AutoRole.new {
                    emote = item.key
                    this.message = roleSet
                    this.role = item.value
                }
            }
        }
    }
}
