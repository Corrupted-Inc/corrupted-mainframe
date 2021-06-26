package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.*
import java.util.logging.Logger
import kotlin.concurrent.schedule

class Bot(val config: Config) {
    val log: Logger = Logger.getLogger("bot logger")
    val listeners = MultiListener()
    val startTime: Instant = Instant.now()
    val jda = JDABuilder.create(config.token, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_VOICE_STATES).addEventListeners(listeners).build()
    val scope = CoroutineScope(Dispatchers.Default)
    val database = ExposedDatabase(Database.connect(config.databaseUrl, driver = config.databaseDriver).apply { useNestedTransactions = true })
    val audio = Audio(this)
    val buttonListeners = mutableListOf<(ButtonClickEvent) -> Unit>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            log.info("Saving audio state to database...")
            audio.gracefulShutdown()
            log.info("Finished, exiting")
        })
        listeners.add(object : ListenerAdapter() {
            override fun onReady(event: ReadyEvent) {
                log.info("Logged in as ${event.jda.selfUser.asTag}")
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

            override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
                if (event.user?.let { database.banned(it) } == true) return
                val roleId = database.autoRole(event.messageIdLong, event.reactionEmote) ?: return
                val role = event.guild.getRoleById(roleId) ?: return

                // If they're muted they aren't eligible for reaction roles
                val end = event.user?.let { database.findMute(it, event.guild)?.end }
                if (end?.let { Instant.ofEpochSecond(it).isAfter(Instant.now()) } == true) {
                    event.reaction.removeReaction(event.user!!).queue()
                    return
                }

                event.guild.addRoleToMember(event.userId, role).queue()
            }

            override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
                if (event.user?.let { database.banned(it) } == true) return
                val roleId = database.autoRole(event.messageIdLong, event.reactionEmote) ?: return
                val role = event.guild.getRoleById(roleId) ?: return
                event.guild.removeRoleFromMember(event.userId, role).queue()
            }
        })
    }

    private val commands = Commands(this)

    init {
        listeners.add(object : ListenerAdapter() {
            override fun onMessageReceived(event: MessageReceivedEvent) {
                commands.handle(event.message)
            }

            override fun onButtonClick(event: ButtonClickEvent) {
                if (database.banned(event.user)) return
                buttonListeners.forEach { it(event) }
            }
        })
    }
}
