package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.Config
import com.github.corruptedinc.corruptedmainframe.audio.Audio
import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.commands.Commands.Companion.embed
import com.github.corruptedinc.corruptedmainframe.commands.Leveling
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.injectKTX
import dev.minn.jda.ktx.listener
import kotlinx.coroutines.*
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.ReadyEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.interaction.ButtonClickEvent
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.apache.logging.log4j.Level
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.impl.Log4jLoggerFactory
import java.lang.IllegalArgumentException
import java.time.Instant
import java.util.*
import kotlin.concurrent.schedule
import kotlin.random.Random


@OptIn(ExperimentalCoroutinesApi::class)
class Bot(val config: Config) {
    val log = Log4jLoggerFactory().getLogger("aaaaaaa")
    val startTime: Instant = Instant.now()
    val jda = JDABuilder.create(config.token,
        GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.DIRECT_MESSAGES)
        .disableCache(CacheFlag.ACTIVITY, CacheFlag.EMOTE, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS)
        .injectKTX()
        .build()
    val scope = CoroutineScope(Dispatchers.Default)
    val database = ExposedDatabase(Database.connect(config.databaseUrl, driver = config.databaseDriver).apply {
        useNestedTransactions = true
    })
    val audio = Audio(this)
    val leveling = Leveling(this)
    val buttonListeners = mutableListOf<(ButtonClickEvent) -> Unit>()

    companion object {
        /** Number of milliseconds between checking for expiring reminders and mutes. */
        private const val REMINDER_MUTE_RESOLUTION = 1000L
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

        jda.listener<ReadyEvent> { event ->
            log.info("Logged in as ${event.jda.selfUser.asTag}")

            updateActivity()

            scope.launch {
                while (true) {
                    delay(REMINDER_MUTE_RESOLUTION)
                    for (mute in database.moderationDB.expiringMutes()) {
                        try {
                            database.trnsctn {  // todo why is the expensive part in the transaction
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

            // If they're muted they aren't eligible for reaction roles
            val end = event.user?.let { database.moderationDB.findMute(it, event.guild)?.end }
            if (end?.let { Instant.ofEpochSecond(it).isAfter(Instant.now()) } == true) {
                event.reaction.removeReaction(event.user!!).queue()
                return@listener
            }

            event.guild.addRoleToMember(event.userId, role).queue()
        }

        @Suppress("ReturnCount")  // todo maybe fix?  not sure how to make this work
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
    }

    init {

        jda.listener<GuildMessageReceivedEvent> { event ->
            if (database.banned(event.author)) return@listener
            if (event.author == jda.selfUser) return@listener
            scope.launch {
                leveling.addPoints(event.author, Leveling.POINTS_PER_MESSAGE, event.channel)
            }
        }

        jda.listener<ButtonClickEvent> { event ->
            if (database.banned(event.user)) return@listener
            buttonListeners.forEach { it(event) }
        }

        Commands(this)
    }
}
