package core.db

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

class ExposedDatabase(val db: Database) {
    init {
        transaction(db) {
            SchemaUtils.create(GuildMs, UserMs, GuildUsers, Mutes, MutedUserRoles)
        }
    }

    object GuildMs : LongIdTable(name = "guilds") {
        val discordId = long("discord_id").index(isUnique = true)
        val prefix = varchar("prefix", 64)
    }

    class GuildM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<GuildM>(GuildMs)
        var discordId by GuildMs.discordId
        var prefix    by GuildMs.prefix
    }

    object UserMs : LongIdTable(name = "users") {
        val discordId = long("discord_id").index(isUnique = true)
        val botAdmin  = bool("admin")
    }

    class UserM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<UserM>(UserMs)

        var discordId by UserMs.discordId
        var botAdmin  by UserMs.botAdmin
        var guilds    by GuildM via GuildUsers
    }

    object GuildUsers : Table() {
        val guild = reference("guild", GuildMs)
        val user = reference("user", UserMs)

        override val primaryKey = PrimaryKey(guild, user, /*name = "PK_GuildUsers_swf_act"*/)
    }

    object Mutes : LongIdTable(name = "mutes") {
        val user = reference("user", UserMs)
        val start = long("start")
        val end = long("end")
        val guild = reference("guild", GuildMs)
    }

    class Mute(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Mute>(Mutes)

        var user  by UserM referencedOn Mutes.user
        var start by Mutes.start
        var end   by Mutes.end
        var guild by GuildM referencedOn Mutes.guild
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

    fun user(user: User) = transaction(db) { UserM.find { UserMs.discordId eq user.idLong }.firstOrNull() ?: UserM.new { discordId = user.idLong; botAdmin = false } }

    fun guild(guild: Guild): GuildM {
        return transaction(db) { GuildM.find { GuildMs.discordId eq guild.idLong }.firstOrNull() ?: GuildM.new { discordId = guild.idLong; prefix = "!" } }
    }

    fun users() = transaction(db) { UserM.all().toList() }

    fun guilds() = transaction(db) { GuildM.all().toList() }

    fun addUser(user: User, guilds: List<Guild>): UserM {
        val userm = transaction(db) { UserM.new { botAdmin = false; discordId = user.idLong } }
        val guildms = mutableListOf<GuildM>()
        for (guild in guilds) {
            guildms.add(guild(guild))
        }
        transaction(db) { userm.guilds = SizedCollection(guildms) }
        return userm
    }

    fun addGuild(guild: Guild, users: List<User>): GuildM {
        val guildm = transaction(db) { GuildM.new { prefix = "!"; discordId = guild.idLong } }
        val userms = mutableListOf<UserM>()
        for (user in users) {
            userms.add(user(user))
        }
        transaction(db) {
            for (userm in userms) {
                userm.guilds = SizedCollection(userm.guilds.plus(guildm))
            }
        }
        return guildm
    }

    fun addLink(guild: Guild, user: User) {
        val guildm = guild(guild)
        val userm = user(user)
        transaction(db) {
            if (!userm.guilds.contains(guildm)) {
                userm.guilds = SizedCollection(userm.guilds.plus(guildm))
            }
        }
    }

    fun addMute(user: User, roles: List<Role>, endTime: Instant, guild: Guild) {
        val userm = user(user)
        val guildm = guild(guild)

        val previous = mutes(user, guild)
        transaction {
            for (mute in previous) {
                removeMute(mute)
            }
        }

        val mute = transaction(db) { Mute.new { start = Instant.now().epochSecond; end = endTime.epochSecond; this.user = userm; this.guild = guildm } }

        transaction(db) {
            for (role in roles) {
                MutedUserRole.new { this.mute = mute; this.role = role.idLong }
            }
        }
    }

    fun mutes() = transaction(db) { Mute.all() }.toList()

    fun expiringMutes(): List<Mute> {
        val now = Instant.now().epochSecond
        return transaction(db) {
            Mute.find { Mutes.end lessEq now }.toList()
        }
    }

    fun removeMute(mute: Mute) {
        transaction(db) {
            roles(mute).map { it.delete() }
            mute.delete()
        }
    }

    fun roles(mute: Mute): List<MutedUserRole> {
        return transaction(db) { mute.roles.toList() }
    }

    //todo: transaction?
    fun roleIds(mute: Mute) = mute.roles.map { it.role }

    private fun mutes(user: User, guild: Guild): List<Mute> {
        val userm = user(user)
        val guildm = guild(guild)
        return transaction(db) { Mute.find { (Mutes.user eq userm.id) and (Mutes.guild eq guildm.id) }.toList() }
    }

    fun findMute(user: User, guild: Guild): Mute? {
        return mutes(user, guild).firstOrNull()
    }
}
