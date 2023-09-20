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
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.plugin.PluginLoader
import com.github.corruptedinc.corruptedmainframe.utils.Emoji
import com.github.corruptedinc.corruptedmainframe.utils.PathDrawer
import com.github.corruptedinc.corruptedmainframe.utils.levenshtein
import dev.minn.jda.ktx.coroutines.await
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.jdabuilder.injectKTX
import kotlinx.coroutines.*
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
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
import org.slf4j.Logger
import org.slf4j.impl.SimpleLoggerFactory
import java.io.File
import java.time.Instant

class Bot(val config: Config) {
    val log: Logger = SimpleLoggerFactory().getLogger("aaaaaaa") // Creates a logger for recording errors and other debug information
    val startTime: Instant = Instant.now() // Sets the start time of the bot
    private val plugins = PluginLoader(File("plugins"), this)
    val jda = JDABuilder.create(config.token,
        // Specify the intents we need
        // Note the absence of the PRESENCE intent; this vastly reduces traffic to the bot
        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_EMOJIS_AND_STICKERS,
        GatewayIntent.MESSAGE_CONTENT /* for starboard */)
        .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS)  // disable unneeded cache flags to improve performance
        // Add JDA KTX to the mix, which allows for some coroutine-related niceties
        .injectKTX()
        // allow plugins to make changes to the JDA as needed
        .apply { plugins.applyToJDA(this) }
        .build()
    // The primary coroutine scope for the bot
    val scope = CoroutineScope(Dispatchers.Default)
    // Creates a database instance from the URL and driver specified in the config file.  The jar includes the postgresql driver
    val database = ExposedDatabase(
        Database.connect(
            config.databaseUrl,
            driver = config.databaseDriver,
            databaseConfig = DatabaseConfig { useNestedTransactions = true }  // TODO: make individual functions not start their own transactions
        ), this)
    val buttonListeners = mutableListOf<(ButtonInteractionEvent) -> Unit>()
    // load components
    val audio = Audio(this)
    val leveling = Leveling(this)
    val starboard = Starboard(this)
    val commands = Commands(this)
    val fights = Fights()
    val emoji = Emoji()
    // Robotics
    val theBlueAlliance = TheBlueAlliance(config.blueAllianceToken, scope)
    val pathDrawer = PathDrawer(this)
    val paths = RobotPaths(this)

    companion object {
        /** Number of milliseconds between checking for expiring reminders. */
        private const val REMINDER_RESOLUTION =    1_000L
        private const val GUILD_SCAN_INTERVAL = 3600_000L
        const val BUILD = 1L
    }

    private fun updateActivity() {
        jda.presence.activity = Activity.watching("${jda.shardManager?.guilds?.size ?: jda.guilds.size} guilds")
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            // on shutdown, save currently playing audio to the database
            // TODO: add timeout so that it doesn't resume if the bot was offline for more than a few minutes
            log.info("Saving audio state to database...")
            audio.gracefulShutdown()
            log.info("Finished, exiting")
        })

        jda.listener<ReadyEvent> { event ->
            log.info("Logged in as ${event.jda.selfUser.asTag}")

            updateActivity()

            // TODO: figure out a better way to do this than just doing a query every second
            scope.launch {
                while (true) {
                    delay(REMINDER_RESOLUTION)

                    database.trnsctn {
                        for (reminder in database.expiringReminders()) {
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
            // Note: keeping track of this is stupid and should be removed (delegate to discord, may cause sharding issues?)
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
            if (event.user?.let { database.bannedT(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reaction.emoji) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener

            if (!event.reaction.retrieveUsers().complete().any { it.id == jda.selfUser.id }) return@listener

            event.guild.addRoleToMember(event.retrieveUser().await(), role).queue()
        }

        @Suppress("ReturnCount")
        jda.listener<MessageReactionRemoveEvent> { event ->
            if (event.user?.let { database.bannedT(it) } == true) return@listener
            val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reaction.emoji) ?: return@listener
            val role = event.guild.getRoleById(roleId) ?: return@listener
            event.guild.removeRoleFromMember(event.retrieveUser().await(), role).queue()
        }

        jda.listener<GuildJoinEvent> {
//            event.guild.loadMembers {
//                database.addLink(event.guild, it.user)
//            }.onSuccess {}
            updateActivity()
        }

        jda.listener<GuildLeaveEvent> { event ->
            database.trnsctn { event.guild.m.currentlyIn = false }
        }

        // correct people on their Violet#7247 bot usage
        val validRoles = listOf("Alumni", "Archiver", "Awards/Outreach", "CAD/Design", "dank memelord", "Drive Team",
            "Electrical/Pneumatics", "Gamer", "Leadership", "Marketing/Business", "Mechanical", "Mentor", "mom",
            "Pineapple Sympathizer", "Programming", "rlly? light theme?", "Safety Captain", "Scout/Strategy",
            "social", "Spirit Captain", "Volunteer", "list", "Media")

        val mapping = mapOf(
            "mech" to "Mechanical",
            "elec" to "Electrical/Pneumatics",
            "electrical" to "Electrical/Pneumatics",
            "alum" to "Alumni",
            "awards" to "Awards/Outreach",
            "outreach" to "Awards/Outreach",
            "cad" to "CAD/Design",
            "design" to "CAD/Design",
            "memelord" to "dank memelord",
            "driver" to "Drive Team",
            "operator" to "Drive Team",
            "technician" to "Drive Team",
            "human player" to "Drive Team",
            "drive coach" to "Drive Team",
            "pneumatics" to "Electrical/Pneumatics",
            "captain" to "Leadership",
            "lead" to "Leadership",
            "marketing" to "Marketing/Business",
            "business" to "Marketing/Business",
            "grants" to "Marketing/Business",
            "pineapple" to "Pineapple Sympathizer",
            "pineapple on pizza" to "Pineapple Sympathizer",
            "programmer" to "Programming",
            "coder" to "Programming",
            "code" to "Programming",
            "light theme" to "rlly? light theme?",
            "safety" to "Safety Captain",
            "scouting" to "Scout/Strategy",
            "scout" to "Scout/Strategy",
            "strategy" to "Scout/Strategy",
            "spirit" to "Spirit Captain",
            "coach" to "Mentor",
            "a/v" to "Media",
            "video" to "Media",
            "reveal" to "Media",
            "all" to "list",
            "show" to "list"
        )
        jda.listener<MessageReceivedEvent> { event ->
            if (!event.isFromGuild || event.guild.idLong != 292134570940301312) return@listener
            val message = event.message.contentRaw.replace('”', '"').replace('“', '"')
            if (message.startsWith("<give me") || message.startsWith("<giveme") || message.startsWith("giveme") || (message.startsWith("give me") && event.channel.idLong == 296497111090855936) || message.startsWith("<give me")) {
                val attemptedRole = "<?give\\s?me \"?".toRegex().replace(message, "").replace("\"", "")
                val mappingFound = mapping[attemptedRole]
                val nearest = validRoles.minBy { it.lowercase().levenshtein(attemptedRole.lowercase()) }
                if (nearest.lowercase().levenshtein(attemptedRole.lowercase()) > 3 && mappingFound == null) return@listener
                val foundRole = mappingFound ?: nearest
                val isWrong = !message.startsWith("<giveme") || (foundRole.contains(" ") && !message.contains('"')) || attemptedRole != foundRole
                if (isWrong) {
                    event.channel.sendMessage("did you mean: `<giveme ${if (foundRole.contains(' ')) "\"$foundRole\"" else foundRole}`").await()
                }
            }
        }
    }

    init {
        jda.listener<ButtonInteractionEvent> { event ->
            if (database.bannedT(event.user)) return@listener
            buttonListeners.forEach { it(event) }
        }

        plugins.loadPlugins()
        commands.registerAll()
    }
}
