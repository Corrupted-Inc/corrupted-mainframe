package commands

import commands.CommandHandler.*
import commands.CommandHandler.Command.CommandBuilder
import discord.Bot
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import utils.admin
import java.awt.Color
import java.time.temporal.TemporalAccessor
import kotlinx.coroutines.*
import net.dv8tion.jda.api.entities.User
import utils.weightedRandom
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class Commands(val bot: Bot) {
    val handler = CommandHandler<Message, MessageEmbed> { commandResult ->
        commandResult.sender.channel.sendMessage(commandResult.value ?: return@CommandHandler).queue()
    }

    private fun embed(title: String, url: String? = null, content: List<MessageEmbed.Field> = emptyList(), imgUrl: String? = null, thumbnail: String? = null, author: String? = null, authorUrl: String? = null, timestamp: TemporalAccessor? = null, color: Color? = null, description: String? = null): MessageEmbed {
        val builder = EmbedBuilder()
        builder.setTitle(title, url)
        builder.fields.addAll(content)
        builder.setImage(imgUrl)
        builder.setThumbnail(thumbnail)
        builder.setAuthor(author, authorUrl)
        builder.setTimestamp(timestamp)
        builder.setColor(color)
        builder.setDescription(description)
        return builder.build()
    }

    init {
        class UserArg(name: String, optional: Boolean = false) : Argument<User>(User::class, { bot.jda.getUserById(it.removeSurrounding("<@", ">"))!! }, { bot.jda.retrieveUserById(it.removeSurrounding("<@", ">")).complete() != null }, name, optional)

        val unauthorized = EmbedBuilder().setTitle("Insufficient Permissions").setColor(Color(235, 70, 70)).build()
        handler.register(
            CommandBuilder<Message, MessageEmbed>("help").arg(IntArg("page", true)).ran { sender, args ->
                var page = args.getOrDefault("page", 1) as Int - 1
                val list = handler.commands.sortedBy { it.base == "help" }.chunked(10)
                if (page !in list.indices) page = 0

                val commands = list[page]
                val builder = EmbedBuilder()
                builder.setTitle("Help ${page + 1}/${list.size}")
                for (command in commands) {
                    builder.addField(command.base + " " + command.arguments.joinToString(" ") { if (it.optional) "[${it.name}]" else "<${it.name}>" }, command.help, true)
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
                     Members: ${bot.dbWrapper.listUsers().size}
                     Guilds: ${bot.dbWrapper.listGuilds().size}
                     Commands: ${handler.commands.size}
                     Gateway ping: ${bot.jda.gatewayPing}ms
                     Uptime: ${Duration.between(bot.startTime, Instant.now()).toString().removePrefix("PT").replace("(\\d[HMS])(?!$)".toRegex(), "$1 ").toLowerCase()}
                """.trimIndent())
                InternalCommandResult(builder.build(), true)
            }
        )

        val administration = CommandCategory("Administration", mutableListOf())
        handler.register(
            CommandBuilder<Message, MessageEmbed>("ban").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                var isAdmin = false
                bot.database.transaction {
                    isAdmin = bot.dbWrapper.getUser(sender.author, this)?.botAdmin == true || sender.member.admin
                }
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.ban(id, 0).complete()
                return@ran InternalCommandResult(embed("Banned", description = "Banned <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unban").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                val isAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.unban(id).complete()
                return@ran InternalCommandResult(embed("Unbanned", description = "Unbanned <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("kick").arg(StringArg("user id")).ran { sender, args ->
                val id = (args["user id"] as String).removeSurrounding(prefix = "<@", suffix = ">")
                val isAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                sender.guild.kick(id).complete()
                return@ran InternalCommandResult(embed("Kicked", description = "Kicked <@$id>"), true)
            }.category(administration)
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("slots").ran { sender, _ ->
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
            CommandBuilder<Message, MessageEmbed>("trace").arg(UserArg("ID")).ran { sender, args ->
                val user = args["ID"] as User
                val botAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true
                val isAdmin = sender.member.admin || botAdmin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)
                val dbUser = bot.database.transaction { bot.dbWrapper.getUser(user, this) }

                val embed = EmbedBuilder()
                embed.setTitle("User ${user.asTag} (${user.id})")

                embed.setThumbnail(user.avatarUrl)

                embed.addField("Account Creation Date:", user.timeCreated.toString(), true)

                embed.addField("Flags:", if (user.flags.isEmpty()) "None" else user.flags.joinToString { it.getName() }, false)

                if (dbUser != null) {
                    embed.addField("**Database Info**", "", false)
                    if (botAdmin) {
                        for (guild in dbUser.servers) {
                            val resolved = sender.jda.getGuildById(guild.discordId) ?: continue
                            val member = resolved.getMember(user) ?: continue
                            embed.addField(
                                resolved.name,
                                "Join date: ${member.timeJoined}\nNickname: ${member.effectiveName}\nRoles: ${member.roles.joinToString { it.name }}\nAdmin: ${member.admin}",
                                false
                            )
                        }
                    } else {
                        embed.addField("Insufficient permissions", "To view information from the database you must be a global admin.", false)
                    }
                }

                return@ran InternalCommandResult(embed.build(), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("prefix").arg(StringArg("prefix")).ran { sender, args ->
                val isAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true || sender.member.admin
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val prefix = args["prefix"] as? String ?: return@ran InternalCommandResult(embed("Invalid prefix"), false)

                if (!prefix.matches(".{1,30}".toRegex())) {
                    return@ran InternalCommandResult(embed("Invalid prefix"), false)
                }

                bot.database.transaction {
                    val model = bot.dbWrapper.getGuild(sender.guild, this) ?: return@transaction null
                    detach(model)

                    model.prefix = prefix

                    merge(model)
                }

                return@ran InternalCommandResult(embed("Successfully set prefix"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("admin").arg(UserArg("user")).ran { sender, args ->
                val isAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                bot.database.transaction {
                    val model = bot.dbWrapper.getUser(user, this) ?: return@transaction
                    detach(model)
                    model.botAdmin = true
                    merge(model)
                }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a global admin"), true)
            }
        )

        handler.register(
            CommandBuilder<Message, MessageEmbed>("unadmin").arg(UserArg("user")).ran { sender, args ->
                val isAdmin = bot.database.transaction { bot.dbWrapper.getUser(sender.author, this) }?.botAdmin == true || bot.config.permaAdmins.contains(sender.author.id)
                if (!isAdmin) return@ran InternalCommandResult(unauthorized, false)

                val user = args["user"] as User

                if (user.id in bot.config.permaAdmins) return@ran InternalCommandResult(embed("Cannot un-admin a permanent admin.", color = Color(235, 70, 70)), false)

                bot.database.transaction {
                    val model = bot.dbWrapper.getUser(user, this) ?: return@transaction
                    detach(model)
                    model.botAdmin = false
                    merge(model)
                }

                return@ran InternalCommandResult(embed("Successfully made @${user.asTag} a non-admin"), true)
            }
        )
    }

    fun handle(message: Message) {
        GlobalScope.launch {
            val prefix = bot.database.transaction { bot.dbWrapper.getGuild(message.guild, this) }?.prefix ?: "!"
            handler.handleAndSend(prefix, message.contentRaw, message)
        }
    }
}
