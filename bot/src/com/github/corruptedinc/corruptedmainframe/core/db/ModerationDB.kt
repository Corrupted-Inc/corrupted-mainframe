package com.github.corruptedinc.corruptedmainframe.core.db

import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.commands.newcommands.Administration
import com.github.corruptedinc.corruptedmainframe.commands.newcommands.Administration.RoleMenuCommands.RoleData
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.VARCHAR_MAX_LENGTH
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.utils.CmdCtx
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.emoji.Emoji
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.duration
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class ModerationDB(private val database: ExposedDatabase) {
    fun tables() = arrayOf(AutoRoleMessages, AutoRoles, AuditLogs, RoleMenus, RoleMenuItems, UserLogs, WarningTypes)

    object RoleMenus : LongIdTable(name = "role_menus") {
        val guild = reference("guild", ExposedDatabase.GuildMs)
        val name = varchar("name", VARCHAR_MAX_LENGTH)
        val message = long("message")
        val channel = long("channel")
    }

    class RoleMenu(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<RoleMenu>(RoleMenus)
        var guild   by ExposedDatabase.GuildM referencedOn RoleMenus.guild
        var name    by RoleMenus.name
        var message by RoleMenus.message
        var channel by RoleMenus.channel
        val roles   by RoleMenuItem referrersOn RoleMenuItems.menu
    }

    object RoleMenuItems : LongIdTable(name = "role_menu_items") {
        val menu = reference("menu", RoleMenus)
        val description = varchar("description", VARCHAR_MAX_LENGTH).nullable()
        val emote = varchar("emote", VARCHAR_MAX_LENGTH)
        val role = long("role")
        val index = integer("index")
    }

    class RoleMenuItem(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<RoleMenuItem>(RoleMenuItems)
        var menu        by RoleMenu referencedOn RoleMenuItems.menu
        var description by RoleMenuItems.description
        var emote       by RoleMenuItems.emote
        var role        by RoleMenuItems.role
        var index       by RoleMenuItems.index
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
        val emote = varchar("emote", VARCHAR_MAX_LENGTH)
        val role = long("role")
    }

    class AutoRole(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AutoRole>(AutoRoles)
        var message by AutoRoleMessage referencedOn AutoRoles.message
        var emote   by AutoRoles.emote
        var role    by AutoRoles.role
    }

    enum class LogType {
        KICK, BAN, UNBAN, TIMEOUT, UNTIMEOUT, WARN, UNWARN, NOTE
    }

    object UserLogs : LongIdTable(name = "user_logs") {
        val user = reference("user", ExposedDatabase.UserMs).index()
        val guild = reference("guild", ExposedDatabase.GuildMs).index()
        val timestamp = timestamp("timestamp")
        val type = integer("type").index()  // references enum LogType
        val admin = reference("admin", ExposedDatabase.UserMs).nullable()
        val warnLevel = integer("warn_level").nullable()
        val reason = text("reason")
        val punishmentDuration = duration("punishment_duration").nullable()

        val warnExpired = bool("warn_expired").default(false).index()
        val warnExpiry = timestamp("warn_expiry").nullable()

        context(Transaction)
        fun createIndex() {
            exec("CREATE INDEX IF NOT EXISTS user_logs_warn_expiry ON $tableName USING BTREE (warn_expiry);")
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logKick(victim: User, guild: Guild, admin: User, reason: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.KICK.ordinal
                this.admin = admin.m
                this.warnLevel = null
                this.punishmentDuration = null
                this.warnExpired = true
                this.warnExpiry = null
                this.reason = reason
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logBan(victim: User, guild: Guild, admin: User, reason: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.BAN.ordinal
                this.admin = admin.m
                this.punishmentDuration = null
                this.warnExpired = true
                this.warnExpiry = null
                this.warnLevel = null
                this.reason = reason
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logUnban(victim: User, guild: Guild, admin: User, reason: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.UNBAN.ordinal
                this.admin = admin.m
                this.punishmentDuration = null
                this.warnExpired = true
                this.warnExpiry = null
                this.warnLevel = null
                this.reason = reason
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logTimeout(victim: User, guild: Guild, admin: User, duration: Duration, reason: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.TIMEOUT.ordinal
                this.admin = admin.m
                this.punishmentDuration = duration.toJavaDuration()
                this.warnExpired = true
                this.warnExpiry = null
                this.warnLevel = null
                this.reason = reason
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logUnTimeout(victim: User, guild: Guild, admin: User, reason: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.UNTIMEOUT.ordinal
                this.admin = admin.m
                this.punishmentDuration = null
                this.warnExpired = true
                this.warnExpiry = null
                this.warnLevel = null
                this.reason = reason
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }

        context(Transaction, ExposedDatabase, CmdCtx)
        fun logNote(victim: User, guild: Guild, admin: User, note: String, timestamp: Instant = Instant.now()) {
            val log = UserLog.new {
                this.user = victim.m
                this.guild = guild.m
                this.timestamp = timestamp
                this.type = LogType.NOTE.ordinal
                this.admin = admin.m
                this.punishmentDuration = null
                this.warnExpired = true
                this.warnExpiry = null
                this.warnLevel = null
                this.reason = note
            }
            auditLog(AuditableAction.GUILDLOGGED_ACTION, AuditableAction.GuildLoggedActionL(log.id.value))
        }
    }

    class UserLog(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<UserLog>(UserLogs)
        var user               by ExposedDatabase.UserM referencedOn UserLogs.user
        var guild              by ExposedDatabase.GuildM referencedOn UserLogs.guild
        var timestamp          by UserLogs.timestamp
        var type               by UserLogs.type
        var admin              by ExposedDatabase.UserM optionalReferencedOn UserLogs.admin
        var warnLevel          by UserLogs.warnLevel
        var reason             by UserLogs.reason
        var warnExpired        by UserLogs.warnExpired
        var warnExpiry         by UserLogs.warnExpiry
        var punishmentDuration by UserLogs.punishmentDuration

        fun format() = when (LogType.entries[type]) {
                LogType.KICK -> "<t:${timestamp.epochSecond}> Kicked by <@${admin?.discordId}> for reason $reason"
                LogType.BAN -> "<t:${timestamp.epochSecond}> Banned by <@${admin?.discordId}> for reason $reason"
                LogType.UNBAN -> "<t:${timestamp.epochSecond}> Unbanned by <@${admin?.discordId}> for reason $reason"
                LogType.TIMEOUT -> "<t:${timestamp.epochSecond}> Muted by <@${admin?.discordId}> for reason $reason"
                LogType.UNTIMEOUT -> "<t:${timestamp.epochSecond}> Unmuted by <@${admin?.discordId}> for reason $reason"
                LogType.WARN -> "<t:${timestamp.epochSecond}> Warned by <@${admin?.discordId}> (now at level $warnLevel) for reason $reason"
                LogType.UNWARN -> "<t:${timestamp.epochSecond}> Unwarned by <@${admin?.discordId}> (now at level $warnLevel) for reason $reason"
                LogType.NOTE -> "<t:${timestamp.epochSecond}> <@${admin?.discordId}> added a note: $reason"
            }
    }

    enum class PunishmentType {
        NONE, MUTE, KICK, BAN
    }

    object WarningTypes : LongIdTable(name = "warnings") {
        val guild = reference("guild", ExposedDatabase.GuildMs).index()
        val index = integer("index")
        val punishmentType = integer("type")
        val punishmentDuration = duration("punishment_duration").nullable()
        val expiry = duration("expiry").nullable()
        val role = long("role").nullable()
    }

    class WarningType(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<WarningType>(WarningTypes)
        var guild              by ExposedDatabase.GuildM referencedOn WarningTypes.guild
        var index              by WarningTypes.index
        var punishmentType     by WarningTypes.punishmentType
        var punishmentDuration by WarningTypes.punishmentDuration
        var expiry             by WarningTypes.expiry
        var role               by WarningTypes.role
    }

//    enum class UserLog(val constructor: (DataInputStream) -> UserLogEntry) {
//        KICK(::Kick);
//
//        sealed interface UserLogEntry { fun serialize(out: DataOutputStream) }
//        data class Kick(val timestamp: Instant, val userID: Long, val adminID: Long, val reason: String) : UserLogEntry {
//            constructor(inp: DataInputStream) : this(Instant.ofEpochSecond(inp.readLong()), inp.readLong(), inp.readLong(), inp.readUTF())
//            override fun serialize(out: DataOutputStream) { out.writeLong(timestamp.epochSecond); out.writeLong(userID); out.writeLong(adminID); out.writeUTF(reason) }
//        }
//
//        data class Ban(val timestamp: Instant, val userID: Long, val adminID: Long, val reason: String) : UserLogEntry {
//            constructor(inp: DataInputStream) : this(Instant.ofEpochSecond(inp.readLong()), inp.readLong(), inp.readLong(), inp.readUTF())
//            override fun serialize(out: DataOutputStream) { out.writeLong(timestamp.epochSecond); out.writeLong(userID); out.writeLong(adminID); out.writeUTF(reason) }
//        }
//
//        data class Timeout(val timestamp: Instant, val userID: Long, val adminID: Long, val duration: Duration, val reason: String) : UserLogEntry {
//            constructor(inp: DataInputStream) : this(Instant.ofEpochSecond(inp.readLong()), inp.readLong(), inp.readLong(), inp.readLong().seconds, inp.readUTF())
//            override fun serialize(out: DataOutputStream) { out.writeLong(timestamp.epochSecond); out.writeLong(userID); out.writeLong(adminID); out.writeLong(duration.inWholeSeconds); out.writeUTF(reason) }
//        }
//
//        data class Warn(val timestamp: Instant, val userID: Long, val adminID: Long, val duration: Duration, val reason: String) : UserLogEntry {
//            constructor(inp: DataInputStream) : this(Instant.ofEpochSecond(inp.readLong()), inp.readLong(), inp.readLong(), inp.readLong().seconds, inp.readUTF())
//            override fun serialize(out: DataOutputStream) { out.writeLong(timestamp.epochSecond); out.writeLong(userID); out.writeLong(adminID); out.writeLong(duration.inWholeSeconds); out.writeUTF(reason) }
//        }
//    }

    enum class AuditableAction(val constructor: (DataInputStream) -> AuditLogItem) {
        BOT_ADMIN(::BotAdmin), UN_BOT_ADMIN(::UnBotAdmin), GLOBAL_BAN(::GlobalBan), GLOBAL_UNBAN(::GlobalUnBan), SQL(::Sql), RESTART(::Restart),
        PURGE(::Purge),
        REACTIONROLE_ADD(::ReactionroleL), REACTIONROLE_REMOVE(::ReactionroleL), REACTIONROLE_MODIFY(::ReactionroleL),
        REACTIONMENU_CREATE(::ReactionMenuL), REACTIONMENU_DELETE(::ReactionMenuL), REACTIONMENU_ADDITEM(::ReactionMenuL), REACTIONMENU_REMOVEITEM(::ReactionMenuL), REACTIONMENU_EDITITEM(::ReactionMenuL),
        STARBOARD_ADD(::StarboardL), STARBOARD_REMOVE(::StarboardL), STARBOARD_MODIFY(::StarboardL),
        FIGHT_COOLDOWN(::FightCooldown), FIGHT_CATEGORIES(::FightCategories),
        GUILDLOGGED_ACTION(::GuildLoggedActionL)
        ;

        sealed interface AuditLogItem { fun serialize(out: DataOutputStream) fun serialize(): ByteArray { val out = ByteArrayOutputStream(); DataOutputStream(out).use { serialize(it) }; return out.toByteArray() } fun asField(user: Long): String }
        data class BotAdmin(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) ="<@$user> promoted <@$userID> to bot admin" }
        data class UnBotAdmin(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> demoted <@$userID> from bot admin" }
        data class GlobalBan(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> banned <@$userID> from using the bot" }
        data class GlobalUnBan(val userID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(userID) } override fun asField(user: Long) = "<@$user> unbanned <@$userID> from using the bot" }
        data class Sql(val query: String, val commit: Boolean) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readUTF(), inp.readBoolean()) override fun serialize(out: DataOutputStream) { out.writeUTF(query); out.writeBoolean(commit) } override fun asField(user: Long) = "<@$user> ran `${query.replace("\\", "").replace("`", "\\`")}` (commit = $commit)" }
        class Restart() : AuditLogItem { constructor(inp: DataInputStream) : this() override fun serialize(out: DataOutputStream) { } override fun asField(user: Long) = "<@$user> restarted the bot" }
        data class Purge(val channel: Long, val messages: Int) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readInt()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeInt(messages) } override fun asField(user: Long) = "<@$user> purged $messages messages from <#$channel>" }
        data class ReactionroleL(val channel: Long, val message: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeLong(message) } override fun asField(user: Long) = "channel: <#$channel> message ID: $message" }
        data class StarboardL(val channel: Long, val emote: String, val threshold: Int, val xpMultiplier: Double, val name: String) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readUTF(), inp.readInt(), inp.readDouble(), inp.readUTF()) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeUTF(emote); out.writeInt(threshold); out.writeDouble(xpMultiplier); out.writeUTF(name) } constructor(v: ExposedDatabase.Starboard) : this(v.channel, v.emote, v.threshold, v.xpMultiplier, v.name) override fun asField(user: Long) = "channel: <#$channel>, emote: $emote, threshold: $threshold, xp multiplier: $xpMultiplier, name: $name" }
        data class FightCooldown(val cooldown: Duration) : AuditLogItem  { constructor(inp: DataInputStream) : this(inp.readLong().milliseconds) override fun serialize(out: DataOutputStream) { out.writeLong(cooldown.inWholeMilliseconds) } override fun asField(user: Long) = "<@$user> set the fight cooldown to ${cooldown.toIsoString()}" }
        data class FightCategories(val categories: List<Attack.Category>) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readUTF().split(',').map { Attack.Category.valueOf(it) }) override fun serialize(out: DataOutputStream) { out.writeUTF(categories.joinToString(",") { it.name }) } override fun asField(user: Long) = "<@$user> set the fight categories to ${categories.joinToString(",") { it.name.lowercase() }}" }
        data class ReactionMenuL(val channel: Long, val menuID: Long, val name: String, val data: List<RoleData>) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong(), inp.readLong(), inp.readUTF(), Unit.run { val len = inp.readInt(); val data = mutableListOf<RoleData>(); repeat(len) { data.add(RoleData(inp.readLong(), inp.readUTF(), inp.readUTF())) }; data }) override fun serialize(out: DataOutputStream) { out.writeLong(channel); out.writeLong(menuID); out.writeUTF(name); out.writeInt(data.size); data.forEach { out.writeLong(it.role); out.writeUTF(it.description ?: ""); out.writeUTF(it.emoji) } } override fun asField(user: Long) = "channel: <#channel>, menu ID: $menuID, menu name: $name, data: $data" }
        data class GuildLoggedActionL(val logID: Long) : AuditLogItem { constructor(inp: DataInputStream) : this(inp.readLong()) override fun serialize(out: DataOutputStream) { out.writeLong(logID) } override fun asField(user: Long) = "<@$user> performed guild-logged action #$logID" }  // TODO: retrieve action
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
