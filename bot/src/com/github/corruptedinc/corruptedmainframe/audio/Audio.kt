package com.github.corruptedinc.corruptedmainframe.audio

import com.github.corruptedinc.corruptedmainframe.core.db.AudioDB
import com.github.corruptedinc.corruptedmainframe.core.db.AudioDB.PlaylistEntries
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.google.common.cache.LoadingCache
import com.sedmelluq.discord.lavaplayer.track.*
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.VoiceChannel
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceMoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class Audio(val bot: Bot) {
    val playerManager = DefaultAudioPlayerManager()
    val currentlyPlaying = mutableSetOf<AudioState>()

    companion object {
        private const val BUSYWAITING_DELAY = 50L
        private const val MAX_CACHE_SIZE = 256L
        private const val MAX_METADATA_CACHE_SIZE = 1024L
        private const val CACHE_RET_MINUTES = 10L
        private const val METADATA_CACHE_RET_MINUTES = 60L
    }

    init {
        bot.jda.listener<ReadyEvent> { _ ->
            for (item in bot.database.audioDB.musicStates()) {
                // Because sharding, only restart playing for guilds that it's actually connected to
                if (bot.jda.guilds.any { it.idLong == bot.database.trnsctn { item.guild.discordId } }) {
                    currentlyPlaying.add(AudioState(item))
                }
            }
        }

        bot.jda.listener<GuildVoiceMoveEvent> { event ->
            if (event.entity.user == bot.jda.selfUser) {
                val state = currentlyPlaying.find { it.channelId == event.channelLeft.idLong }
                if (state == null) {
                    event.guild.audioManager.closeAudioConnection()
                    return@listener
                }
                state.channelId = event.channelJoined.idLong
            }
        }
    }

    private val cache: Cache<String, List<AudioTrack>> = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_SIZE)
        .expireAfterWrite(CACHE_RET_MINUTES, TimeUnit.MINUTES).build(object : CacheLoader<String, List<AudioTrack>>() {
        override fun load(key: String): List<AudioTrack> {
            return runBlocking { load(key, nocache = true) }
        }
    })

    private val metadataCache: LoadingCache<String, AudioTrackInfo> = CacheBuilder.newBuilder()
        .maximumSize(MAX_METADATA_CACHE_SIZE).expireAfterWrite(METADATA_CACHE_RET_MINUTES, TimeUnit.MINUTES)
        .build(object : CacheLoader<String, AudioTrackInfo>() {
            override fun load(key: String): AudioTrackInfo {
                return runBlocking { load(key, search = false) }.first().info
            }
        })

    fun metadata(source: String?): AudioTrackInfo? = if (source == null) null else metadataCache[source]

    // TODO restructure into a function that takes cache into account and one that doesn't
    @SuppressWarnings("ReturnCount")
    suspend fun load(source: String?, search: Boolean = false, nocache: Boolean = false): List<AudioTrack> {
        source ?: return emptyList()
        if (!nocache) {
            val gotten = cache.getIfPresent(source)
            if (gotten != null) return gotten.map { it.makeClone() }
        }
        var done = false
        var track: AudioPlaylist? = null
        bot.audio.playerManager.loadItem(if (search) "ytsearch:$source" else source, object : AudioLoadResultHandler {
            override fun loadFailed(exception: FriendlyException?) {
                done = true
                track = null
            }

            override fun trackLoaded(track1: AudioTrack?) {
                done = true
                track = BasicAudioPlaylist("playlist", listOf(track1), track1, false)
                val info = track1?.info
                if (info != null) metadataCache.put(source, info)
            }

            override fun noMatches() {
                done = true
                track = null
            }

            override fun playlistLoaded(playlist: AudioPlaylist?) {
                done = true
                track = playlist
            }
        })

        // TODO make infinite delay job and cancel it when done
        while (!done) {
            delay(BUSYWAITING_DELAY)
        }
        var tracks = track?.tracks ?: emptyList()
        if (search) {
            tracks = listOfNotNull(tracks.firstOrNull())
        }
        cache.put(source, tracks)
        return tracks
    }

    fun createState(channel: VoiceChannel, player: AudioPlayerSendHandler, playlist: MutableList<AudioTrack>) =
        AudioState(channel, player, playlist, 0L)

    fun gracefulShutdown() {
        for (state in currentlyPlaying) {
            state.updateDatabase()
        }
    }

    inner class AudioState {
        val channel get() = bot.jda.getVoiceChannelById(channelId)
        private val player: AudioPlayerSendHandler
        var playlistPos: Long
        val playlistCount get() = bot.database.audioDB.playlistEntryCount(databaseState)
        var channelId: Long
            set(value) {
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                try {
                    bot.database.trnsctn { databaseState.channel = value }
                } catch (e: NullPointerException) {
                    destroy()
                }
                field = value
            }

        constructor(channel: VoiceChannel, player: AudioPlayerSendHandler,
                    playlist: MutableList<AudioTrack>, position: Long) {
            playlistCache = playlist.map { it.info.uri }
            this.databaseState = bot.database.audioDB.createMusicState(channel, playlistCache)
            channelId = channel.idLong
            this.player = player
            this.playlistPos = position
            currentlyPlaying.add(this)
        }

        constructor(databaseState: AudioDB.MusicState) {
            channelId = transaction(bot.database.db) { databaseState.channel }
            player = AudioPlayerSendHandler(playerManager.createPlayer())

            player.audioPlayer.addListener(object : AudioEventAdapter() {
                override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
                    if (endReason?.mayStartNext == true) {
                        bot.scope.launch { next() }
                    }
                }
            })

            // Not sure what kind of stuff can be thrown here
            @Suppress("SwallowedException", "TooGenericExceptionCaught")
            try {
                channel?.guild?.audioManager?.openAudioConnection(channel)
                channel?.guild?.audioManager?.connectionListener = object : ConnectionListener {
                    override fun onStatusChange(status: ConnectionStatus) {
                        if (status == ConnectionStatus.CONNECTED) {
                            channel?.guild?.audioManager?.isSelfDeafened = true
                            channel?.guild?.audioManager?.sendingHandler = player
                            bot.scope.launch { playCurrent() }
                        }
                    }

                    @Suppress("EmptyFunctionBlock")
                    override fun onPing(ping: Long) {}
                    @Suppress("EmptyFunctionBlock")
                    override fun onUserSpeaking(user: User, speaking: Boolean) {}
                }
            } catch (e: Exception) {
                destroy()
            }

            playlistPos = databaseState.playlistPos
            playlistCache = transaction(bot.database.db) { databaseState.items.map { it.audioSource } }
            this.databaseState = databaseState
            currentlyPlaying.add(this)
            bot.log.info("Resuming music in ${channel?.guild?.name}")
        }

        private val playlistCache: List<String>
        private val databaseState: AudioDB.MusicState

        var paused
            get() = player.audioPlayer.isPaused
            set(value) { player.audioPlayer.isPaused = value }

        var volume
            get() = player.audioPlayer.volume
            set(value) { player.audioPlayer.volume = value }

        var progress get() = player.audioPlayer.playingTrack?.position
            set(value) { if (value != null) { player.audioPlayer.playingTrack?.position =
                value.coerceIn(0..(currentlyPlayingTrack?.duration ?: 0)) } }

        val currentlyPlayingTrack: AudioTrack? get() = player.audioPlayer.playingTrack

        suspend fun next(): Boolean {
            val canProceed = playlistPos++.run { this >= 0 && this < transaction(bot.database.db) {
                databaseState.items.count()
            } }

            if (canProceed) {
                bot.database.audioDB.updateMusicState(databaseState, 0L,
                    player.audioPlayer.isPaused, player.audioPlayer.volume, playlistPos)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }

        suspend fun previous(): Boolean {
            val canProceed = playlistPos--.run { this >= 0 && this < bot.database.trnsctn {
                databaseState.items.count()
            } }

            if (canProceed) {
                bot.database.audioDB.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused,
                    player.audioPlayer.volume, playlistPos)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }

        fun current(): String? {
            return bot.database.audioDB.playlistItem(databaseState, playlistPos)
        }

        suspend fun playCurrent() {
            val cur = load(current())
            player.audioPlayer.playTrack(cur.firstOrNull() ?: run { destroy(); return })
            if (databaseState.playlistPos == playlistPos) {
                player.audioPlayer.playingTrack.position = databaseState.position
            }
            bot.database.audioDB.updateMusicState(databaseState, player.audioPlayer.playingTrack.position,
                player.audioPlayer.isPaused, player.audioPlayer.volume, playlistPos)
        }

        fun range(range: LongRange) = bot.database.audioDB.playlistItems(databaseState, range)

        fun queue(tracks: List<AudioTrack>) {
            bot.database.audioDB.addPlaylistItems(databaseState, tracks)
        }

        fun destroy() {
            try {
                @Suppress("TooGenericExceptionCaught")
                try {
                    bot.database.audioDB.clearMusicState(databaseState)
                } catch (e: Exception) {
                    bot.log.error("Error while clearing music state!\n" + e.stackTraceToString())
                }
            } finally {
                channel?.guild?.audioManager?.closeAudioConnection()
                currentlyPlaying.remove(this)
            }
        }

        fun updateDatabase() {
            bot.database.audioDB.updateMusicState(databaseState, player.audioPlayer.playingTrack?.position ?: 0,
                player.audioPlayer.isPaused, volume, playlistPos)
        }

        /** O(n), avoid when possible */
        fun shuffle() {
            bot.database.trnsctn {
                val shuffled = (0 until playlistCount).shuffled()
                for ((item, pos) in databaseState.items.zip(shuffled)) {
                    if (item.position == playlistPos) continue
                    item.position = pos
                }
            }
        }

        /** O(n), avoid when possible */
        fun remove(position: Long) {
            bot.database.trnsctn {
                AudioDB.PlaylistEntry.find {
                    (PlaylistEntries.state eq databaseState.id) and (PlaylistEntries.position eq position)
                }.firstOrNull()?.delete()

                for (item in AudioDB.PlaylistEntry.find {
                    (PlaylistEntries.state eq databaseState.id) and (PlaylistEntries.position greater position)
                }) {
                    item.position -= 1
                }
            }
        }

        operator fun get(position: Long) = bot.database.audioDB.playlistItem(databaseState, position)
    }

    init {
        AudioSourceManagers.registerRemoteSources(playerManager)
    }

    class AudioPlayerSendHandler(val audioPlayer: AudioPlayer) : AudioSendHandler {
        private var lastFrame: AudioFrame? = null

        override fun canProvide(): Boolean {
            lastFrame = audioPlayer.provide()
            return lastFrame != null
        }

        override fun provide20MsAudio(): ByteBuffer? {
            return ByteBuffer.wrap(lastFrame!!.data)
        }

        override fun isOpus(): Boolean {
            return true
        }
    }
}
