//package com.github.corruptedinc.corruptedmainframe.core.db
//
//import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
//import com.github.corruptedinc.corruptedmainframe.discord.Bot
//import com.sedmelluq.discord.lavaplayer.track.AudioTrack
//import net.dv8tion.jda.api.entities.Guild
//import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
//import org.jetbrains.exposed.dao.LongEntity
//import org.jetbrains.exposed.dao.LongEntityClass
//import org.jetbrains.exposed.dao.exceptions.EntityNotFoundException
//import org.jetbrains.exposed.dao.id.EntityID
//import org.jetbrains.exposed.dao.id.LongIdTable
//import org.jetbrains.exposed.sql.and
//
//class AudioDB(val database: ExposedDatabase, val bot: Bot) {
//    fun tables() = arrayOf(PlaylistEntries, MusicStates)
//
//    object PlaylistEntries : LongIdTable(name = "playlist_entries") {
//        val state = reference("state", MusicStates)
//        val audioSource = varchar("audioSource", ExposedDatabase.VARCHAR_MAX_LENGTH)
//        val position = long("position")
//    }
//
//    class PlaylistEntry(id: EntityID<Long>) : LongEntity(id) {
//        companion object : LongEntityClass<PlaylistEntry>(PlaylistEntries)
//        var state       by MusicState referencedOn PlaylistEntries.state
//        var audioSource by PlaylistEntries.audioSource
//        var position    by PlaylistEntries.position
//    }
//
//    object MusicStates : LongIdTable(name = "music_states") {
//        val guild = reference("guild", ExposedDatabase.GuildMs)
//        val channel = long("channel")
//        val paused = bool("paused")
//        val volume = integer("volume")
//        val position = long("position")
//        val playlistPos = long("playlistPos")
//    }
//
//    class MusicState(id: EntityID<Long>) : LongEntity(id) {
//        companion object : LongEntityClass<MusicState>(MusicStates)
//        var guild       by ExposedDatabase.GuildM referencedOn MusicStates.guild
//        var channel     by MusicStates.channel
//        var paused      by MusicStates.paused
//        var volume      by MusicStates.volume
//        var position    by MusicStates.position
//        var playlistPos by MusicStates.playlistPos
//        val items       by PlaylistEntry referrersOn PlaylistEntries.state
//    }
//
//    fun musicStates() = database.trnsctn { MusicState.all().toList() }
//
//    fun musicStates(guild: Guild): List<MusicState> {
//        return database.trnsctn { MusicState.find { MusicStates.guild eq guild.m.id }.toList() }
//    }
//
//    fun musicState(guild: Guild, channel: AudioChannel): MusicState? {
//        return database.trnsctn { MusicState.find {
//            (MusicStates.guild eq guild.m.id) and (MusicStates.channel eq channel.idLong)
//        }.firstOrNull() }
//    }
//
//    fun clearMusicState(state: MusicState?) {
//        state ?: return
//        database.trnsctn {
//            try {
//                for (item in state.items) {
//                    item.delete()
//                }
//                state.delete()
//            } catch (e: EntityNotFoundException) {
//                bot.log.error(e.stackTraceToString())
//            }
//        }
//    }
//
//    fun updateMusicState(state: MusicState, position: Long, paused: Boolean, volume: Int, playlistPos: Long) {
//        database.trnsctn {
//            state.position = position
//            state.paused = paused
//            state.volume = volume
//            state.playlistPos = playlistPos
//        }
//    }
//
//    fun updateMusicState(state: MusicState, paused: Boolean, volume: Int, position: Long, playlistPos: Long) {
//        database.trnsctn {
//            state.position = position
//            state.paused = paused
//            state.volume = volume
//            state.playlistPos = playlistPos
//        }
//    }
//
//    fun playlistItem(state: MusicState, position: Long): String? =
//        database.trnsctn {
//            PlaylistEntry.find {
//                (PlaylistEntries.state eq state.id) and (PlaylistEntries.position eq position)
//            }.toList().firstOrNull()?.audioSource
//        }
//
//    fun playlistItems(state: MusicState, range: LongRange): List<String> {
//        return database.trnsctn {
//            PlaylistEntry.find {
//                (PlaylistEntries.state eq state.id) and
//                        (PlaylistEntries.position greaterEq range.first) and
//                        (PlaylistEntries.position lessEq range.last) }.toList().map { it.audioSource }
//        }
//    }
//
//    fun addPlaylistItem(state: MusicState, track: String): Long {
//        return database.trnsctn {
//            val pos = state.items.maxOf { it.position } + 1
//            PlaylistEntry.new {
//                this.state = state
//                this.position = pos
//                this.audioSource = track
//            }
//            pos
//        }
//    }
//
//    fun createMusicState(channel: AudioChannel, tracks: List<String>): MusicState {
//        return database.trnsctn {
//            val oldState = MusicState.find { MusicStates.channel eq channel.idLong }.firstOrNull()
//            if (oldState != null) clearMusicState(oldState)
//
//            val state = MusicState.new {
//                position = 0L
//                playlistPos = 0L
//                @SuppressWarnings("MagicNumber")  // again, I am not making a 100% constant
//                volume = 100
//                paused = false
//                this.guild = channel.guild.m
//                this.channel = channel.idLong
//            }
//
//            for ((index, track) in tracks.withIndex()) {
//                PlaylistEntry.new {
//                    this.state = state
//                    audioSource = track
//                    position = index.toLong()
//                }
//            }
//            return@trnsctn state
//        }
//    }
//
//    fun addPlaylistItems(state: MusicState, tracks: List<AudioTrack>) {
//        database.trnsctn {
//            var pos = (state.items.maxOfOrNull { it.position } ?: 0) + 1
//            for (t in tracks) {
//                PlaylistEntry.new {
//                    this.state = state
//                    this.position = pos++
//                    this.audioSource = t.info.uri
//                }
//            }
//        }
//    }
//
//    fun playlistEntryCount(state: MusicState): Long {
//        return database.trnsctn { state.items.count() }
//    }
//}
