package com.github.corruptedinc.corruptedmainframe.core.db

import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ModerationDB(private val database: ExposedDatabase) {
    fun tables() = arrayOf(AutoRoleMessages, AutoRoles, AuditLogs)

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

    enum class AuditableAction(val constructor: (DataInputStream) -> AuditLogItem) {
        BOT_ADMIN(::BotAdmin), UN_BOT_ADMIN(::UnBotAdmin), GLOBAL_BAN(::GlobalBan), GLOBAL_UNBAN(::GlobalUnBan), SQL(::Sql), RESTART(::Restart),
        PURGE(::Purge),
        REACTIONROLE_ADD(::ReactionroleL), REACTIONROLE_REMOVE(::ReactionroleL), REACTIONROLE_MODIFY(::ReactionroleL),
        STARBOARD_ADD(::StarboardL), STARBOARD_REMOVE(::StarboardL), STARBOARD_MODIFY(::StarboardL),
        FIGHT_COOLDOWN(::FightCooldown), FIGHT_CATEGORIES(::FightCategories);

        sealed interface AuditLogItem { fun serialize(out: DataOutputStream) fun serialize(): ByteArray { val out = ByteArrayOutputStream(); DataOutputStream(out).use { serialize(it) }; return out.toByteArray() } fun asField(user: Long): String }
        data class BotAdmin(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) ="<@$user> promoted <@$userID> to bot admin" }
        data class UnBotAdmin(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> demoted <@$userID> from bot admin" }
        data class GlobalBan(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> banned <@$userID> from using the bot" }
        data class GlobalUnBan(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> unbanned <@$userID> from using the bot" }
        data class Sql(val query: String) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readUTF()) override fun serialize(out: DataOutputStream) { out.writeUTF(query) } override fun asField(user: Long) = "<@$user> ran `${query.replace("\\", "").replace("`", "\\`")}`" }
        class Restart() : AuditLogItem { constructor(inp: DataInputStream) : this() override fun serialize(out: DataOutputStream) { } override fun asField(user: Long) = "<@$user> restarted the bot" }
        data class Purge(val channel: Long, val messages: Int) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readInt()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeInt(messages) } override fun asField(user: Long) = "<@$user> purged $messages messages from <#$channel>" }
        data class ReactionroleL(val channel: Long, val message: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeLong(message) } override fun asField(user: Long) = "channel: <#$channel> message ID: $message" }
        data class StarboardL(val channel: Long, val emote: String, val threshold: Int, val xpMultiplier: Double, val name: String) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readUTF(), inp.readInt(), inp.readDouble(), inp.readUTF()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeUTF(emote); out.writeInt(threshold); out.writeDouble(xpMultiplier); out.writeUTF(name) } constructor(v: ExposedDatabase.Starboard) : this(v.channel, v.emote, v.threshold, v.xpMultiplier, v.name) override fun asField(user: Long) = "channel: <#$channel>, emote: $emote, threshold: $threshold, xp multiplier: $xpMultiplier, name: $name" }
        data class FightCooldown(val cooldown: Duration) : AuditLogItem  { constructor(inp: DataInputStream) : this(inp.readLong().milliseconds) override fun serialize(out: DataOutputStream) { out.writeLong(cooldown.inWholeMilliseconds) } override fun asField(user: Long) = "<@$user> set the fight cooldown to ${cooldown.toIsoString()}" }
        data class FightCategories(val categories: List<Attack.Category>) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readUTF().split(',').map { Attack.Category.valueOf(it) }) override fun serialize(out: DataOutputStream) { out.writeUTF(categories.joinToString(",") { it.name }) } override fun asField(user: Long) = "<@$user> set the fight categories to ${categories.joinToString(",") { it.name.lowercase() }}" }
    }

    object AuditLogs : LongIdTable(name = "audit_logs") {
        val user = reference("user", ExposedDatabase.UserMs).index()
        val guild = reference("guild", ExposedDatabase.GuildMs).nullable().index()
        val timestamp = timestamp("timestamp")
        val type = enumeration("type", AuditableAction::class)
        val data = blob("data")
    }

    class AuditLog(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AuditLog>(AuditLogs)

        var user      by ExposedDatabase.UserM referencedOn AuditLogs.user
        var guild     by ExposedDatabase.GuildM optionalReferencedOn AuditLogs.guild
        var timestamp by AuditLogs.timestamp
        var type      by AuditLogs.type
        var data      by AuditLogs.data
        var deserialized get() = DataInputStream(data.bytes.inputStream()).use { type.constructor(it) }
            set(value) { data = ExposedBlob(value.serialize()) }
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
