package discord

import commands.Commands
import core.Config
import core.db.Database
import core.db.DatabaseWrapper
import kotlinx.coroutines.*
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.concurrent.Task
import java.time.Instant
import java.util.logging.Logger

class Bot(config: Config) {
    val database = Database()
    val dbWrapper = DatabaseWrapper(database)
    val logger: Logger = Logger.getLogger("bot logger")
    val listeners = MultiListener()
    val startTime = Instant.now()
    val jda = JDABuilder.create(config.token, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS).addEventListeners(listeners).build()

    init {
        listeners.add(object : ListenerAdapter() {
            override fun onReady(event: ReadyEvent) {
                logger.info("Logged in as ${event.jda.selfUser.asTag}")
                try {
                    database.transaction {
                        val done = BooleanArray(jda.guilds.size)
                        for ((index, guild) in jda.guilds.withIndex()) {
                            guild.loadMembers {
                                dbWrapper.addLink(it.user, guild, this@transaction)
                            }.onSuccess { done[index] = true }
                        }
                        while (done.any { !it }) {
                            Thread.sleep(50L)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
                database.transaction {
                    dbWrapper.addLink(event.user, event.guild, this)
                }
            }
        })
    }

    private val commands = Commands(this)

    init {
        listeners.add(object : ListenerAdapter() {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                commands.handle(event.message)
            }
        })
    }
}
