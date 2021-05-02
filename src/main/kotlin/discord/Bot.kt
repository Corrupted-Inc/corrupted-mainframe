package discord

import commands.Commands
import core.Config
import core.db.Database
import core.db.DatabaseWrapper
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.logging.Logger

class Bot(config: Config) {
    val database = Database()
    val dbWrapper = DatabaseWrapper(database)
    val logger: Logger = Logger.getLogger("bot logger")
    val listeners = MultiListener()
    init {
        listeners.add(object : ListenerAdapter() {
            override fun onReady(event: ReadyEvent) {
                logger.info("Logged in as ${event.jda.selfUser.asTag}")
                for (guild in jda.guilds) {
                    guild.loadMembers {
                        dbWrapper.addLink(it.user, guild)
                    }
                }
            }

            override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
                dbWrapper.addLink(event.user, event.guild)
            }
        })
    }
    val jda = JDABuilder.createDefault(config.token).addEventListeners(listeners).enableIntents(GatewayIntent.GUILD_MEMBERS).build()

    private val commands = Commands(this)

    init {
        listeners.add(object : ListenerAdapter() {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                commands.handle(event.message)
            }
        })
    }
}
