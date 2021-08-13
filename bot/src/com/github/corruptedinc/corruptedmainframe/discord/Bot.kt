package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.plugin.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.time.Instant
import java.util.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.logging.Logger
import kotlin.concurrent.schedule


class Bot(val config: Config) {
    val log: Logger = Logger.getLogger("bot logger")
    val listeners = MultiListener()
    val startTime: Instant = Instant.now()
    val jda = JDABuilder.create(config.token,
        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.DIRECT_MESSAGES).addEventListeners(listeners).build()
    val scope = CoroutineScope(Dispatchers.Default)
    val database = ExposedDatabase(Database.connect(config.databaseUrl, driver = config.databaseDriver).apply {
        useNestedTransactions = true
    })
    val audio = Audio(this)
    val leveling = Leveling(this)
    val buttonListeners = mutableListOf<(ButtonClickEvent) -> Unit>()
    val plugins = mutableListOf<Plugin>()

    companion object {
        /** Number of milliseconds between checking for expiring reminders and mutes. */
        private const val REMINDER_MUTE_RESOLUTION = 1000L
        private const val PLUGIN_MAIN_CLASS_NAME = "com.plugin.Plugin"
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Saving audio state to database...")
            audio.gracefulShutdown()
            log.info("Finished, exiting")
        })

        val pluginsDir = File("plugins")
        // Create plugin dir if it doesn't exist
        pluginsDir.mkdir()
        val pluginFiles = pluginsDir.listFiles()
        for (plugin in pluginFiles ?: emptyArray()) {
            if (!plugin.name.endsWith(".jar")) continue
            @Suppress("TooGenericExceptionCaught")  // Anything can happen when loading a plugin
            try {
                val jar = JarFile(plugin)
                val e = jar.entries()

                val urls = arrayOf(URL("jar:file:" + plugin.path + "!/"))
                val cl = URLClassLoader.newInstance(urls)

                while (e.hasMoreElements()) {
                    val je: JarEntry = e.nextElement()
                    if (je.isDirectory || !je.name.endsWith(".class")) {
                        continue
                    }
                    // -6 because of .class
                    var className: String = je.name.removeSuffix(".class")
                    className = className.replace('/', '.')
                    val c: Class<*> = cl.loadClass(className)

                    if (className == PLUGIN_MAIN_CLASS_NAME) {
                        val loaded = c.constructors.first().newInstance(this) as Plugin
                        plugins.add(loaded)
                        loaded.load()
                    }
                }
            } catch (e: Exception) {
                log.severe("Error loading plugin '${plugin.nameWithoutExtension}'!")
                log.severe(e.stackTraceToString())
            }
        }

        listeners.add(object : ListenerAdapter() {
            override fun onReady(event: ReadyEvent) {
                log.info("Logged in as ${event.jda.selfUser.asTag}")
                plugins.forEach { it.botStarted() }

                Timer().schedule(0, REMINDER_MUTE_RESOLUTION) {
                    for (mute in database.moderationDB.expiringMutes()) {
                        try {
                            transaction(database.db) {
                                val guild = jda.getGuildById(mute.guild.discordId)!!
                                val member = guild.getMemberById(mute.user.discordId)!!
                                val roles = database.moderationDB.roleIds(mute).map { guild.getRoleById(it) }
                                guild.modifyMemberRoles(member, roles).queue({}, {})  // ignore errors
                            }
                        } finally {
                            database.moderationDB.removeMute(mute)
                        }
                    }

                    database.trnsctn {
                        for (reminder in database.expiringRemindersNoTransaction()) {
                            try {
                                // Make copies of relevant things for thread safety
                                val text = reminder.text
                                jda.getTextChannelById(reminder.channelId)?.retrieveMessageById(reminder.messageId)
                                    ?.queue({
                                        it.replyEmbeds(embed("Reminder", text)).queue({}, {/*ignore errors*/})
                                    }, {/*ignore errors*/})
                            } finally {
                                reminder.delete()
                            }
                        }
                    }
                }
            }

            override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
                database.addLink(event.guild, event.user)
            }

            @Suppress("ReturnCount")  // todo likewise fix maybe?
            override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
                if (event.user?.let { database.banned(it) } == true) return
                val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return
                val role = event.guild.getRoleById(roleId) ?: return

                // If they're muted they aren't eligible for reaction roles
                val end = event.user?.let { database.moderationDB.findMute(it, event.guild)?.end }
                if (end?.let { Instant.ofEpochSecond(it).isAfter(Instant.now()) } == true) {
                    event.reaction.removeReaction(event.user!!).queue()
                    return
                }

                event.guild.addRoleToMember(event.userId, role).queue()
            }

            @Suppress("ReturnCount")  // todo maybe fix?  not sure how to make this work
            override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
                if (event.user?.let { database.banned(it) } == true) return
                val roleId = database.moderationDB.autoRole(event.messageIdLong, event.reactionEmote) ?: return
                val role = event.guild.getRoleById(roleId) ?: return
                event.guild.removeRoleFromMember(event.userId, role).queue()
            }

            override fun onGuildJoin(event: GuildJoinEvent) {
                event.guild.loadMembers {
                    database.addLink(event.guild, it.user)
                }
            }
        })
    }

    private val commands = Commands(this)
    init {
        plugins.forEach { it.registerCommands(commands.handler) }

        listeners.add(object : ListenerAdapter() {
            override fun onGuildMessageReceived(event: GuildMessageReceivedEvent) {
                if (database.banned(event.author)) return
                if (event.author == jda.selfUser) return
                commands.handle(event.message)
                scope.launch {
                    leveling.addPoints(event.author, Leveling.POINTS_PER_MESSAGE, event.channel)
                }
            }

            override fun onButtonClick(event: ButtonClickEvent) {
                if (database.banned(event.user)) return
                buttonListeners.forEach { it(event) }
            }
        })
    }
}
