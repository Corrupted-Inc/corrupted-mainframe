package com.github.corruptedinc.corruptedmainframe.core.db

import net.dv8tion.jda.api.entities.*
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.and
import java.time.Instant

class ModerationDB(private val database: ExposedDatabase) {
    fun tables() = arrayOf(Mutes, MutedUserRoles, AutoRoleMessages, AutoRoles)

    object Mutes : LongIdTable(name = "mutes") {
        val user = reference("user", ExposedDatabase.UserMs)
        val start = long("start")
        val end = long("end")
        val guild = reference("guild", ExposedDatabase.GuildMs)
    }

    class Mute(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Mute>(Mutes)

        var user  by ExposedDatabase.UserM referencedOn Mutes.user
        var start by Mutes.start
        var end   by Mutes.end
        var guild by ExposedDatabase.GuildM referencedOn Mutes.guild
        val roles by MutedUserRole referrersOn MutedUserRoles.mute
    }

    object MutedUserRoles : LongIdTable(name = "muted_user_roles") {
        val mute = reference("mute", Mutes)
        val role = long("role")
    }

    class MutedUserRole(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<MutedUserRole>(MutedUserRoles)
        var mute by Mute referencedOn MutedUserRoles.mute
        var role by MutedUserRoles.role
    }

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

    fun addMute(user: User, roles: List<Role>, endTime: Instant, guild: Guild) {
        val userm = database.user(user)
        val guildm = database.guild(guild)

        val previous = mutes(user, guild)
        database.trnsctn {
            for (mute in previous) {
                removeMute(mute)
            }
        }

        val mute = database.trnsctn { Mute.new {
            start = Instant.now().epochSecond; end = endTime.epochSecond; this.user = userm; this.guild = guildm
        } }

        database.trnsctn {
            for (role in roles) {
                MutedUserRole.new { this.mute = mute; this.role = role.idLong }
            }
        }
    }

    fun mutes() = database.trnsctn { Mute.all() }.toList()

    fun expiringMutes(): List<Mute> {
        val now = Instant.now().epochSecond
        return database.trnsctn {
            Mute.find { Mutes.end lessEq now }.toList()
        }
    }

    fun removeMute(mute: Mute) {
        database.trnsctn {
            roles(mute).map { it.delete() }
            mute.delete()
        }
    }

    fun roles(mute: Mute): List<MutedUserRole> {
        return database.trnsctn { mute.roles.toList() }
    }

    //todo transaction?
    fun roleIds(mute: Mute) = mute.roles.map { it.role }

    private fun mutes(user: User, guild: Guild): List<Mute> {
        val userm = database.user(user)
        val guildm = database.guild(guild)
        return database.trnsctn { Mute.find { (Mutes.user eq userm.id) and (Mutes.guild eq guildm.id) }.toList() }
    }

    fun findMute(user: User, guild: Guild): Mute? {
        return mutes(user, guild).firstOrNull()
    }

    fun autoRoleMessages(guild: Guild): List<AutoRoleMessage> {
        return database.trnsctn {
            val guildm = database.guild(guild)
            AutoRoleMessage.find { AutoRoleMessages.guild eq guildm.id }.toList()
        }
    }

    fun autoRole(message: Long, emote: MessageReaction.ReactionEmote): Long? {
        return database.trnsctn {
            val msg = AutoRoleMessage.find { AutoRoleMessages.message eq message }.firstOrNull()?.id
                ?: return@trnsctn null
            AutoRole.find { (AutoRoles.message eq msg) and (AutoRoles.emote eq emote.name) }.firstOrNull()?.role
        }
    }

    fun addAutoRole(message: Message, mapping: Map<String, Long>) {
        database.trnsctn {
            val guildm = database.guild(message.guild)
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
