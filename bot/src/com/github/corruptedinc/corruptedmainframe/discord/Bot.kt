package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.commands.RobotPaths
import com.github.corruptedinc.corruptedmainframe.commands.TheBlueAlliance
import com.github.corruptedinc.corruptedmainframe.commands.fights.Fights
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.plugin.PluginLoader
import com.github.corruptedinc.corruptedmainframe.utils.Emotes
import com.github.corruptedinc.corruptedmainframe.utils.PathDrawer
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.injectKTX
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.*
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.entities.Icon
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.and
import org.slf4j.Logger
import org.slf4j.impl.SimpleLogger
import org.slf4j.impl.SimpleLoggerFactory
import java.io.File
import java.time.Instant
import java.util.*


@OptIn(ExperimentalCoroutinesApi::class)
class Bot(val config: Config) {
    val log: Logger = SimpleLoggerFactory().getLogger("aaaaaaa") // Creates a log.
    val startTime: Instant = Instant.now() // Sets the start time of the bot.
    val jda = JDABuilder.create(config.token,
        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_EMOJIS)
        .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS)
        .injectKTX()
        .build() // The actual API for discord.
    val scope = CoroutineScope(Dispatchers.Default)
    // Creates a database instance from the URL and driver specified in the config file.  The jar includes the postgresql driver
    val database = ExposedDatabase(Database.connect(config.databaseUrl, driver = config.databaseDriver,
        databaseConfig = DatabaseConfig { useNestedTransactions = true }
    ), this)
    val audio = Audio(this)
    val leveling = Leveling(this)
    val buttonListeners = mutableListOf<(ButtonInteractionEvent) -> Unit>()
    val starboard = Starboard(this)
    val commands = Commands(this)
    val theBlueAlliance = TheBlueAlliance(config.blueAllianceToken, scope)
    private val plugins = PluginLoader(File("plugins"), this)
    val pathDrawer = PathDrawer(this)
    val paths = RobotPaths(this)
    val fights = Fights(this)

    companion object {
        /** Number of milliseconds between checking for expiring reminders. */
        private const val REMINDER_RESOLUTION = 1000L
        private const val GUILD_SCAN_INTERVAL = 3600_000L
    }

    private fun updateActivity() {
        jda.presence.activity = Activity.watching("${jda.shardManager?.guilds?.size ?: jda.guilds.size} guilds")
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Saving audio state to database...")
            audio.gracefulShutdown()
            log.info("Finished, exiting")
        })

        Emotes

        jda.listener<ReadyEvent> { event ->
            log.info("Logged in as ${event.jda.selfUser.asTag}")

            updateActivity()

            scope.launch {
                while (true) {
                    delay(REMINDER_RESOLUTION)

                    database.trnsctn {
                        for (reminder in database.expiringRemindersNoTransaction()) {
                            // Make copies of relevant things for thread safety
                            val text = reminder.text + ""
                            val channelId = reminder.channelId
                            val userId = reminder.user.discordId
                            launch {
                                val channel = jda.getTextChannelById(channelId)
                                val user = jda.getUserById(userId)
                                if (user != null) {
                                    channel?.sendMessage(user.asMention)?.await()
                                    channel?.sendMessageEmbeds(embed("Reminder", description = text))?.await()
                                } else {
                                    log.error("Couldn't find user with id '$userId'!")
                                }
                            }
                            reminder.delete()
                        }
                    }
                }
            }

            // Every so often, double check if it's actually in those guilds
            // Note: keeping track of this is stupid and should be removed
            scope.launch {
                while (true) {
                    val guilds = (jda.shardManager?.guildCache ?: jda.guildCache).map { it.idLong }.toHashSet()

                    database.trnsctn {
                        for (g in database.guilds()) {
                            g.currentlyIn = g.discordId in guilds
                        }
                    }
                    delay(GUILD_SCAN_INTERVAL)
                }
            }
        }

        @Suppress("ReturnCount")
        jda.listener<MessageReactionAddEvent> { event ->
            if (event.user?.let { database.banned(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener

            if (!event.reaction.retrieveUsers().complete().any { it.id == jda.selfUser.id }) return@listener

            event.guild.addRoleToMember(event.userId, role).queue()
        }

        @Suppress("ReturnCount")
        jda.listener<MessageReactionRemoveEvent> { event ->
            if (event.user?.let { database.banned(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener
            event.guild.removeRoleFromMember(event.userId, role).queue()
        }

        jda.listener<GuildJoinEvent> { event ->
            event.guild.loadMembers {
                database.addLink(event.guild, it.user)
            }.onSuccess {}
            updateActivity()
        }

        jda.listener<GuildLeaveEvent> { event ->
            database.trnsctn { database.guild(event.guild).currentlyIn = false }
        }

        jda.listener<MessageReceivedEvent> { event ->
            val reaction = database.trnsctn {
                val g = database.guild(event.guild)
                val u = database.user(event.author)
                ExposedDatabase.Autoreaction.find { (ExposedDatabase.Autoreactions.guild eq g.id) and (ExposedDatabase.Autoreactions.user eq u.id) }
                    .singleOrNull()?.reaction
            } ?: return@listener
            val isBuiltin = Emotes.isValid(reaction)
            if (isBuiltin) {
                event.message.addReaction(reaction).await()
            } else {
                val found = event.guild.getEmoteById(reaction.filter { it.isDigit() }) ?: return@listener
                event.message.addReaction(found).await()
            }
        }
    }

    init {
        jda.listener<MessageReceivedEvent> { event ->
            if (!event.isFromGuild || event.channelType != ChannelType.TEXT) return@listener
            if (database.banned(event.author)) return@listener
            if (event.author == jda.selfUser) return@listener
            scope.launch {
                leveling.addPoints(event.author, Leveling.POINTS_PER_MESSAGE, event.textChannel)
            }
        }

        jda.listener<ButtonInteractionEvent> { event ->
            if (database.banned(event.user)) return@listener
            buttonListeners.forEach { it(event) }
        }

        plugins.loadPlugins()
        commands.registerAll()
    }
}
