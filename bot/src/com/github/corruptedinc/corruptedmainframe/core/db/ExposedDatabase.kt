package com.github.corruptedinc.corruptedmainframe.core.db

import com.github.corruptedinc.corruptedmainframe.commands.fights.Attack
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.CommandRuns.index
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import net.dv8tion.jda.api.entities.*
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.duration
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalUnsignedTypes::class)
class ExposedDatabase(val db: Database, bot: Bot) {
    val audioDB = AudioDB(this, bot)
    val moderationDB = ModerationDB(this)
    val frcDB = FRCDB(this, bot)

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
                Reminders,
                StarredMessages,
                CommandRuns,
                *frcDB.tables(),
                Autoreactions
            )
        }
    }

    companion object {
        const val VARCHAR_MAX_LENGTH = 255  // beginning to hate code analysis
        private const val MAX_PREFIX_LENGTH = 64
        const val STARBOARD_THRESHOLD_DEFAULT = 7
    }

    object GuildMs : LongIdTable(name = "guilds") {
        val discordId = long("discord_id").uniqueIndex()
        val prefix = varchar("prefix", MAX_PREFIX_LENGTH)
        val currentlyIn = bool("currently_in").default(true)
        val starboardChannel = long("starboard_channel").nullable()
        val starboardThreshold = integer("starboard_threshold").default(STARBOARD_THRESHOLD_DEFAULT)
        val starboardReaction = varchar("starboard_reaction", VARCHAR_MAX_LENGTH).nullable()
        val levelsEnabled = bool("levels_enabled").default(true)
        val fightCategories = ulong("fight_categories").default(Attack.Category.GENERAL.bitmask)
        val fightCooldown = duration("fight_cooldown").default(Duration.of(5, ChronoUnit.MINUTES))
    }

    class GuildM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<GuildM>(GuildMs)
        var discordId          by GuildMs.discordId
        var prefix             by GuildMs.prefix
        var currentlyIn        by GuildMs.currentlyIn
        var starboardChannel   by GuildMs.starboardChannel
        var starboardThreshold by GuildMs.starboardThreshold
        var starboardReaction  by GuildMs.starboardReaction
        var levelsEnabled      by GuildMs.levelsEnabled
        var fightCategories    by GuildMs.fightCategories
        var fightCooldown      by GuildMs.fightCooldown
        val starredMessages    by StarredMessage.referrersOn(StarredMessages.guild)
        val crawlJobs          by ImageHashJob.referrersOn(ImageHashJobs.guild)
        val autoRoles          by ModerationDB.AutoRoleMessage.referrersOn(ModerationDB.AutoRoleMessages.guild)

        val fightCategoryList = object : AbstractMutableSet<Attack.Category>() {
            override val size get() = Attack.Category.values().count { fightCategories and it.bitmask != 0UL }

            override fun iterator(): MutableIterator<Attack.Category> = Attack.Category.values().filter { fightCategories and it.bitmask != 0UL }.toMutableList().iterator()
            override fun add(element: Attack.Category): Boolean { val original = fightCategories; fightCategories = fightCategories or element.bitmask; return (element.bitmask and original) == 0UL }
            override fun remove(element: Attack.Category): Boolean { val original = fightCategories; fightCategories = fightCategories and (element.bitmask.inv()); return (element.bitmask and original) != 0UL }
        }
    }

    object UserMs : LongIdTable(name = "users") {
        val discordId = long("discord_id").uniqueIndex()
        val botAdmin  = bool("admin")
        val banned    = bool("banned").default(false)
        val timezone  = text("timezone").default("UTC")
    }

    class UserM(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<UserM>(UserMs)

        var discordId by UserMs.discordId
        var botAdmin  by UserMs.botAdmin
        var banned    by UserMs.banned
        var timezone  by UserMs.timezone
        var guilds    by GuildM via GuildUsers
        val reminders by Reminder referrersOn Reminders.user
    }

    object Points : LongIdTable(name = "points") {
        val user = reference("user", UserMs).index()
        val guild = reference("guild", GuildMs)
        val pnts = double("points")
        val popups = bool("popups").default(true)
        val fightCooldown = timestamp("fight_cooldown").default(Instant.EPOCH)
        val rank = long("rank").default(0)
    }

    class Point(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Point>(Points)

        var user          by UserM referencedOn Points.user
        var guild         by GuildM referencedOn Points.guild
        var points        by Points.pnts
        var popups        by Points.popups
        var fightCooldown by Points.fightCooldown
        var rank          by Points.rank
    }

    //todo this isn't needed, remove
    object GuildUsers : Table() {
        val guild = reference("guild", GuildMs)
        val user = reference("user", UserMs)

        override val primaryKey = PrimaryKey(guild, user, /*name = "PK_GuildUsers_swf_act"*/)
    }

    object Reminders : LongIdTable(name = "reminders") {
        val user = reference("user", UserMs).index()
        val channelId = long("channelid")
        val time = datetime("time")
        val text = varchar("text", VARCHAR_MAX_LENGTH)
    }

    class Reminder(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Reminder>(Reminders)
        var user      by UserM referencedOn Reminders.user
        var channelId by Reminders.channelId
        var time      by Reminders.time
        var text      by Reminders.text
    }

    object StarredMessages : LongIdTable(name = "starred_messages") {
        val guild = reference("guild", GuildMs).index()
        val messageID = long("message_id")
    }

    class StarredMessage(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<StarredMessage>(StarredMessages)
        var guild     by StarredMessages.guild
        var messageID by StarredMessages.messageID
    }

    object CommandRuns : LongIdTable(name = "command_runs") {
        val guild = reference("guild", GuildMs)
        val timestamp = timestamp("timestamp")
        val user = reference("user", UserMs)
        val command = varchar("command", VARCHAR_MAX_LENGTH)
        val millis = long("millis")
    }

    class CommandRun(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<CommandRun>(CommandRuns)

        var guild     by CommandRuns.guild
        var timestamp by CommandRuns.timestamp
        var user      by CommandRuns.user
        var command   by CommandRuns.command
        var millis    by CommandRuns.millis
    }

    object ImageHashes : LongIdTable(name = "image_hashes") {
        val guild = reference("guild", GuildMs)
        val channel = long("channel")
        val message = long("message")
        val hash = ulong("hash").index()
        val version = short("version").default(0)
        val embedNumber = byte("embed_number")
    }

    class ImageHash(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<ImageHash>(ImageHashes)

        var guild       by ImageHashes.guild
        var channel     by ImageHashes.channel
        var message     by ImageHashes.message
        var hash        by ImageHashes.hash.index()
        var version     by ImageHashes.version
        var embedNumber by ImageHashes.embedNumber
    }

    object ImageHashJobs : LongIdTable(name = "image_hash_jobs") {
        val guild = reference("guild", GuildMs).index()
        val channel = long("channel").index()
        val lastMessage = long("last_message")
        val done = bool("done")
    }

    class ImageHashJob(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<ImageHashJob>(ImageHashJobs)

        var guild       by ImageHashJobs.guild
        var channel     by ImageHashJobs.channel
        var lastMessage by ImageHashJobs.lastMessage
        var done        by ImageHashJobs.done
    }

    object Quotes : LongIdTable(name = "quotes") {
        val guild = reference("guild", GuildMs)
        val user = reference("user", UserMs)
        val content = text("content")
    }

    object Autoreactions : LongIdTable(name = "autoreactions") {
        val guild = reference("guild", GuildMs).index()
        val user = reference("user", UserMs).index()
        val reaction = varchar("reaction", VARCHAR_MAX_LENGTH)
    }

    class Autoreaction(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Autoreaction>(Autoreactions)

        var guild    by GuildM referencedOn Autoreactions.guild
        var user     by UserM referencedOn Autoreactions.user
        var reaction by Autoreactions.reaction
    }

    fun user(user: User) = user(user.idLong)

    fun user(user: Long) = transaction(db) { UserM.find {
        UserMs.discordId eq user
    }.firstOrNull() ?: UserM.new { discordId = user; botAdmin = false; banned = false } }

    fun guild(guild: Guild) = guild(guild.idLong)

    fun guild(guild: Long): GuildM {
        return transaction(db) { GuildM.find {
            GuildMs.discordId eq guild
        }.firstOrNull() ?: GuildM.new { discordId = guild; prefix = "!" } }
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
                this.popups = true
            }

            found.points += points
            found.points = found.points.coerceAtLeast(0.0)

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

    fun guildCount() = trnsctn { GuildM.count(Op.build { GuildMs.currentlyIn eq true }) }

    fun commandsRun(start: Instant, end: Instant) = trnsctn {
        CommandRun.count(Op.build { CommandRuns.timestamp.between(start, end) })
    }

    fun <T> trnsctn(block: Transaction.() -> T): T = transaction(db, block)
}
