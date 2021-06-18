package audio

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import core.db.ExposedDatabase
import discord.Bot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.VoiceChannel
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

class Audio(val bot: Bot) {
    val playerManager = DefaultAudioPlayerManager()
    val currentlyPlaying = mutableSetOf<AudioState>()

    init {
        bot.scope.launch {
            delay(10_000L)
            for (item in bot.database.musicStates()) {
                currentlyPlaying.add(AudioState(item))
            }
        }
    }

    val cache: Cache<String, List<AudioTrack>> = CacheBuilder.newBuilder().maximumSize(1024).expireAfterWrite(10, TimeUnit.MINUTES).build(object : CacheLoader<String, List<AudioTrack>>() {
        override fun load(key: String): List<AudioTrack> {
            return runBlocking { load(key, nocache = true) }
        }
    })

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

        while (!done) {
            delay(50L)
        }
        val tracks = track?.tracks ?: emptyList()
        cache.put(source, tracks)
        return tracks
    }

    fun createState(channel: VoiceChannel, player: AudioPlayerSendHandler, playlist: MutableList<AudioTrack>) = AudioState(channel, player, playlist, 0L)

    fun gracefulShutdown() {
        for (state in currentlyPlaying) {
            state.updateDatabase()
        }
    }

    inner class AudioState {
        val channel: VoiceChannel
        private val player: AudioPlayerSendHandler
        var playlistPos: Long

        constructor(channel: VoiceChannel, player: AudioPlayerSendHandler, playlist: MutableList<AudioTrack>, position: Long) {
            playlistCache = playlist.map { it.info.uri }
            this.databaseState = bot.database.createMusicState(channel, playlistCache)
            this.channel = channel
            this.player = player
            this.playlistPos = position
            currentlyPlaying.add(this)
        }

        constructor(databaseState: ExposedDatabase.MusicState) {
            channel = bot.jda.getVoiceChannelById(transaction(bot.database.db) { databaseState.channel })!!
            player = AudioPlayerSendHandler(playerManager.createPlayer())

            player.audioPlayer.addListener(object : AudioEventAdapter() {
                override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
                    if (endReason?.mayStartNext == true) {
                        bot.scope.launch { next() }
                    }
                }
            })

            channel.guild.audioManager.openAudioConnection(channel)
            channel.guild.audioManager.connectionListener = object : ConnectionListener {
                override fun onStatusChange(status: ConnectionStatus) {
                    if (status == ConnectionStatus.CONNECTED) {
                        channel.guild.audioManager.isSelfDeafened = true
                        channel.guild.audioManager.sendingHandler = player
                        bot.scope.launch { playCurrent() }
                    }
                }

                override fun onPing(ping: Long) {}
                override fun onUserSpeaking(user: User, speaking: Boolean) {}
            }

            playlistPos = databaseState.playlistPos
            playlistCache = transaction(bot.database.db) { databaseState.items.map { it.audioSource } }
            this.databaseState = databaseState
            currentlyPlaying.add(this)
        }

        private val playlistCache: List<String>
        private val databaseState: ExposedDatabase.MusicState

        var paused
            get() = player.audioPlayer.isPaused
            set(value) { player.audioPlayer.isPaused = value }

        var volume
            get() = player.audioPlayer.volume
            set(value) { player.audioPlayer.volume = value }

        val progress get() = player.audioPlayer.playingTrack?.position

        val currentlyPlayingTrack: AudioTrack? get() = player.audioPlayer.playingTrack

        suspend fun next(): Boolean {
            val canProceed = playlistPos++.run { this >= 0 && this < transaction(bot.database.db) { databaseState.items.count() } }

            if (canProceed) {
                bot.database.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused, player.audioPlayer.volume, playlistPos)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }

        suspend fun previous(): Boolean {
            val canProceed = playlistPos--.run { this >= 0 && this < transaction(bot.database.db) { databaseState.items.count() } }

            if (canProceed) {
                bot.database.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused, player.audioPlayer.volume, playlistPos)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }

        fun current(): String? {
            return bot.database.playlistItem(databaseState, playlistPos)
        }

        suspend fun playCurrent() {
            val cur = load(current())
            player.audioPlayer.playTrack(cur.firstOrNull() ?: run { destroy(); return })
            if (databaseState.playlistPos == playlistPos) {
                player.audioPlayer.playingTrack.position = databaseState.position
            }
            bot.database.updateMusicState(databaseState, player.audioPlayer.playingTrack.position, player.audioPlayer.isPaused, player.audioPlayer.volume, playlistPos)
        }

        fun range(range: LongRange) = bot.database.playlistItems(databaseState, range)

        fun queue(track: AudioTrack) {
            bot.database.addPlaylistItem(databaseState, track.info.uri)
        }

        fun queue(tracks: List<AudioTrack>) {
            bot.database.addPlaylistItems(databaseState, tracks)
        }

        fun destroy() {
            bot.database.clearMusicState(databaseState)
            channel.guild.audioManager.closeAudioConnection()
            currentlyPlaying.remove(this)
        }

        fun updateDatabase() {
            bot.database.updateMusicState(databaseState, player.audioPlayer.playingTrack?.position ?: 0, player.audioPlayer.isPaused, volume, playlistPos)
        }
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
