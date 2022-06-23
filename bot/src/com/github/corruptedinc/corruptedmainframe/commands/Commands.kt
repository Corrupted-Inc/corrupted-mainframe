package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.VARCHAR_MAX_LENGTH
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Reminders
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.math.InfixNotationParser
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.CoroutineEventListener
import dev.minn.jda.ktx.Message
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.interactions.replyPaginator
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.GuildChannel
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.build.Commands.user
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.context.MessageContextInteraction
import net.dv8tion.jda.api.interactions.commands.context.UserContextInteraction
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.and
import org.ocpsoft.prettytime.nlp.PrettyTimeParser
import java.awt.Color
import java.math.MathContext
import java.math.RoundingMode
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import java.util.*
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinDuration

class Commands(val bot: Bot) {

    companion object {
        fun String.stripPings() = this.replace("@", "\\@")

        @Suppress("LongParameterList")  // They've got default arguments
        fun embed(
            title: String,
            url: String? = null,
            content: List<Field> = emptyList(),
            imgUrl: String? = null,
            thumbnail: String? = null,
            author: String? = null,
            authorUrl: String? = null,
            timestamp: TemporalAccessor? = null,
            color: Color? = null,
            description: String? = null,
            stripPings: Boolean = true,
            footer: String? = null
        ): MessageEmbed {
            val builder = EmbedBuilder()
            builder.setTitle(title, url)
            builder.fields.addAll(if (stripPings) content.map {
                Field(it.name?.stripPings(), it.value?.stripPings(), it.isInline)
            } else content)
            builder.setImage(imgUrl)
            builder.setThumbnail(thumbnail)
            builder.setAuthor(author, authorUrl)
            builder.setTimestamp(timestamp)
            builder.setColor(color)
            builder.setDescription(if (stripPings) description?.stripPings() else description)
            builder.setFooter(footer)
            return builder.build()
        }

        const val MIN_CALCULATOR_PRECISION = 10
        const val MAX_CALCULATOR_PRECISION = 1024
        const val DEF_CALCULATOR_PRECISION = 100

        private const val SLOTS_HEIGHT = 3
        private const val SLOTS_WIDTH = 6

        const val BUTTON_TIMEOUT = 120_000L

        val ERROR_COLOR = Color(235, 70, 70)

        const val REMINDERS_PER_PAGE = 15
        const val MAX_REMINDERS = 128

        fun adminInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId&permissions=8&scope=applications.commands%20bot"

        fun basicInvite(botId: String) =
            "https://discord.com/api/oauth2/authorize?client_id=$botId" +
                    "&permissions=271830080&scope=applications.commands%20bot"
    }

    internal val newCommands = mutableListOf<CommandData>()
    val listeners = mutableListOf<CoroutineEventListener>()

    // TODO custom listener pool that handles exceptions
    // TODO cleaner way than upserting the command every time
    fun register(data: CommandData, lambda: suspend (SlashCommandInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<SlashCommandInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\nCommand: ${it.commandPath} ${it.options.joinToString { c -> "${c.name}: ${c.asString}" }}\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun registerUser(data: CommandData, lambda: suspend (UserContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<UserContextInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun registerMessage(data: CommandData, lambda: suspend (MessageContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<MessageContextInteractionEvent> {
            if (it.name == data.name) {
                val hook = it.hook
                val start = System.currentTimeMillis()
                try {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (bot.database.banned(it.user)) return@listener
                        lambda(it)
                    } catch (e: CommandException) {
                        val embed = embed("Error", description = e.message, color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                    } catch (e: Exception) {
                        val embed = embed("Internal Error", color = ERROR_COLOR)
                        if (it.isAcknowledged) {
                            hook.editOriginalEmbeds(embed).await()
                        } else {
                            it.replyEmbeds(embed).ephemeral().await()
                        }
                        bot.log.error("Error in command '${data.name}':\n" + e.stackTraceToString())
                    }
                } catch (ignored: PermissionException) {}  // nested try/catch is my favorite
                bot.database.trnsctn {
                    val g = bot.database.guild(it.guild!!)
                    val u = bot.database.user(it.user)

                    ExposedDatabase.CommandRun.new {
                        this.guild = g.id
                        this.user = u.id
                        this.timestamp = Instant.now()
                        this.command = it.name
                        this.millis = System.currentTimeMillis() - start
                    }
                }
            }
        })
    }

    fun autocomplete(commandPath: String, param: String, lambda: (value: String, event: CommandAutoCompleteInteractionEvent) -> List<Command.Choice>) {
        bot.jda.listener<CommandAutoCompleteInteractionEvent> { event ->
            if (event.commandPath != commandPath) return@listener
            val op = event.focusedOption
            if (op.name != param) return@listener

            event.replyChoices(lambda(op.value, event)).await()
        }
    }

    @Suppress("SwallowedException")
    suspend fun finalizeCommands() {
        bot.log.info("Registering ${newCommands.size} commands...")
        bot.jda.guilds.map {
            bot.scope.launch {
                try {
                    it.updateCommands().addCommands(newCommands).await()
                } catch (e: ErrorResponseException) {
                    bot.log.error("Failed to register commands in ${it.name}")
                }
            }
        }.joinAll()
        bot.log.info("Done")

        bot.jda.listener<GuildJoinEvent> { event ->
            event.guild.updateCommands().addCommands(newCommands).await()
        }
    }

    fun assertAdmin(event: CommandInteraction) {
        assertPermissions(event, Permission.ADMINISTRATOR)
    }

    fun assertPermissions(event: CommandInteraction, vararg permissions: Permission,
                          channel: GuildChannel = event.guildChannel) {
        if (bot.database.trnsctn { bot.database.user(event.user).botAdmin }) return
        val member = event.member ?: throw CommandException("Missing permissions!")
        if (!member.getPermissions(channel).toSet().containsAll(permissions.toList())) {
            throw CommandException("Missing permissions!")
        }
    }

    @Suppress("ComplexMethod", "LongMethod", "ThrowsCount")
    fun registerAll() {
        register(slash("invite", "Invite Link")) {
            it.replyEmbeds(embed("Invite Link",
                description = "[Admin invite](${adminInvite(bot.jda.selfUser.id)})\n" +
                              "[Basic permissions](${basicInvite(bot.jda.selfUser.id)})"
            )).await()
        }
        
        register(slash("slots", "Play the slots")) { event ->
            val emotes = ":cherries:, :lemon:, :seven:, :broccoli:, :peach:, :green_apple:".split(", ")

//                val numberCorrect = /*weightedRandom(
//                    listOf(1,     2,     3,     4,     5,      6),
//                    listOf(0.6,   0.2,   0.15,  0.03,  0.0199, 0.000001)
//                )*/ Random.nextInt(1, 6)

            fun section(count: Int, index: Int): List<String> {
                return emotes.plus(emotes).slice(index..(index + emotes.size)).take(count)
            }

            val indexes = MutableList(SLOTS_WIDTH) { Random.nextInt(0, emotes.size) }
//                for (i in 0 until numberCorrect) {
//                    indexes.add(indexes[0])
//                }
//                for (i in 0 until emotes.size - numberCorrect) {
//                    indexes.add(Random.nextInt(emotes.size))
//                }
            indexes.shuffle()
            val wheels = indexes.map { section(SLOTS_HEIGHT, it) }
                val out = (0 until SLOTS_HEIGHT).joinToString("\n") { n ->
                    wheels.joinToString("   ") { it[n] }
                }
            event.replyEmbeds(embed("${event.user.name} is playing...", description = out)).await()
        }

        register(slash("sql", "not for you")
            .addOption(OptionType.STRING, "sql", "bad", true)
            .addOption(OptionType.BOOLEAN, "commit", "keep changes?", false)
        ) { event ->
            // do not touch this
            val isAdmin = bot.config.permaAdmins.contains(event.user.id)
            if (!isAdmin) {
                event.reply("no").await()
                return@register
            }

            val sql = event.getOption("sql")!!.asString
            val rollback = event.getOption("commit")?.asBoolean != true

            val output = bot.database.trnsctn {
                exec(sql) { result ->
                    val output = mutableListOf<MutableList<String>>()
                    while (result.next()) {
                        output.add(mutableListOf())
                        for (c in 1..result.metaData.columnCount) {
                            output.last().add(result.getObject(c).toString())
                        }
                    }
                    if (rollback) {
                        rollback()
                    }
                    output
                }
            }
            val count = output?.firstOrNull()?.size ?: run { event.reply("empty result").ephemeral().await(); return@register }
            val columnWidths = IntArray(count)

            for (row in output) {
                for ((index, item) in row.withIndex()) {
                    if (item.length > columnWidths[index]) columnWidths[index] = item.length + 1
                }
            }

            val outputTable = StringBuilder()
            coroutineScope {
                launch(Dispatchers.IO) {
                    outputTable.append('+')
                    outputTable.append("-".repeat(columnWidths.sum() + columnWidths.size - 1))
                    outputTable.append('+')
                    outputTable.append('\n')

                    for (row in output) {
                        outputTable.append('|')
                        for ((index, item) in row.withIndex()) {
                            outputTable.append(item.padStart(columnWidths[index], ' '))
                            if (index + 1 in row.indices) outputTable.append('|')
                        }
                        outputTable.append('|')
                        outputTable.append('\n')
                    }
                    outputTable.append('+')
                    outputTable.append("-".repeat(columnWidths.sum() + columnWidths.size - 1))
                    outputTable.append('+')
                }
            }.join()

            val table = outputTable.toString()

            @Suppress("MagicNumber")
            val pages = table.chunked(1900)
                .map { embed("a", description = "```\n$it```") }

            @Suppress("SpreadOperator")
            event.replyPaginator(pages = pages.toTypedArray(), Duration.of(BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                .toKotlinDuration()).ephemeral().await()
        }

//        register(
//            slash("autoreact", "Automatically react to a user's message")
//                .addSubcommands(SubcommandData("list", "Lists the autoreactions"))
//                .addSubcommands(SubcommandData("add", "Add an autoreaction").addOption(OptionType.USER, "user", "The user", true).addOption(OptionType.STRING, "reaction", "The reaction", true))
//                .addSubcommands(SubcommandData("remove", "Remove an autoreaction").addOption(OptionType.USER, "user", "The user", true).addOption(OptionType.STRING, "reaction", "The reaction", true))
//        ) { event ->
//            val guild = event.guild!!
//            when (event.subcommandName) {
//                "list" -> {
//                    val autoreactions = bot.database.trnsctn {
//                        val g = bot.database.guild(guild)
//                        val selection = ExposedDatabase.Autoreaction.find { ExposedDatabase.Autoreactions.guild eq g.id }
//                        selection.map { it.user.discordId to it.reaction }
//                    }.map { Row(guild.getMemberById(it.first)?.effectiveName ?: "null", it.second) }
//
//                    val content = table((listOf(Row("Name", "Reaction")) + autoreactions).toTypedArray())
//                    val embeds = content.chunked(1000).map { embed("Autoreactions", description = it) }.toTypedArray()
//
//                    event.replyPaginator(pages = embeds, expireAfter = 5L.minutes)
//                }
//                "add" -> {
////                    assertAdmin(event)
//                    val user = event.getOption("user")!!.asMember!!
//                    val reaction = event.getOption("reaction")!!.asString.dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }
//                    val r = event.guild!!.getEmotesByName(reaction, true).singleOrNull()?.asMention
//                        ?: reaction.filter { it.isDigit() }.toLongOrNull()?.let { event.guild!!.getEmoteById(it)?.asMention }
//                        ?: if (Emotes.isValid(reaction)) reaction else null
////                        ?: Emotes.builtinEmojiByNameFuzzy(reaction).first()
//                    r ?: throw CommandException("No emoji found!")
//                    bot.database.trnsctn {
//                        val u = bot.database.user(user.user)
//                        val g = bot.database.guild(guild)
//
//                        if (g.starboardReaction == r) throw CommandException("nice try")
//                        ExposedDatabase.Autoreaction.new {
//                            this.user = u
//                            this.guild = g
//                            this.reaction = r
//                        }
//                    }
//                    event.replyEmbeds(embed("Added Autoreaction", description = "<@${user.idLong}>'s messages will be reacted to with $r", stripPings = false)).await()
//                }
//                "remove" -> {
////                    assertAdmin(event)
//                    val user = event.getOption("user")!!.asMember!!
//                    val reaction = event.getOption("reaction")!!.asString.dropWhile { it.isWhitespace() }.dropLastWhile { it.isWhitespace() }
//                    val r = event.guild!!.getEmotesByName(reaction, true).singleOrNull()?.asMention
//                        ?: reaction.filter { it.isDigit() }.toLongOrNull()?.let { event.guild!!.getEmoteById(it)?.asMention }
//                        ?: if (Emotes.isValid(reaction)) reaction else null
////                        ?: Emotes.builtinEmojiByNameFuzzy(reaction).first()
//                    r ?: throw CommandException("No emoji found!")
//                    val res = bot.database.trnsctn {
//                        val u = bot.database.user(user.user)
//                        val g = bot.database.guild(guild)
//                        ExposedDatabase.Autoreaction.find { (ExposedDatabase.Autoreactions.guild eq g.id) and (ExposedDatabase.Autoreactions.user eq u.id) and (ExposedDatabase.Autoreactions.reaction eq r) }
//                            .singleOrNull()?.delete()?.run { true } ?: false
//                    }
//                    if (!res) throw CommandException("No existing reaction")
//                    event.replyEmbeds(embed("Removed Autoreaction", description = "<@${user.idLong}>'s messages will no longer be reacted to with $r", stripPings = false)).await()
//                }
//            }
//        }

        registerAudioCommands(bot, this)
        registerCommands(bot)
        registerBotCommands(bot)
        registerUtilityCommands(bot)
        bot.fights.registerCommands()
        bot.leveling.registerCommands()

        bot.scope.launch(Dispatchers.IO) {
            bot.jda.awaitReady()
            finalizeCommands()
        }
    }
}
