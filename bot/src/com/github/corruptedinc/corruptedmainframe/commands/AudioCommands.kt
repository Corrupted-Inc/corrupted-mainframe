package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.*
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.Command.CommandBuilder
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.levenshtein
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.floor
import kotlin.math.roundToLong

fun registerAudioCommands(bot: Bot, handler: CommandHandler<Message, MessageEmbed>) {

    fun state(sender: Message) = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }

    suspend fun next(message: Message) = state(message)?.next() ?: false

    suspend fun previous(message: Message) = state(message)?.previous() ?: false

    suspend fun queue(sender: Message, args: Map<String, Any?>): InternalCommandResult<MessageEmbed> {
        val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
            ?: return InternalCommandResult(
                embed("Nothing playing"),
                false
            )
        val loaded = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http"))
        if (loaded.isEmpty()) return InternalCommandResult(
            embed("Couldn't find '${args["source"] as String}'"),
            false
        )
        state.queue(loaded)
        return InternalCommandResult(
            embed("Successfully added ${loaded.size} items to the queue"),
            true
        )
    }

    handler.register(
        CommandBuilder<Message, MessageEmbed>("play")
            .args(StringArg("source"), StringArg("channel", optional = true))
            .help("Plays a song.  Give the URL/name in double quotes, and optionally the voice channel name in double quotes.")
            .ran { sender, args ->
                var channel = if (args["channel"] == null) sender.member?.voiceState?.channel else null
                if (channel == null) {
                    val channelName = args["channel"] as String
                    channel = try {
                        sender.guild.getVoiceChannelById(channelName.removeSurrounding("<#", ">"))
                    } catch (e: NumberFormatException) {
                        null
                    }
                        ?: sender.guild.voiceChannels.minByOrNull { it.name.levenshtein(channelName) }
                                ?: return@ran InternalCommandResult(
                            embed("Please specify a valid voice channel"),
                            false
                        )
                }

                val st = state(sender)
                if (st != null) {
                    return@ran queue(sender, args)
                }
                delay(1000L)
                val t = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http")).toMutableList()
                if (t.isEmpty()) {
                    return@ran InternalCommandResult(embed("Failed to load '${args["source"]}'"), false)
                }

                val player = Audio.AudioPlayerSendHandler(bot.audio.playerManager.createPlayer())
                val state = bot.audio.createState(channel, player, t)

                player.audioPlayer.addListener(object : AudioEventAdapter() {
                    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
                        if (endReason?.mayStartNext == true) {
                            bot.scope.launch { state.next() }
                        }
                    }
                })
                bot.audio.currentlyPlaying.add(state)

                sender.guild.audioManager.openAudioConnection(channel)
                sender.guild.audioManager.connectionListener = object : ConnectionListener {
                    override fun onStatusChange(status: ConnectionStatus) {
                        if (status == ConnectionStatus.CONNECTED) {
                            sender.guild.audioManager.isSelfDeafened = true
                            sender.guild.audioManager.sendingHandler = player
                            bot.scope.launch { state.playCurrent() }
                        }
                    }

                    override fun onPing(ping: Long) {}
                    override fun onUserSpeaking(user: User, speaking: Boolean) {}
                }

                val first = bot.audio.load(state.current()).firstOrNull()
                return@ran InternalCommandResult(
                    embed("Playing ${first?.info?.title}", url = first?.info?.uri),
                    true
                )
            }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("pause")
            .help("Pauses the current song.")
            .ran { sender, _ ->
            val state = state(sender) ?: return@ran InternalCommandResult(embed("Nothing playing"), false)

            state.paused = true
            return@ran InternalCommandResult(
                embed("Paused '${state.currentlyPlayingTrack?.info?.title}'"),
                true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("resume", "play")
            .help("Unpauses the current song.")
            .ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            state.paused = false
            return@ran InternalCommandResult(
                embed("Resumed '${state.currentlyPlayingTrack?.info?.title}'"),
                true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("volume", "vol").arg(IntArg("volume"))
            .help("Sets the volume of the current song.  Must be an integer between 0 and 200.")
            .ran { sender, args ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            val vol = (args["volume"] as Int).coerceIn(0, 200)

            state.volume = vol
            return@ran InternalCommandResult(embed("Set volume to $vol%"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("stop")
            .help("Stops playing the current song and deletes the playlist.")
            .ran { sender, _ ->
            state(sender)?.destroy() ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            return@ran InternalCommandResult(embed("Stopped playing"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("skip", "next")
            .help("Skips to the next song, and stops playing if there are no more.")
            .ran { sender, _ ->
            if (next(sender)) {
                return@ran InternalCommandResult(embed("Playing next"), true)
            } else {
                return@ran InternalCommandResult(embed("Nothing to play"), false)
            }
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("back", "prev", "previous", "replay")
            .help("Replays the last song.  If this is the first song, stops playing.")
            .ran { sender, _ ->
            if (previous(sender)) {
                return@ran InternalCommandResult(embed("Playing last"), true)
            } else {
                return@ran InternalCommandResult(embed("Nothing to play"), false)
            }
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("queue")
            .help("Shows the queue.")
            .ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            return@ran InternalCommandResult(
                embed(
                    "Queue",
                    content = state.range(state.playlistPos..(state.playlistPos + 5))
                        .mapIndexedNotNull { i, it ->
                            val loaded = bot.audio.load(it).firstOrNull() ?: return@mapIndexedNotNull null
                            MessageEmbed.Field("#$i: ${loaded.info.title}", loaded.info.author, false)
                        }
                        .reversed()
                ), true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("queue", "add").arg(StringArg("source"))
            .help("Adds a song (by double-quoted name or url) to the end of the playlist.")
            .ran { sender, args ->
                return@ran queue(sender, args)
            }
    )

    //fixme
//    handler.register(
//        CommandBuilder<Message, MessageEmbed>("dequeue", "remove", "delete").arg(IntArg("index")).ran { sender, args ->
//            val state = state(sender) ?: return@ran InternalCommandResult(
//                    embed("Nothing playing"),
//                    false
//                )
//
//            if (args["index"] !in 0 until (state.playlistCount - state.playlistPos)) return@ran InternalCommandResult(
//                embed("${args["index"]} is not a valid index"),
//                false
//            )
//            return@ran InternalCommandResult(
//                embed("Successfully removed '${state.playlist.removeAt(args["index"] as Int - state.position).info.title}' from the playlist."),
//                true
//            )
//        }
//    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("fastforward").arg(DoubleArg("seconds"))
            .ran { sender, args ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

                state.progress = state.progress?.plus(((args["seconds"] as Double) * 1000).roundToLong())

                return@ran InternalCommandResult(
                    embed("Skipping ${args["seconds"]} seconds..."),
                    true
                )
            }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("status", "playing")
            .help("Shows the music status with reaction controls.")
            .ran { sender, _ ->
            val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            val length = 15
            val timeFraction = (state.progress ?: 0) / (state.currentlyPlayingTrack?.duration?.toDouble() ?: 1.0)
            val dashCount = floor(timeFraction * length).toInt()
            val spaceCount = 4 * (length - dashCount)
            val embed = embed(
                "'${state.currentlyPlayingTrack?.info?.title}' by ${state.currentlyPlayingTrack?.info?.author}",
                description = "|${":heavy_minus_sign:".repeat(dashCount)}:radio_button:${"-".repeat(spaceCount)}|",
                imgUrl = if (state.currentlyPlayingTrack?.info?.uri?.startsWith("https://youtube.com/watch?v=") == true) "https://img.youtube.com/vi/${state.currentlyPlayingTrack?.info?.uri?.substringAfterLast("?v=")}/maxresdefault.jpg" else null
            )

            val message = sender.channel.sendMessageEmbeds(embed).complete()

            message.addReaction("⏮️").queue {
                message.addReaction("⏹️")
                    .queue { message.addReaction("⏯️").queue { message.addReaction("⏭️").queue() } }
            }
            bot.listeners.add(object : ListenerAdapter() {
                override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
                    if (event.user == bot.jda.selfUser) return
                    if (event.messageId == message.id) {
                        try {
                            val c = event.reactionEmote.emoji
                            when {
                                "⏹" in c -> {
                                    state(sender)?.destroy()
                                }
                                "⏯" in c -> {
                                    state.paused = !state.paused
                                }
                                "⏭" in c -> {
                                    bot.scope.launch {
                                        state.next()
                                    }
                                }
                                "⏮️" in c -> {
                                    bot.scope.launch {
                                        state.previous()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        event.retrieveMessage().complete()
                            .removeReaction(event.reactionEmote.asCodepoints, event.retrieveUser().complete()).queue()
                    }
                }
            }.apply { val s = this; Timer().schedule(60_000L) { bot.listeners.remove(s); message.clearReactions().queue() } })

            return@ran InternalCommandResult(null, true)
        }
    )
}
