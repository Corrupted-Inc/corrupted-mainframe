package audio

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import discord.Bot
import kotlinx.coroutines.delay
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.VoiceChannel
import java.nio.ByteBuffer

class Audio(val bot: Bot) {
    val playerManager = DefaultAudioPlayerManager()
    val currentlyPlaying = mutableListOf<AudioState>()

    suspend fun load(source: String?, search: Boolean = false): List<AudioTrack> {
        source ?: return emptyList()
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
        return track?.tracks ?: emptyList()
    }

    fun createState(channel: VoiceChannel, player: AudioPlayerSendHandler, playlist: MutableList<AudioTrack>) = AudioState(channel, player, playlist, 0L)

    inner class AudioState(val channel: VoiceChannel, private val player: AudioPlayerSendHandler, playlist: MutableList<AudioTrack>, var position: Long) {
        private val playlistCache = playlist.map { it.identifier }
        private val databaseState = bot.database.createMusicState(channel, playlistCache)

        var paused
            get() = player.audioPlayer.isPaused
            set(value) { player.audioPlayer.isPaused = value }

        var volume
            get() = player.audioPlayer.volume
            set(value) { player.audioPlayer.volume = value }

        val progress get() = player.audioPlayer.playingTrack?.position

        val currentlyPlayingTrack: AudioTrack? get() = player.audioPlayer.playingTrack

        suspend fun next(): Boolean {
            val canProceed = position++.run { this >= 0 && this < databaseState.items.count() }

            if (canProceed) {
                bot.database.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused, player.audioPlayer.volume, position)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }
        suspend fun previous(): Boolean {
            val canProceed = position--.run { this >= 0 && this < databaseState.items.count() }

            if (canProceed) {
                bot.database.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused, player.audioPlayer.volume, position)
                playCurrent()
            } else {
                destroy()
            }

            return canProceed
        }

        fun current(): String? {
            return bot.database.playlistItem(databaseState, position)
        }

        suspend fun playCurrent() {
            val cur = load(current())
            player.audioPlayer.playTrack(cur.firstOrNull() ?: run { destroy(); return })
            bot.database.updateMusicState(databaseState, 0L, player.audioPlayer.isPaused, player.audioPlayer.volume, position)
        }

        fun range(range: LongRange) = bot.database.playlistItems(databaseState, range)

        fun queue(track: AudioTrack) {
            bot.database.addPlaylistItem(databaseState, track.identifier)
        }

        fun queue(tracks: List<AudioTrack>) {
            bot.database.addPlaylistItems(databaseState, tracks)
        }

        fun destroy() {
            bot.database.clearMusicState(databaseState)
            channel.guild.audioManager.closeAudioConnection()
            currentlyPlaying.remove(this)
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
