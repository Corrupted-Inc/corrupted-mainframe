package commands

import audio.Audio
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import commands.CommandHandler.*
import commands.CommandHandler.Command.CommandBuilder
import discord.Bot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import utils.admin
import java.awt.Color
import java.time.temporal.TemporalAccessor
import kotlinx.coroutines.*
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.jetbrains.exposed.sql.transactions.transaction
import utils.toHumanReadable
import java.lang.NumberFormatException
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

class Commands(val bot: Bot) {
    val handler = CommandHandler<Message, MessageEmbed> { commandResult ->
        commandResult.sender.channel.sendMessage(commandResult.value ?: return@CommandHandler).queue()
    }

    // fun stripPings(inp: String) = inp.replace("@", "\\@")

    fun String.stripPings() = this.replace("@", "\\@")

    private fun embed(title: String, url: String? = null, content: List<MessageEmbed.Field> = emptyList(), imgUrl: String? = null, thumbnail: String? = null, author: String? = null, authorUrl: String? = null, timestamp: TemporalAccessor? = null, color: Color? = null, description: String? = null): MessageEmbed {
        val builder = EmbedBuilder()
        builder.setTitle(title, url)
        builder.fields.addAll(content.map { Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline) })
        builder.setImage(imgUrl)
        builder.setThumbnail(thumbnail)
        builder.setAuthor(author, authorUrl)
        builder.setTimestamp(timestamp)
        builder.setColor(color)
        builder.setDescription(description?.stripPings())
        return builder.build()
    }

    init {
        class UserArg(name: String, optional: Boolean = false) : Argument<User>(User::class, { bot.jda.retrieveUserById(it.removeSurrounding("<@", ">")).complete()!! }, { bot.jda.retrieveUserById(it.removeSurrounding("<@", ">")).complete() != null }, name, optional)

        val unauthorized = EmbedBuilder().setTitle("Insufficient Permissions").setColor(Color(235, 70, 70)).build()
        handler.register(
            CommandBuilder<Message, MessageEmbed>("help", "commands").arg(IntArg("page", true)).ran { sender, args ->
                var page = args.getOrDefault("page", 1) as Int - 1
                val list = handler.commands.sortedBy { it.base.contains("help") }.chunked(10)
                if (page !in list.indices) page = 0

                val commands = list[page]
                val builder = EmbedBuilder()
                builder.setTitle("Help ${page + 1}/${list.size}")
                for (command in commands) {
                    val base = if (command.base.size == 1) command.base.first() else command.base.joinToString(prefix = "(", separator = "/", postfix = ")")
                    builder.addField(base + " " + command.arguments.joinToString(" ") { if (it.optional) "[${it.name}]" else "<${it.name}>" }, command.help, false)
                }
                InternalCommandResult(builder.build(), true)
            }.help("Shows a list of commands and their descriptions.")
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("stats").ran { sender, _ ->
                val builder = EmbedBuilder()
                builder.setTitle("Statistics and Info")
                builder.setThumbnail(sender.guild.iconUrl)
                builder.setDescription("""
                    **Bot Info**
                     Members: ${bot.database.users().size}
                     Guilds: ${bot.database.guilds().size}
                     Commands: ${handler.commands.size}
                     Gateway ping: ${bot.jda.gatewayPing}ms
                     Uptime: ${Duration.between(bot.startTime, Instant.now()).toHumanReadable()}
                """.trimIndent())
                InternalCommandResult(builder.build(), true)
            }
        )

        val administration = CommandCategory("Administration", mutableListOf())
        handler.register(
            CommandBuilder<Message, MessageEmbed>("ban").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.ban(id, 0).complete()
                return@ran InternalCommandResult(embed("Banned", description = "Banned <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unban").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.unban(id).complete()
                return@ran InternalCommandResult(embed("Unbanned", description = "Unbanned <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("kick").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.kick(id).complete()
                return@ran InternalCommandResult(embed("Kicked", description = "Kicked <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("slots", "gamble").ran { sender, _ ->
                val emotes = ":cherries:, :lemon:, :seven:, :broccoli:, :peach:, :green_apple:".split(", ")

                val numberCorrect = /*weightedRandom(
                    listOf(1,     2,     3,     4,     5,      6),
                    listOf(0.6,   0.2,   0.15,  0.03,  0.0199, 0.000001)
                )*/ Random.nextInt(1, 6)

                fun section(count: Int, index: Int): List<String> {
                    return emotes.plus(emotes).slice(index..(index + emotes.size)).take(count)
                }

                val indexes = MutableList(6) { Random.nextInt(0, 6) }
//                for (i in 0 until numberCorrect) {
//                    indexes.add(indexes[0])
//                }
//                for (i in 0 until emotes.size - numberCorrect) {
//                    indexes.add(Random.nextInt(emotes.size))
//                }
                indexes.shuffle()
                val wheels = indexes.map { section(3, it) }
                var output = ""
                try {
                    output = (0 until 3).joinToString("\n") { n -> wheels.joinToString("   ") { it[n] } }
                } catch (e: Exception) { e.printStackTrace() }
                InternalCommandResult(embed("${sender.author.name} is playing...", description = output), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("trace", "lookup").arg(UserArg("ID")).ran { sender, args ->
                val user = args["ID"] as User
                val botAdmin = bot.database.user(sender.author).botAdmin
                val isAdmin = sender.member.admin || botAdmin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                val dbUser = bot.database.user(user)

                val embed = EmbedBuilder()
                val dmEmbed = EmbedBuilder()
                embed.setTitle("User ${user.asTag} (${user.id})")
                dmEmbed.setTitle("User ${user.asTag} (${user.id})")

                embed.setThumbnail(user.avatarUrl)
                dmEmbed.setThumbnail(user.avatarUrl)

                embed.addField("Account Creation Date:", user.timeCreated.toString(), true)
                dmEmbed.addField("Account Creation Date:", user.timeCreated.toString(), true)

                embed.addField("Flags:", if (user.flags.isEmpty()) "None" else user.flags.joinToString { it.getName() }, false)
                dmEmbed.addField("Flags:", if (user.flags.isEmpty()) "None" else user.flags.joinToString { it.getName() }, false)

                dmEmbed.addField("**Database Info**", "", false)
                if (botAdmin) {
                    for (guild in transaction(bot.database.db) { dbUser.guilds.toList() }) {
                        val resolved = sender.jda.getGuildById(guild.discordId) ?: continue
                        val member = resolved.getMember(user) ?: continue
                        dmEmbed.addField(
                            resolved.name,
                            "Join date: ${member.timeJoined}\nNickname: ${member.effectiveName}\nRoles: ${member.roles.joinToString { it.name }}\nAdmin: ${member.admin}",
                            false
                        )
                    }
                } else {
                    dmEmbed.addField("Insufficient permissions", "To view information from the database you must be a global admin.", false)
                }

                sender.author.openPrivateChannel().complete().sendMessage(dmEmbed.build()).queue()
                return@ran InternalCommandResult(embed.build(), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("prefix", "setprefix").arg(StringArg("prefix")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val prefix = args["prefix"] as? String ?: return@ran InternalCommandResult(embed("Invalid prefix"), false)

                if (!prefix.matches(".{1,30}".toRegex())) {
                    return@ran InternalCommandResult(embed("Invalid prefix"), false)
                }

                transaction(bot.database.db) { bot.database.guild(sender.guild).prefix = prefix }

                return@ran InternalCommandResult(embed("Successfully set prefix"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("admin").arg(UserArg("user")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                transaction(bot.database.db) { bot.database.user(user).botAdmin = true }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a global admin"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unadmin", "deadmin").arg(UserArg("user")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                if (user.id in bot.config.permaAdmins) return@ran InternalCommandResult(embed("Cannot un-admin a permanent admin.", color = Color(235, 70, 70)), false)

                transaction(bot.database.db) { bot.database.user(user).botAdmin = false }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a non-admin"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("mute").args(UserArg("user"), IntArg("seconds")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin

                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User
                val time = (args["seconds"] as Int).coerceAtLeast(15)
                val member = sender.guild.getMember(user) ?: return@ran InternalCommandResult(embed("Must be a member of this server"), false)

                val end = Instant.now().plusSeconds(time.toLong())
                bot.database.addMute(user, member.roles, end, sender.guild)

                member.guild.modifyMemberRoles(member, listOf()).complete()

                return@ran InternalCommandResult(embed("Muted ${user.asTag} for ${Duration.ofSeconds(time.toLong()).toHumanReadable()}"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unmute").args(UserArg("user")).ran { sender, args ->
                val isAdmin = bot.database.user(sender.author).botAdmin || sender.member.admin

                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                val mute = bot.database.findMute(user, sender.guild) ?: return@ran InternalCommandResult(embed("${user.asMention} isn't muted!"), false)

                sender.guild.modifyMemberRoles(sender.guild.getMember(user)!!, bot.database.roleIds(mute).map { sender.guild.getRoleById(it) }).complete()

                return@ran InternalCommandResult(embed("Unmuted ${user.asTag}"), true)
            }
        )

        suspend fun load(source: String): List<AudioTrack> {
            var done = false
            var track: AudioPlaylist? = null
            bot.audio.playerManager.loadItem(source, object : AudioLoadResultHandler {
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

        fun dispose(guild: Guild) {
            guild.audioManager.closeAudioConnection()
            for (state in bot.audio.currentlyPlaying.filter { it.channel.guild == guild }) {
                bot.audio.currentlyPlaying.remove(state)
                state.player.audioPlayer.destroy()
            }
        }

        fun next(guild: Guild): Boolean {
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == guild } ?: return false

            state.next()
            state.player.audioPlayer.playTrack(state.current() ?: run {
                dispose(guild)
                return false
            })
            return true
        }

        fun previous(guild: Guild): Boolean {
            val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == guild } ?: return false

            state.previous()
            state.player.audioPlayer.playTrack(state.current()?.makeClone() ?: run {
                dispose(guild)
                return false
            })
            return true
        }

        handler.register(
            CommandBuilder<Message, MessageEmbed>("play").args(StringArg("source"), StringArg("channel", optional = true)).ran { sender, args ->
                var channel = if (args["channel"] == null) sender.member?.voiceState?.channel else null
                if (channel == null) channel = try { sender.guild.getVoiceChannelById((args["channel"] as String).removeSurrounding("<#", ">")) } catch (e: NumberFormatException) { null }
                    ?: sender.guild.getVoiceChannelsByName(args["channel"] as String, false).firstOrNull()
                    ?: return@ran InternalCommandResult(embed("Please specify a valid voice channel"), false)

                dispose(sender.guild)
                delay(1000L)
                val t = load(args["source"] as String).toMutableList()
                if (t.isEmpty()) {
                    return@ran InternalCommandResult(embed("Failed to load '${args["source"]}'"), false)
                }

                val player = Audio.AudioPlayerSendHandler(bot.audio.playerManager.createPlayer())
//                val musicState = bot.database.createMusicState(channel, )
                val state = Audio.AudioState(channel, player, t, 0)

                player.audioPlayer.addListener(object : AudioEventAdapter() {
                    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
                        if (endReason?.mayStartNext == true) {
                            if (!state.next()) {
                                dispose(sender.guild)
                            } else {
                                dispose(sender.guild)
                            }
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
                            player.audioPlayer.playTrack(state.current() ?: run { dispose(channel.guild); return })
                        }
                    }

                    override fun onPing(ping: Long) {}
                    override fun onUserSpeaking(user: User, speaking: Boolean) {}
                }

                return@ran InternalCommandResult(embed("Playing ${state.playlist.firstOrNull()?.info?.title}"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("pause").ran { sender, _ ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)

                state.player.audioPlayer.isPaused = true
                return@ran InternalCommandResult(embed("Paused '${state.player.audioPlayer.playingTrack?.info?.title}'"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("resume", "play").ran { sender, _ ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)

                state.player.audioPlayer.isPaused = false
                return@ran InternalCommandResult(embed("Resumed '${state.player.audioPlayer.playingTrack?.info?.title}'"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("volume", "vol").arg(IntArg("volume")).ran { sender, args ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)

                val vol = (args["volume"] as Int).coerceIn(0, 200)

                state.player.audioPlayer.volume = vol
                return@ran InternalCommandResult(embed("Set volume to $vol%"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("stop").ran { sender, _ ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)

                dispose(sender.guild)

                return@ran InternalCommandResult(embed("Stopped playing"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("skip", "next").ran { sender, args ->
                if (next(sender.guild)) {
                    return@ran InternalCommandResult(embed("Playing next"), true)
                } else {
                    dispose(sender.guild)
                    return@ran InternalCommandResult(embed("Nothing to play"), false)
                }
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("queue").ran { sender, args ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)
                return@ran InternalCommandResult(embed("Queue", content = state.playlist.drop(state.position).take(5).mapIndexed { i, it -> MessageEmbed.Field("#$i: ${it.info.title}", it.info.author, false) }.reversed()), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("queue", "add").arg(StringArg("source")).ran { sender, args ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)
                val loaded = load(args["source"] as String)
                if (loaded.isEmpty()) return@ran InternalCommandResult(embed("Couldn't find '${args["source"] as String}'"), false)
                state.playlist.addAll(state.playlist.size, loaded)
                return@ran InternalCommandResult(embed("Successfully added ${loaded.size} items to the queue"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("dequeue", "remove", "delete").arg(IntArg("index")).ran { sender, args ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)
                if (args["index"] !in 0 until (state.playlist.size - state.position)) return@ran InternalCommandResult(embed("${args["index"]} is not a valid index"), false)
                return@ran InternalCommandResult(embed("Successfully removed '${state.playlist.removeAt(args["index"] as Int - state.position).info.title}' from the playlist."), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("status", "playing").ran { sender, args ->
                val state = bot.audio.currentlyPlaying.singleOrNull { it.channel.guild == sender.guild } ?: return@ran InternalCommandResult(embed("Nothing playing"), false)
                val length = 15
                val timeFraction = (state.player.audioPlayer.playingTrack?.position ?: 0) / (state.player.audioPlayer.playingTrack?.duration?.toDouble() ?: 1.0)
                val dashCount = floor(timeFraction * length).toInt()
                val spaceCount = 4 * (length - dashCount)
                val embed = embed("'${state.player.audioPlayer.playingTrack.info.title}' by ${state.player.audioPlayer.playingTrack.info.author}",
                        description = "|${":heavy_minus_sign:".repeat(dashCount)}:radio_button:${"-".repeat(spaceCount)}|")

                val message = sender.channel.sendMessage(embed).complete()

                message.addReaction("⏮️").queue { message.addReaction("⏹️").queue { message.addReaction("⏯️").queue { message.addReaction("⏭️").queue() } } }
                bot.listeners.add(object : ListenerAdapter() {
                    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
                        if (event.user == bot.jda.selfUser) return
                        if (event.messageId == message.id) {
                            try {
                                val c = event.reactionEmote.emoji
                                when {
                                    "⏹" in c -> {
                                        dispose(sender.guild)
                                    }
                                    "⏯" in c -> {
                                        state.player.audioPlayer.isPaused = !state.player.audioPlayer.isPaused
                                    }
                                    "⏭" in c -> {
                                        next(sender.guild)
                                    }
                                    "⏮️" in c -> {
                                        previous(sender.guild)
                                    }
                                }
                            } catch (e: Exception) { e.printStackTrace() }
                            event.retrieveMessage().complete().removeReaction(event.reactionEmote.asCodepoints, event.retrieveUser().complete()).queue()
                        }
                    }
                }.apply { val s = this; Timer().schedule(60_000L) { bot.listeners.remove(s) } })

                return@ran InternalCommandResult(null, true)
            }
        )
    }

    fun handle(message: Message) {
        GlobalScope.launch {
            val prefix = bot.database.guild(message.guild).prefix
            handler.handleAndSend(prefix, message.contentRaw, message)
        }
    }
}
