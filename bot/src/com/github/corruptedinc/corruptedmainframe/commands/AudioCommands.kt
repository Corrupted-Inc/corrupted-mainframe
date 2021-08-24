package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.*
import com.github.corruptedinc.corruptedmainframe.commands.CommandHandler.Command.CommandBuilder
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
import net.dv8tion.jda.api.interactions.components.Button
import kotlin.math.floor
import kotlin.math.roundToLong

private const val SPACES_TO_EMOTE = 4
private const val BAR_LENGTH = 15
private const val QUEUE_VIEW_LENGTH = 5
private const val MAX_VOLUME = 200

@Suppress("LongMethod", "ComplexMethod", "ThrowsCount")  // why yes, it is a long method
fun registerAudioCommands(bot: Bot, handler: CommandHandler<Message, MessageEmbed>) {

    //fixme channels
    fun state(sender: Message) = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == sender.guild }

    suspend fun next(message: Message) = state(message)?.next() ?: false

    suspend fun previous(message: Message) = state(message)?.previous() ?: false

    fun validateInChannel(sender: Member?, state: Audio.AudioState) {
        if (state.channel?.members?.contains(sender) != true)
            throw CommandException("You must be in ${state.channel?.asMention ?: "ohno"} to use this command!")
    }

    fun validateInChannel(sender: Message, state: Audio.AudioState) {
        validateInChannel(sender.member, state)
    }

    suspend fun queue(sender: Message, args: Map<String, Any?>): InternalCommandResult<MessageEmbed> {
        val state = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == sender.guild }
            ?: return InternalCommandResult(
                embed("Nothing playing"),
                false
            )
        validateInChannel(sender, state)
        val loaded = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http"))
        if (loaded.isEmpty()) throw CommandException("Couldn't find '${args["source"] as String}'")
        state.queue(loaded)
        return InternalCommandResult(
            embed("Successfully added ${loaded.size} items to the queue"),
            true
        )
    }

    handler.register(
        CommandBuilder<Message, MessageEmbed>("play", "queue", "p")
            .args(StringArg("source"))
            .help("Plays a song.  Give the URL/name in double quotes.")
            .ran { sender, args ->
                val channel = sender.member?.voiceState?.channel
                    ?: throw CommandException("You must be in a voice channel to use this command!")

                if (sender.member?.hasPermission(channel, Permission.VOICE_CONNECT) != true) {
                    throw CommandException("this can't happen, why am I adding a check for this")
                }

                val st = state(sender)
                if (st != null) {
                    return@ran queue(sender, args)
                }
                @Suppress("MagicNumber")
                delay(1000L)  // TODO is this even needed?
                val t = bot.audio.load(args["source"] as String, !(args["source"] as String).startsWith("http"))
                    .toMutableList()
                if (t.isEmpty()) {
                    return@ran InternalCommandResult(embed("Failed to load '${args["source"]}'"), false)
                }

                val player = Audio.AudioPlayerSendHandler(bot.audio.playerManager.createPlayer())
                val state = bot.audio.createState(channel, player, t)
                validateInChannel(sender, state)

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

                    @Suppress("EmptyFunctionBlock")
                    override fun onPing(ping: Long) {}
                    @Suppress("EmptyFunctionBlock")
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
            validateInChannel(sender, state)

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
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
            validateInChannel(sender, state)

            state.paused = false
            return@ran InternalCommandResult(
                embed("Resumed '${state.currentlyPlayingTrack?.info?.title}'"),
                true
            )
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("volume", "vol").arg(IntArg("volume"))
            .help("Sets the volume of the current song.  Must be an integer between 0 and $MAX_VOLUME.")
            .ran { sender, args ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)

                val vol = (args["volume"] as Int).coerceIn(0, MAX_VOLUME)

            state.volume = vol
            return@ran InternalCommandResult(embed("Set volume to $vol%"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("stop", "stoop", "stahp", "sotp", "stfu")
            .help("Stops playing the current song and deletes the playlist.")
            .ran { sender, _ ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)
                state.destroy()

            return@ran InternalCommandResult(embed("Stopped playing"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("skip", "next", "s")
            .help("Skips to the next song, and stops playing if there are no more.")
            .ran { sender, _ ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )

                validateInChannel(sender, state)

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
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)
                if (previous(sender)) {
                return@ran InternalCommandResult(embed("Playing last"), true)
            } else {
                return@ran InternalCommandResult(embed("Nothing to play"), false)
            }
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("shuffle")
            .help("Shuffles the playlist.")
            .ran { sender, _ ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)
                state.shuffle()
                return@ran InternalCommandResult(embed("Shuffled"), true)
        }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("queue", "q")
            .help("Shows the queue.")
            .ran { sender, _ ->
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel?.guild == sender.guild }
                ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)
                return@ran InternalCommandResult(
                embed(
                    "Queue",
                    content = state.range(state.playlistPos..(state.playlistPos + QUEUE_VIEW_LENGTH))
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
        CommandBuilder<Message, MessageEmbed>("dequeue", "remove", "delete", "r").arg(LongArg("index"))
            .help("Removes queued items from the playlist.")
            .ran { sender, args ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                        embed("Nothing playing"),
                        false
                    )

                validateInChannel(sender, state)

                val idx = args["index"] as Long + state.playlistPos
                if (idx !in 0 until (state.playlistCount - state.playlistPos)) return@ran InternalCommandResult(
                    embed("$idx is not a valid index"),
                    false
                )

                val found = state[idx]
                    ?: throw CommandException("this should never happen, which is why I'm showing it to the user")

                val info = bot.audio.load(found).firstOrNull()  // it's got caching, it's fine.... right??

                state.remove(idx)

                return@ran InternalCommandResult(
                    embed("Successfully removed '${info?.info?.title}' from the playlist."),
                    true
                )
            }
    )

    handler.register(
        CommandBuilder<Message, MessageEmbed>("fastforward").arg(DoubleArg("seconds"))
            .ran { sender, args ->
                val state = state(sender) ?: return@ran InternalCommandResult(
                    embed("Nothing playing"),
                    false
                )
                validateInChannel(sender, state)

                @Suppress("MagicNumber")  // no, I am not defining a constant for seconds to milliseconds
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

                fun buttons(disabled: Boolean = false) = arrayOf(
                    Button.primary("prev",  Emoji.fromUnicode("⏮️")).withDisabled(disabled),
                    Button.primary("stop",  Emoji.fromUnicode("⏹")).withDisabled(disabled),
                    Button.primary("pause", Emoji.fromUnicode("⏯")).withDisabled(disabled),
                    Button.primary("next",  Emoji.fromUnicode("⏭")).withDisabled(disabled),
                )

                @Suppress("SpreadOperator")
                val message = sender.channel.sendMessageEmbeds(embed).setActionRow(*buttons(false)).complete()

                val listener = listener@ { event: ButtonClickEvent ->
                    if (event.messageId == message.id) {
                        validateInChannel(event.member ?: return@listener, state)

                        when (event.button?.id) {
                            "prev" -> bot.scope.launch { state.previous() }
                            "stop" -> state.destroy()
                            "pause" -> state.paused = !state.paused
                            "next" -> bot.scope.launch { state.next() }
                        }
                        event.deferEdit().complete()
                    }
                }

                bot.buttonListeners.add(listener)

                bot.scope.launch {
                    delay(Commands.BUTTON_TIMEOUT)
                    @Suppress("SpreadOperator")
                    message.editMessageEmbeds(embed).setActionRow(*buttons(true)).complete()
                    bot.buttonListeners.remove(listener)
                }

                return@ran InternalCommandResult(null, true)
        }
    )
}
