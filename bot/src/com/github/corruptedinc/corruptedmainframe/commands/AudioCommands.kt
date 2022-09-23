package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.replyLambdaPaginator
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.components.buttons.Button
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

private const val SPACES_TO_EMOTE = 4
private const val BAR_LENGTH = 15
private const val QUEUE_VIEW_LENGTH = 5
private const val MAX_VOLUME = 200

@Suppress("LongMethod", "ComplexMethod", "ThrowsCount")  // why yes, it is a long method
fun registerAudioCommands(bot: Bot, commands: Commands) {

    fun nothingPlaying(member: Member?): Nothing {
        if (member?.guild?.audioManager?.connectedChannel?.members?.contains(member) == true)
            member.guild.audioManager.closeAudioConnection()
        throw CommandException("Nothing playing")
    }

    fun state(member: Member?) = bot.audio.currentlyPlaying
        .singleOrNull { it.channel?.members?.contains(member) == true }

    suspend fun next(member: Member?) = state(member)?.next() ?: false

    suspend fun previous(member: Member?) = state(member)?.previous() ?: false

    fun validateInChannel(sender: Member?, state: Audio.AudioState) {
        if (state.channel?.members?.contains(sender) != true)
            throw CommandException("You must be in ${state.channel?.asMention ?: "ohno"} to use this command!")
    }

    suspend fun queue(member: Member?, source: String): MessageEmbed {
        val state = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == member?.guild }
            ?: return embed("Nothing playing")
        validateInChannel(member, state)
        val loaded = bot.audio.load(source, !source.startsWith("http"))
        if (loaded.isEmpty()) throw CommandException("Couldn't find '$source'")
        state.queue(loaded)
        return embed("Successfully added ${loaded.size} items to the queue")
    }

    commands.register(slash("play", "Play a song")
        .addOption(OptionType.STRING, "source", "The URL/search term", true)) { event ->
        val channel = event.member?.voiceState?.channel
            ?: throw CommandException("You must be in a voice channel to use this command!")

        if (event.member?.hasPermission(channel, Permission.VOICE_CONNECT) != true) {
            throw CommandException("this can't happen, why am I adding a check for this")
        }

        val st = state(event.member)
        val source = event.getOption("source")!!.asString.removeSurrounding("\"")
        if (st != null) {
            event.replyEmbeds(queue(event.member, source)).complete()
            return@register
        }
        @Suppress("MagicNumber")
        delay(1000L)  // TODO is this even needed?
        val t = bot.audio.load(source, !source.startsWith("http"))
            .toMutableList()

        if (t.isEmpty()) throw CommandException("Failed to load '$source'")

        val player = Audio.AudioPlayerSendHandler(bot.audio.playerManager.createPlayer())
        val state = bot.audio.createState(channel as AudioChannel, player, t)
        validateInChannel(event.member, state)

        player.audioPlayer.addListener(object : AudioEventAdapter() {
            override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
                if (endReason?.mayStartNext == true) {
                    bot.scope.launch { state.next() }
                }
            }
        })
        bot.audio.currentlyPlaying.add(state)

        event.guild!!.audioManager.openAudioConnection(channel)
        event.guild!!.audioManager.connectionListener = object : ConnectionListener {
            override fun onStatusChange(status: ConnectionStatus) {
                if (status == ConnectionStatus.CONNECTED) {
                    event.guild!!.audioManager.isSelfDeafened = true
                    event.guild!!.audioManager.sendingHandler = player
                    bot.scope.launch { state.playCurrent() }
                }
            }

            @Suppress("EmptyFunctionBlock")
            override fun onPing(ping: Long) {}
            @Suppress("EmptyFunctionBlock")
            override fun onUserSpeaking(user: User, speaking: Boolean) {}
        }

        val first = bot.audio.metadata(state.current())
        event.replyEmbeds(embed("Playing ${first?.title}", url = first?.uri)).complete()
    }

    commands.register(slash("pause", "Pause the current song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)
        validateInChannel(event.member, state)

        state.paused = true
        event.replyEmbeds(embed("Paused '${state.currentlyPlayingTrack?.info?.title}'")).complete()
    }

    commands.register(slash("resume", "Unpause the current song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)
        validateInChannel(event.member, state)

        state.paused = false
        event.replyEmbeds(embed("Unpaused '${state.currentlyPlayingTrack?.info?.title}'")).complete()
    }

    commands.register(slash("volume", "Set the volume")
        .addOption(OptionType.INTEGER, "percentage", "The volume percentage", true)) { event ->
            val state = state(event.member) ?: nothingPlaying(event.member)
            validateInChannel(event.member, state)

            val vol = event.getOption("percentage")!!.asLong.toInt().coerceIn(0, MAX_VOLUME)

            state.volume = vol
            event.replyEmbeds(embed("Set volume to $vol%")).complete()
        }

    commands.register(slash("stop", "Stop playing")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)
        validateInChannel(event.member, state)
        state.destroy()

        event.replyEmbeds(embed("Stopped playing")).complete()
    }

    commands.register(slash("skip", "Skip to the next song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)

        validateInChannel(event.member, state)

        if (next(event.member)) {
            event.replyEmbeds(embed("Playing next")).complete()
        } else {
            event.replyEmbeds(embed("Nothing to play")).complete()
        }
    }

    commands.register(slash("previous", "Skip to the previous song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)

        validateInChannel(event.member, state)

        if (previous(event.member)) {
            event.replyEmbeds(embed("Playing previous")).complete()
        } else {
            event.replyEmbeds(embed("Nothing to play")).complete()
        }
    }

    commands.register(slash("shuffle", "Shuffle the playlist")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)

        validateInChannel(event.member, state)

        state.shuffle()

        event.replyEmbeds(embed("Shuffling")).complete()
    }

    commands.register(slash("queue", "Show the queue")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)

        validateInChannel(event.member, state)

        val size = ceil((state.playlistCount - state.playlistPos) / QUEUE_VIEW_LENGTH.toDouble()).toLong()
        event.replyLambdaPaginator(size) {
            val range = (it * QUEUE_VIEW_LENGTH) until ((it + 1) * QUEUE_VIEW_LENGTH)
            val songs = state.range(range).withIndex()
            val tracks = runBlocking {
                songs.asFlow().mapNotNull { track ->
                    Pair(bot.audio.metadata(track.value) ?: return@mapNotNull null, track.index)
                }.toList()
            }
            val fields = tracks.map { item ->
                MessageEmbed.Field(
                    "#${item.second + range.first}: ${item.first.title}",
                    item.first.author, false)
            }
            embed("Queue (${it + 1} / $size)", content = fields)
        }
    }

    commands.register(slash("remove", "Remove an item from the queue")
        .addOption(OptionType.INTEGER, "index", "The number of the item to remove", true)) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)

        validateInChannel(event.member, state)

        val idx = event.getOption("index")!!.asLong + state.playlistPos
        if (idx !in 0 until (state.playlistCount - state.playlistPos))
            throw CommandException("$idx is not a valid index")

        val found = state[idx]
            ?: throw CommandException("this should never happen, which is why I'm showing it to the user")

        val info = bot.audio.metadata(found)

        state.remove(idx)

        event.replyEmbeds(embed("Successfully removed '${info?.title}' from the playlist.")).complete()
    }

    commands.register(slash("fastforward", "Skip forward in the current song")
        .addOption(OptionType.INTEGER, "seconds", "The number of seconds to skip", true)) { event ->

        val state = state(event.member) ?: nothingPlaying(event.member)
        validateInChannel(event.member, state)

        val seconds = event.getOption("seconds")!!.asLong.coerceAtLeast(0)

        @Suppress("MagicNumber")  // no, I am not defining a constant for seconds to milliseconds
        state.progress = state.progress?.plus(seconds * 1000)

        event.replyEmbeds(embed("Skipping $seconds seconds...")).complete()
    }

    commands.register(slash("playing", "Show what is currently playing")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)
        val length = BAR_LENGTH
        val timeFraction = (state.progress ?: 0) / (state.currentlyPlayingTrack?.duration?.toDouble() ?: 1.0)
        val dashCount = floor(timeFraction * length).toInt()
        val spaceCount = SPACES_TO_EMOTE * (length - dashCount)
        val embed = embed(
            "'${state.currentlyPlayingTrack?.info?.title}' by ${state.currentlyPlayingTrack?.info?.author}",
            description = "|${":heavy_minus_sign:".repeat(dashCount)}:radio_button:${"-".repeat(spaceCount)}|",
            imgUrl = if (state.currentlyPlayingTrack?.info?.uri
                    ?.startsWith("https://youtube.com/watch?v=") == true)
                        "https://img.youtube.com/vi/${state.currentlyPlayingTrack?.info?.uri?.
                        substringAfterLast("?v=")}/maxresdefault.jpg" else null
        )

        val id = Random.nextLong()

        fun buttons(disabled: Boolean = false) = arrayOf(
            Button.primary("prev$id",  Emoji.fromUnicode("⏮️")).withDisabled(disabled),
            Button.primary("stop$id",  Emoji.fromUnicode("⏹")).withDisabled(disabled),
            Button.primary("pause$id", Emoji.fromUnicode("⏯")).withDisabled(disabled),
            Button.primary("next$id",  Emoji.fromUnicode("⏭")).withDisabled(disabled),
        )

        @Suppress("SpreadOperator")
        val message = event.replyEmbeds(embed).addActionRow(*buttons(false)).complete().retrieveOriginal()
            .complete().idLong
        val channelId = event.channel.idLong

        val listener = listener@ { buttonEvent: ButtonInteractionEvent ->
            if (buttonEvent.button.id?.endsWith(id.toString()) == true) {
                validateInChannel(buttonEvent.member ?: return@listener, state)

                when (buttonEvent.button.id?.removeSuffix(id.toString())) {
                    "prev"  -> bot.scope.launch { state.previous() }
                    "stop"  -> state.destroy()
                    "pause" -> state.paused = !state.paused
                    "next"  -> bot.scope.launch { state.next() }
                }
                buttonEvent.deferEdit().complete()
            }
        }

        bot.buttonListeners.add(listener)

        bot.scope.launch {
            delay(Commands.BUTTON_TIMEOUT)
            @Suppress("SpreadOperator")
            bot.jda.getTextChannelById(channelId)?.editMessageEmbedsById(message, embed)
                ?.setActionRow(*buttons(true))?.complete()
            bot.buttonListeners.remove(listener)
        }
    }

    commands.register(slash("seek", "Seeks to a time in the song.")
        .addOption(OptionType.STRING, "time", "The time to seek to")
    ) { event ->
        val state = state(event.member) ?: nothingPlaying(event.member)
        var timeStr = event.getOption("time")!!.asString
        val seconds = timeStr.substringAfterLast(':').toDouble()
        timeStr = timeStr.substringBeforeLast(':', "")
        val minutes = timeStr.substringAfterLast(':').toUIntOrNull() ?: 0U
        timeStr = timeStr.substringBeforeLast(':', "")
        val hours = timeStr.substringAfterLast(':').toUIntOrNull() ?: 0U

        val totalMilliseconds = (seconds * 1000).toLong() +
                (minutes.toInt() * 60_000).toLong() +
                (hours.toInt() * 3600_000).toLong()

        state.progress = totalMilliseconds
        event.replyEmbeds(embed("Seeked to ${totalMilliseconds / 1000} seconds"))
    }
}
