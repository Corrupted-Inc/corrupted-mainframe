package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.CoroutineEventListener
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
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.interaction.command.*
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.CommandInteraction
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.SlashCommandInteraction
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands.slash
import net.dv8tion.jda.api.interactions.commands.context.MessageContextInteraction
import net.dv8tion.jda.api.interactions.commands.context.UserContextInteraction
import java.awt.Color
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAccessor
import kotlin.random.Random
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
    private val listeners = mutableListOf<CoroutineEventListener>()

    // I'm not sure why, but it won't compile if I remove the suspend modifier, despite this warning
    @Suppress("REDUNDANT_INLINE_SUSPEND_FUNCTION_TYPE")
    private suspend inline fun <T : CommandInteraction> handle(data: CommandData, it: T, lambda: suspend (T) -> Unit) {
        if (it.name == data.name) {
            val hook = it.hook
            val start = System.currentTimeMillis()
            try {
                @Suppress("TooGenericExceptionCaught")
                try {
                    if (bot.database.bannedT(it.user)) return
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
                val g = it.guild!!.m
                val u = it.user.m

                ExposedDatabase.CommandRun.new {
                    this.guild = g.id
                    this.user = u.id
                    this.timestamp = Instant.now()
                    this.command = it.name
                    this.millis = System.currentTimeMillis() - start
                }
            }
        }
    }

    // TODO custom listener pool that handles exceptions
    // TODO cleaner way than upserting the command every time
    fun register(data: CommandData, lambda: suspend (SlashCommandInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<SlashCommandInteractionEvent> {
            handle(data, it, lambda)
        })
    }

    fun registerUser(data: CommandData, lambda: suspend (UserContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<UserContextInteractionEvent> {
            handle(data, it, lambda)
        })
    }

    fun registerMessage(data: CommandData, lambda: suspend (MessageContextInteraction) -> Unit) {
        newCommands.add(data)
        listeners.add(bot.jda.listener<MessageContextInteractionEvent> {
            handle(data, it, lambda)
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
        if (bot.database.trnsctn { event.user.m.botAdmin }) return
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

            val rows = bot.database.trnsctn {
                exec(sql) { result ->
                    val output = mutableListOf<Row>()
                    val r = mutableListOf<String>()
                    while (result.next()) {
                        for (c in 1..result.metaData.columnCount) {
                            r.add(result.getObject(c)?.toString() ?: "null")
                        }
                        output.add(Row(*r.toTypedArray()))
                        r.clear()
                    }
                    if (rollback) {
                        rollback()
                    }
                    output
                }
            }!!
            val table = table(rows.toTypedArray()).chunked(1900)
            @Suppress("MagicNumber")
            val pages = table.chunked(1900)
                .map { embed("a", description = "```\n$it```") }

            @Suppress("SpreadOperator")
            event.replyPaginator(pages = pages.toTypedArray(), Duration.of(BUTTON_TIMEOUT, ChronoUnit.MILLIS)
                .toKotlinDuration()).ephemeral().await()
        }

        registerAudioCommands(bot, this)
        registerCommands(bot)
        registerBotCommands(bot)
        registerUtilityCommands(bot)
        bot.fights.registerCommands()
        bot.leveling.registerCommands()

        bot.jda.listener<ReadyEvent> {
            finalizeCommands()
        }
    }
}
