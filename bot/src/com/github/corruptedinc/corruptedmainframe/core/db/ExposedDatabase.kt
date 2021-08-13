package com.github.corruptedinc.corruptedmainframe.core.db

import net.dv8tion.jda.api.entities.*
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class ExposedDatabase(val db: Database) {
    val audioDB = AudioDB(this)
    val moderationDB = ModerationDB(this)

    init {
        trnsctn {
            @Suppress("SpreadOperator")
            SchemaUtils.createMissingTablesAndColumns(
                GuildMs,
                UserMs,
                GuildUsers,
                *moderationDB.tables(),
                *audioDB.tables(),
                Points,
                Reminders
            )
        }
    }

    companion object {
        const val VARCHAR_MAX_LENGTH = 255  // beginning to hate code analysis
        private const val MAX_PREFIX_LENGTH = 64
    }

    object GuildMs : LongIdTable(name = "guilds") {
        val discordId = long("discord_id").index(isUnique = true)
        val prefix = varchar("prefix", MAX_PREFIX_LENGTH)
    }

    class GuildM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<GuildM>(GuildMs)
        var discordId by GuildMs.discordId
        var prefix    by GuildMs.prefix
    }

    object UserMs : LongIdTable(name = "users") {
        val discordId = long("discord_id").index(isUnique = true)
        val botAdmin  = bool("admin")
        val banned    = bool("banned").default(false)
    }

    class UserM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<UserM>(UserMs)

        var discordId by UserMs.discordId
        var botAdmin  by UserMs.botAdmin
        var banned    by UserMs.banned
        var guilds    by GuildM via GuildUsers
        val reminders by Reminder referrersOn Reminders.user
    }

    object Points : LongIdTable(name = "points") {
        val user   = reference("user", UserMs)
        val guild  = reference("guild", GuildMs)
        val pnts = double("points")
        val popups = bool("popups").default(false)
    }

    class Point(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Point>(Points)

        var user   by UserM referencedOn Points.user
        var guild  by GuildM referencedOn Points.guild
        var points by Points.pnts
        var popups by Points.popups
    }

    //todo this isn't needed, remove
    object GuildUsers : Table() {
        val guild = reference("guild", GuildMs)
        val user = reference("user", UserMs)

        override val primaryKey = PrimaryKey(guild, user, /*name = "PK_GuildUsers_swf_act"*/)
    }

    object Reminders : LongIdTable(name = "reminders") {
        val messageId = long("messageid")
        val user = reference("user", UserMs)
        val channelId = long("channelid")
        val time = datetime("time")
        val text = text("text")
    }

    class Reminder(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Reminder>(Reminders)
        var messageId by Reminders.messageId
        var user      by UserM referencedOn Reminders.user
        var channelId by Reminders.channelId
        var time      by Reminders.time
        var text      by Reminders.text
    }

    fun user(user: User) = transaction(db) { UserM.find {
        UserMs.discordId eq user.idLong
    }.firstOrNull() ?: UserM.new { discordId = user.idLong; botAdmin = false; banned = false } }

    fun guild(guild: Guild): GuildM {
        return transaction(db) { GuildM.find {
            GuildMs.discordId eq guild.idLong
        }.firstOrNull() ?: GuildM.new { discordId = guild.idLong; prefix = "!" } }
    }

    fun users() = transaction(db) { UserM.all().toList() }

    fun guilds() = transaction(db) { GuildM.all().toList() }

    fun points(user: User, guild: Guild): Double = trnsctn {
        val u = user(user)
        val g = guild(guild)
        val found = Point.find { (Points.guild eq g.id) and (Points.user eq u.id) }.firstOrNull() ?: Point.new {
            this.guild = g
            this.user = u
            this.points = 0.0
        }

        return@trnsctn found.points
    }

    fun popups(user: User, guild: Guild): Boolean = trnsctn {
        val u = user(user)
        val g = guild(guild)
        val found = Point.find { (Points.guild eq g.id) and (Points.user eq u.id) }.firstOrNull() ?: Point.new {
            this.guild = g
            this.user = u
            this.points = 0.0
        }

        return@trnsctn found.popups
    }

    fun setPopups(user: User, guild: Guild, popups: Boolean) {
        trnsctn {
            val u = user(user)
            val g = guild(guild)

            val found = Point.find { (Points.guild eq g.id) and (Points.user eq u.id) }.firstOrNull() ?: Point.new {
                this.guild = g
                this.user = u
                this.points = 0.0
            }

            found.popups = popups
        }
    }

    fun addPoints(user: User, guild: Guild, points: Double): Double {
        return trnsctn {
            val u = user(user)
            val g = guild(guild)

            val found = Point.find { (Points.guild eq g.id) and (Points.user eq u.id) }.firstOrNull() ?: Point.new {
                this.guild = g
                this.user = u
                this.points = 0.0
            }

            found.points += points

            found.points
        }
    }

    fun setPoints(user: User, guild: Guild, points: Double) {
        trnsctn {
            val u = user(user)
            val g = guild(guild)

            val found = Point.find { (Points.guild eq g.id) and (Points.user eq u.id) }.firstOrNull() ?: Point.new {
                this.guild = g
                this.user = u
                this.points = 0.0
            }

            found.points = points
        }
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

    fun ban(user: User) {
        trnsctn {
            val userm = user(user)
            userm.banned = true
        }
    }

    fun unban(user: User) {
        trnsctn {
            val userm = user(user)
            userm.banned = false
        }
    }

    fun banned(user: User) = user(user).banned

    fun expiringRemindersNoTransaction(): List<Reminder> {
        val now = LocalDateTime.now()
        return Reminder.find { Reminders.time lessEq now }.toList()
    }

    fun <T> trnsctn(block: Transaction.() -> T): T = transaction(db, block)
}
