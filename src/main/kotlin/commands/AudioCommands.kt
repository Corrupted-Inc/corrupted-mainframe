package commands

import audio.Audio
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import commands.CommandHandler.Command.CommandBuilder
import commands.CommandHandler.IntArg
import commands.CommandHandler.StringArg
import commands.Commands.Companion.embed
import discord.Bot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import utils.levenshtein
import java.lang.NumberFormatException
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.floor

fun registerAudioCommands(bot: Bot, handler: CommandHandler<Message, MessageEmbed>) {

    fun state(sender: Message) = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }

    suspend fun next(message: Message) = state(message)?.next() ?: false

    suspend fun previous(message: Message) = state(message)?.previous() ?: false

    handler.register(
        CommandBuilder<Message, MessageEmbed>("play").args(StringArg("source"), StringArg("channel", optional = true))
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
                                ?: return@ran CommandHandler.InternalCommandResult(
                            embed("Please specify a valid voice channel"),
                            false
                        )
                }

                state(sender)?.destroy()
                delay(1000L)
                val t = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http")).toMutableList()
                if (t.isEmpty()) {
                    return@ran CommandHandler.InternalCommandResult(embed("Failed to load '${args["source"]}'"), false)
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
                return@ran CommandHandler.InternalCommandResult(
                    embed("Playing ${first?.info?.title}", url = first?.info?.uri),
                    true
                )
            }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("pause").ran { sender, _ ->
            val state = state(sender) ?: return@ran CommandHandler.InternalCommandResult(embed("Nothing playing"), false)

            state.paused = true
            return@ran CommandHandler.InternalCommandResult(
                embed("Paused '${state.currentlyPlayingTrack?.info?.title}'"),
                true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("resume", "play").ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            state.paused = false
            return@ran CommandHandler.InternalCommandResult(
                embed("Resumed '${state.currentlyPlayingTrack?.info?.title}'"),
                true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("volume", "vol").arg(IntArg("volume")).ran { sender, args ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            val vol = (args["volume"] as Int).coerceIn(0, 200)

            state.volume = vol
            return@ran CommandHandler.InternalCommandResult(embed("Set volume to $vol%"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("stop").ran { sender, _ ->
            state(sender)?.destroy() ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

            return@ran CommandHandler.InternalCommandResult(embed("Stopped playing"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("skip", "next").ran { sender, _ ->
            if (next(sender)) {
                return@ran CommandHandler.InternalCommandResult(embed("Playing next"), true)
            } else {
                return@ran CommandHandler.InternalCommandResult(embed("Nothing to play"), false)
            }
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("back", "prev", "previous").ran { sender, _ ->
            if (previous(sender)) {
                return@ran CommandHandler.InternalCommandResult(embed("Playing last"), true)
            } else {
                return@ran CommandHandler.InternalCommandResult(embed("Nothing to play"), false)
            }
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("queue").ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            return@ran CommandHandler.InternalCommandResult(
                embed(
                    "Queue",
                    content = state.range((state.position - 5)..state.position)
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
        CommandBuilder<Message, MessageEmbed>("queue", "add").arg(StringArg("source")).ran { sender, args ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            val loaded = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http"))
            if (loaded.isEmpty()) return@ran CommandHandler.InternalCommandResult(
                embed("Couldn't find '${args["source"] as String}'"),
                false
            )
            state.queue(loaded)
            return@ran CommandHandler.InternalCommandResult(
                embed("Successfully added ${loaded.size} items to the queue"),
                true
            )
        }
    )

    //fixme
//    handler.register(
//        CommandBuilder<Message, MessageEmbed>("dequeue", "remove", "delete").arg(IntArg("index")).ran { sender, args ->
//            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
//                ?: return@ran CommandHandler.InternalCommandResult(
//                    embed("Nothing playing"),
//                    false
//                )
//            if (args["index"] !in 0 until (state.playlist.size - state.position)) return@ran CommandHandler.InternalCommandResult(
//                embed("${args["index"]} is not a valid index"),
//                false
//            )
//            return@ran CommandHandler.InternalCommandResult(
//                embed("Successfully removed '${state.playlist.removeAt(args["index"] as Int - state.position).info.title}' from the playlist."),
//                true
//            )
//        }
//    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("status", "playing").ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild }
                ?: return@ran CommandHandler.InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            val length = 15
            val timeFraction = (state.progress ?: 0) / (state.currentlyPlayingTrack?.duration?.toDouble() ?: 1.0)
            val dashCount = floor(timeFraction * length).toInt()
            val spaceCount = 4 * (length - dashCount)
            val embed = embed(
                "'${state.currentlyPlayingTrack?.info?.title}' by ${state.currentlyPlayingTrack?.info?.author}",
                description = "|${":heavy_minus_sign:".repeat(dashCount)}:radio_button:${"-".repeat(spaceCount)}|"
            )

            val message = sender.channel.sendMessage(embed).complete()

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

            return@ran CommandHandler.InternalCommandResult(null, true)
        }
    )
}
