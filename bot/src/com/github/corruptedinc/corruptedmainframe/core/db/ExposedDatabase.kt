package com.github.corruptedinc.corruptedmainframe.core.db

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.entities.*
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.datetime
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDateTime

class ExposedDatabase(val db: Database) {
    init {
        trnsctn {
            SchemaUtils.createMissingTablesAndColumns(
                GuildMs,
                UserMs,
                GuildUsers,
                Mutes,
                MutedUserRoles,
                MusicStates,
                PlaylistEntries,
                AutoRoleMessages,
                AutoRoles,
                Points,
                Reminders
            )
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
        val points = double("points")
        val popups = bool("popups").default(false)
    }

    class Point(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<Point>(Points)

        var user   by UserM referencedOn Points.user
        var guild  by GuildM referencedOn Points.guild
        var points by Points.points
        var popups by Points.popups
    }

    //todo: this isn't needed, remove
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

    object PlaylistEntries : LongIdTable(name = "playlist_entries") {
        val state = reference("state", MusicStates)
        val audioSource = varchar("audioSource", 255)
        val position = long("position")
    }

    class PlaylistEntry(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<PlaylistEntry>(PlaylistEntries)
        var state       by MusicState referencedOn PlaylistEntries.state
        var audioSource by PlaylistEntries.audioSource
        var position    by PlaylistEntries.position
    }

    object MusicStates : LongIdTable(name = "music_states") {
        val guild = reference("guild", GuildMs)
        val channel = long("channel")
        val paused = bool("paused")
        val volume = integer("volume")
        val position = long("position")
        val playlistPos = long("playlistPos")
    }

    class MusicState(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<MusicState>(MusicStates)
        var guild       by GuildM referencedOn MusicStates.guild
        var channel     by MusicStates.channel
        var paused      by MusicStates.paused
        var volume      by MusicStates.volume
        var position    by MusicStates.position
        var playlistPos by MusicStates.playlistPos
        val items       by PlaylistEntry referrersOn PlaylistEntries.state
    }

    object AutoRoleMessages : LongIdTable(name = "auto_role_messages") {
        val guild = reference("guild", GuildMs)
        val message = long("message")
    }

    class AutoRoleMessage(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AutoRoleMessage>(AutoRoleMessages)
        var guild   by GuildM referencedOn AutoRoleMessages.guild
        var message by AutoRoleMessages.message
        val roles   by AutoRole referrersOn AutoRoles.message
    }

    object AutoRoles : LongIdTable(name = "auto_roles") {
        val message = reference("message", AutoRoleMessages)
        val emote = varchar("emote", 255)
        val role = long("role")
    }

    class AutoRole(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<AutoRole>(AutoRoles)
        var message by AutoRoleMessage referencedOn AutoRoles.message
        var emote   by AutoRoles.emote
        var role    by AutoRoles.role
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

    fun user(user: User) = transaction(db) { UserM.find { UserMs.discordId eq user.idLong }.firstOrNull() ?: UserM.new { discordId = user.idLong; botAdmin = false; banned = false } }

    fun guild(guild: Guild): GuildM {
        return transaction(db) { GuildM.find { GuildMs.discordId eq guild.idLong }.firstOrNull() ?: GuildM.new { discordId = guild.idLong; prefix = "!" } }
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

    fun addUser(user: User, guilds: List<Guild>): UserM {
        val userm = transaction(db) { UserM.new { botAdmin = false; discordId = user.idLong; banned = false } }
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
        transaction(db) {
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

    fun musicStates() = transaction(db) { MusicState.all().toList() }

    fun musicStates(guild: Guild): List<MusicState> {
        val guildm = guild(guild)
        return transaction(db) { MusicState.find { MusicStates.guild eq guildm.id }.toList() }
    }

    fun musicState(guild: Guild, channel: VoiceChannel): MusicState? {
        val guildm = guild(guild)
        return transaction(db) { MusicState.find { (MusicStates.guild eq guildm.id) and (MusicStates.channel eq channel.idLong) }.firstOrNull() }
    }

    fun clearMusicState(state: MusicState) {
        transaction(db) {
            for (item in state.items) {
                item.delete()
            }
            state.delete()
        }
    }

    fun updateMusicState(state: MusicState, position: Long, paused: Boolean, volume: Int, playlistPos: Long) {
        transaction(db) {
            state.position = position
            state.paused = paused
            state.volume = volume
            state.playlistPos = playlistPos
        }
    }

    fun updateMusicState(state: MusicState, paused: Boolean, volume: Int, position: Long, playlistPos: Long) {
        transaction(db) {
            state.position = position
            state.paused = paused
            state.volume = volume
            state.playlistPos = playlistPos
        }
    }

    fun playlistItem(state: MusicState, position: Long): String? =
        transaction(db) {
            PlaylistEntry.find { (PlaylistEntries.state eq state.id) and (PlaylistEntries.position eq position) }.toList().firstOrNull()?.audioSource
        }

    fun playlistItems(state: MusicState, range: LongRange): List<String> {
        return transaction(db) {
            PlaylistEntry.find {
                (PlaylistEntries.state eq state.id) and
                        (PlaylistEntries.position greaterEq range.first) and
                        (PlaylistEntries.position lessEq range.last) }.toList().map { it.audioSource }
        }
    }

    fun addPlaylistItem(state: MusicState, track: String): Long {
        return transaction(db) {
            val pos = state.items.maxOf { it.position } + 1
            PlaylistEntry.new {
                this.state = state
                this.position = pos
                this.audioSource = track
            }
            pos
        }
    }

    fun createMusicState(channel: VoiceChannel, tracks: List<String>): MusicState {
        val guild = guild(channel.guild)
        return transaction(db) {
            val state = MusicState.new {
                position = 0L
                playlistPos = 0L
                volume = 100
                paused = false
                this.guild = guild
                this.channel = channel.idLong
            }

            for ((index, track) in tracks.withIndex()) {
                PlaylistEntry.new {
                    this.state = state
                    audioSource = track
                    position = index.toLong()
                }
            }
            return@transaction state
        }
    }

    fun addPlaylistItems(state: MusicState, tracks: List<AudioTrack>) {
        transaction(db) {
            var pos = state.items.maxOf { it.position } + 1
            for (t in tracks) {
                PlaylistEntry.new {
                    this.state = state
                    this.position = pos++
                    this.audioSource = t.info.uri
                }
            }
        }
    }

    fun playlistEntryCount(state: MusicState): Long {
        return trnsctn { state.items.count() }
    }

    fun autoRoleMessages(guild: Guild): List<AutoRoleMessage> {
        return transaction(db) {
            val guildm = guild(guild)
            AutoRoleMessage.find { AutoRoleMessages.guild eq guildm.id }.toList()
        }
    }

    fun autoRole(message: Long, emote: MessageReaction.ReactionEmote): Long? {
        return trnsctn {
            val msg = AutoRoleMessage.find { AutoRoleMessages.message eq message }.firstOrNull()?.id ?: return@trnsctn null
            AutoRole.find { (AutoRoles.message eq msg) and (AutoRoles.emote eq emote.name) }.firstOrNull()?.role
        }
    }

    fun addAutoRole(message: Message, mapping: Map<String, Long>) {
        trnsctn {
            val guildm = guild(message.guild)
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

    fun reminders(user: User) = trnsctn { user(user).reminders.toList() }

    fun expiringRemindersNoTransaction(): List<Reminder> {
        val now = LocalDateTime.now()
        return Reminder.find { Reminders.time lessEq now }.toList()
    }

    fun <T> trnsctn(block: Transaction.() -> T): T = transaction(db, block)
}
