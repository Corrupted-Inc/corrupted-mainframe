package com.github.corruptedinc.corruptedmainframe.commands

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.discord.Bot
import com.github.corruptedinc.corruptedmainframe.gen.GeneratedCommands
import com.github.corruptedinc.corruptedmainframe.utils.*
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.CoroutineEventListener
import dev.minn.jda.ktx.events.listener
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
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
import net.dv8tion.jda.internal.interactions.CommandDataImpl
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

    data class Cmd(val data: CommandData, val global: Boolean)

    val newCommands = mutableListOf<Cmd>()
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
    fun register(data: CommandData, global: Boolean = false, lambda: suspend (SlashCommandInteraction) -> Unit) {
        newCommands.add(Cmd(data, global))
        listeners.add(bot.jda.listener<SlashCommandInteractionEvent> {
            handle(data, it, lambda)
        })
    }

    fun registerCompatible(data: CommandData, lambda: suspend (SlashCommandInteraction) -> Unit) {
        register(data, global = false, lambda = lambda)
    }

    fun registerUser(data: CommandData, lambda: suspend (UserContextInteraction) -> Unit) {
        newCommands.add(Cmd(data, false))
        listeners.add(bot.jda.listener<UserContextInteractionEvent> {
            handle(data, it, lambda)
        })
    }

    fun registerMessage(data: CommandData, lambda: suspend (MessageContextInteraction) -> Unit) {
        newCommands.add(Cmd(data, false))
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

    private infix fun CommandData.eq(other: CommandData): Boolean {
        return this.toData() == other.toData()
    }

    @Suppress("SwallowedException")
    suspend fun finalizeCommands() {
        newCommands.addAll(GeneratedCommands.commandData().apply { println("Loaded $size commands from annotation processor") })
        bot.log.info("Registering ${newCommands.size} commands...")
        val existing = bot.jda.retrieveCommands().await().map { CommandData.fromCommand(it) to it.idLong }.toSet()
        val new = newCommands.filter { it.global }.map { it.data to 0L }.toSet()
        val added = new.filterNot { existing.any { e -> e.first eq it.first } }
        val removed = existing.filterNot { new.any { e -> e.first eq it.first } }
        bot.log.info("deleting ${removed.size} global commands (${removed.map { it.first.name }}), adding ${added.size} (${added.map { it.first.name }})")
        for (cmd in removed) {
            bot.jda.deleteCommandById(cmd.second).await()
        }
        for (cmd in added) {
            bot.jda.upsertCommand(cmd.first).await()
        }
        bot.log.info("done with global commands")
        bot.jda.guilds.map {
            bot.scope.launch {
                try {
                    val existing = it.retrieveCommands().await().map { CommandData.fromCommand(it) to it.idLong }.toSet()
                    val new = newCommands.filter { !it.global }.map { it.data to 0L }.toSet()
                    val added = new.filterNot { existing.any { e -> e.first eq it.first } }
                    val removed = existing.filterNot { new.any { e -> e.first eq it.first } }
                    bot.log.info("deleting ${removed.size} guild commands (${removed.map { it.first.name }}), adding ${added.size} (${added.map { it.first.name }})")
                    for (cmd in removed) {
                        it.deleteCommandById(cmd.second).await()
                    }
                    for (cmd in added) {
                        it.upsertCommand(cmd.first).await()
                    }
//                    it.updateCommands().addCommands(newCommands.filter { !it.global }.map { it.data }).await()
                } catch (e: ErrorResponseException) {
                    bot.log.error("Failed to register commands in ${it.name}")
                }
            }
        }.joinAll()
        bot.log.info("Done")

        bot.jda.listener<GuildJoinEvent> { event ->
            event.guild.updateCommands().addCommands(newCommands.filter { !it.global }.map { it.data }).await()
        }

        GeneratedCommands.registerListeners(bot)
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
//        registerAudioCommands(bot, this)
        registerCommands(bot)
        bot.leveling.registerCommands()

        bot.onReady {
            finalizeCommands()
        }
    }
}
