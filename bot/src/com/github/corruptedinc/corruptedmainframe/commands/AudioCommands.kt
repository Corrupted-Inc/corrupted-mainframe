package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.components.Button
import kotlin.math.floor
import kotlin.random.Random

private const val SPACES_TO_EMOTE = 4
private const val BAR_LENGTH = 15
private const val QUEUE_VIEW_LENGTH = 5
private const val MAX_VOLUME = 200

@Suppress("LongMethod", "ComplexMethod", "ThrowsCount")  // why yes, it is a long method
fun registerAudioCommands(bot: Bot, commands: Commands) {

    fun nothingPlaying(guild: Guild?): Nothing {
        guild?.audioManager?.closeAudioConnection()
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

    commands.register(CommandData("play", "Play a song")
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
        val state = bot.audio.createState(channel, player, t)
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

        val first = bot.audio.load(state.current()).firstOrNull()
        event.replyEmbeds(embed("Playing ${first?.info?.title}", url = first?.info?.uri)).complete()
    }

    commands.register(CommandData("pause", "Pause the current song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)
        validateInChannel(event.member, state)

        state.paused = true
        event.replyEmbeds(embed("Paused '${state.currentlyPlayingTrack?.info?.title}'")).complete()
    }

    commands.register(CommandData("resume", "Unpause the current song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)
        validateInChannel(event.member, state)

        state.paused = false
        event.replyEmbeds(embed("Unpaused '${state.currentlyPlayingTrack?.info?.title}'")).complete()
    }

    commands.register(CommandData("volume", "Set the volume")
        .addOption(OptionType.INTEGER, "percentage", "The volume percentage", true)) { event ->
            val state = state(event.member) ?: nothingPlaying(event.guild)
            validateInChannel(event.member, state)

            val vol = event.getOption("percentage")!!.asLong.toInt().coerceIn(0, MAX_VOLUME)

            state.volume = vol
            event.replyEmbeds(embed("Set volume to $vol%")).complete()
        }

    commands.register(CommandData("stop", "Stop playing")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)
        validateInChannel(event.member, state)
        state.destroy()

        event.replyEmbeds(embed("Stopped playing")).complete()
    }

    commands.register(CommandData("skip", "Skip to the next song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)

        validateInChannel(event.member, state)

        if (next(event.member)) {
            event.replyEmbeds(embed("Playing next")).complete()
        } else {
            event.replyEmbeds(embed("Nothing to play")).complete()
        }
    }

    commands.register(CommandData("previous", "Skip to the previous song")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)

        validateInChannel(event.member, state)

        if (previous(event.member)) {
            event.replyEmbeds(embed("Playing previous")).complete()
        } else {
            event.replyEmbeds(embed("Nothing to play")).complete()
        }
    }

    commands.register(CommandData("shuffle", "Shuffle the playlist")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)

        validateInChannel(event.member, state)

        state.shuffle()

        event.replyEmbeds(embed("Shuffling")).complete()
    }

    commands.register(CommandData("queue", "Show the queue")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)

        validateInChannel(event.member, state)

        event.replyEmbeds(
        embed(
            "Queue",
            content = state.range(state.playlistPos..(state.playlistPos + QUEUE_VIEW_LENGTH))
                .mapIndexedNotNull { i, it ->
                    val loaded = bot.audio.load(it).firstOrNull() ?: return@mapIndexedNotNull null
                    MessageEmbed.Field("#$i: ${loaded.info.title}", loaded.info.author, false)
                }
                .reversed()
        )).complete()
    }

    commands.register(CommandData("remove", "Remove an item from the queue")
        .addOption(OptionType.INTEGER, "index", "The number of the item to remove", true)) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)

        validateInChannel(event.member, state)

        val idx = event.getOption("index")!!.asLong + state.playlistPos
        if (idx !in 0 until (state.playlistCount - state.playlistPos))
            throw CommandException("$idx is not a valid index")

        val found = state[idx]
            ?: throw CommandException("this should never happen, which is why I'm showing it to the user")

        val info = bot.audio.load(found).firstOrNull()  // it's got caching, it's fine.... right??

        state.remove(idx)

        event.replyEmbeds(embed("Successfully removed '${info?.info?.title}' from the playlist.")).complete()
    }

    commands.register(CommandData("fastforward", "Skip forward in the current song")
        .addOption(OptionType.INTEGER, "seconds", "The number of seconds to skip", true)) { event ->

        val state = state(event.member) ?: nothingPlaying(event.guild)
        validateInChannel(event.member, state)

        val seconds = event.getOption("seconds")!!.asLong.coerceAtLeast(0)

        @Suppress("MagicNumber")  // no, I am not defining a constant for seconds to milliseconds
        state.progress = state.progress?.plus(seconds * 1000)

        event.replyEmbeds(embed("Skipping $seconds seconds...")).complete()
    }

    commands.register(CommandData("playing", "Show what is currently playing")) { event ->
        val state = state(event.member) ?: nothingPlaying(event.guild)
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

        val listener = listener@ { buttonEvent: ButtonClickEvent ->
            if (buttonEvent.button?.id?.endsWith(id.toString()) == true) {
                validateInChannel(buttonEvent.member ?: return@listener, state)

                when (buttonEvent.button?.id?.removeSuffix(id.toString())) {
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
}
