package audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.VoiceChannel
import java.nio.ByteBuffer

class Audio {
    val playerManager = DefaultAudioPlayerManager()
    val currentlyPlaying = mutableListOf<AudioState>()

    data class AudioState(val channel: VoiceChannel, val player: AudioPlayerSendHandler, val playlist: MutableList<AudioTrack>, var position: Int) {
        fun next() = position++ in playlist.indices
        fun previous() = position-- in playlist.indices

        fun current() = playlist.getOrNull(position)
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
