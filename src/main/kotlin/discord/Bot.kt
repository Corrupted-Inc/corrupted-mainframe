package discord

import audio.Audio
import commands.Commands
import core.Config
import core.db.ExposedDatabase
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.*
import java.util.logging.Logger
import kotlin.concurrent.schedule

class Bot(val config: Config) {
    val logger: Logger = Logger.getLogger("bot logger")
    val listeners = MultiListener()
    val startTime: Instant = Instant.now()
    val jda = JDABuilder.create(config.token, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_VOICE_STATES).addEventListeners(listeners).build()
    val database = ExposedDatabase(Database.connect("jdbc:sqlite:database.db", driver = "org.sqlite.JDBC").apply { useNestedTransactions = true })
    val audio = Audio()

    init {
        listeners.add(object : ListenerAdapter() {
            override fun onReady(event: ReadyEvent) {
                logger.info("Logged in as ${event.jda.selfUser.asTag}")
                for (guild in jda.guilds) {
                    guild.loadMembers {
                        database.addLink(guild, it.user)
                    }
                }

                Timer().schedule(0, 15_000L) {
                    for (mute in database.expiringMutes()) {
                        try {
                            transaction(database.db) {
                                val guild = jda.getGuildById(mute.guild.discordId)!!
                                val member = guild.getMemberById(mute.user.discordId)!!
                                val roles = database.roleIds(mute).map { guild.getRoleById(it) }
                                guild.modifyMemberRoles(member, roles).queue()
                                database.removeMute(mute)
                            }
                        } catch (e: NullPointerException) {
                            database.removeMute(mute)
                        }
                    }
                }
            }

            override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
                database.addLink(event.guild, event.user)
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
