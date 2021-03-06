package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.commands.Commands
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.StarredMessage
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.StarredMessages
import dev.minn.jda.ktx.await
import dev.minn.jda.ktx.listener
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import org.intellij.lang.annotations.Language
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and

class Starboard(private val bot: Bot) {
    companion object {
        private val url = "^(https?)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]".toRegex()
        private val mediaExtensions = setOf("png", "jpg", "jpeg", "gif"/*, "webm", "mp4"*/)
    }

    init {
        bot.jda.listener<MessageReactionAddEvent> { event ->
            // DMs can't be starred
            if (!event.isFromGuild) return@listener

            val u = event.retrieveUser().await()
            // get a list of the guild's starboards
            val board = bot.database.trnsctn {
                val g = event.guild.m
                // banned people don't get to have their messages starred
                return@trnsctn if (banned(u))
                    emptyList()
                else
                    // make a list of the values we need
                    // the Starboard object itself isn't returned because it doesn't work outside of the transaction
                    g.starboards.map { Triple(it.emote, it.threshold, it.id.value) }
            }.firstOrNull { (if (':' in it.first) event.reactionEmote.asReactionCode else event.reactionEmote.name) == it.first } ?: return@listener  // find a starboard matching the emote

            // if there's enough reactions, star it (the function checks if it's already been starred)
            // we have to retrieve the message to get the reaction counts
            val msg = event.retrieveMessage().await()
            val reaction = msg.reactions.find { event.reactionEmote.asReactionCode == it.reactionEmote.asReactionCode } ?: return@listener
            if (board.second <= reaction.count) {
                star(event.textChannel, u, event.messageIdLong, board.third)
            }
        }
    }

    context(Transaction) fun unstarDB(starredMessage: Message, starboard: ExposedDatabase.Starboard) {
        val msg = StarredMessage.find { (StarredMessages.messageID eq starredMessage.idLong) and (StarredMessages.starboard eq starboard.id) }
            .firstOrNull() ?: return

        val points = if (msg.pointsEarned == -1.0) bot.leveling.starboardPoints(bot.leveling.level(starredMessage.author, starredMessage.guild)) else msg.pointsEarned

        bot.database.addPoints(starredMessage.author, starredMessage.guild, -points)
        msg.delete()
    }

    suspend fun star(channel: TextChannel, author: User, messageID: Long, boardID: Long) {
        val points = bot.leveling.starboardPoints(bot.leveling.level(author, channel.guild))

        val (alreadyStarred, starboardChannelID, name) = bot.database.trnsctn {
            val g = channel.guild.m
            val board = ExposedDatabase.Starboard.findById(boardID) ?: return@trnsctn Triple(true, null, "")

            // THIS IS THE WRONG WAY TO DO THIS, DO NOT USE THIS AS AN EXAMPLE
            // this should be a PreparedStatement to avoid SQL injection
            // filling info in like this is ok here ONLY because the data is ALWAYS numeric and not strings
            // TODO: turn into a prepared statement anyways

            // checks if the message has already been starred in the same channel by a different starboard
            @Language("SQL") val fromAnotherStarboardSQL = """
                SELECT COUNT(starred_messages.id) FROM starred_messages inner join starboards s on starred_messages.starboard = s.id where channel = ${board.channel} and message_id = $messageID
            """.trimIndent()

            val count = exec(fromAnotherStarboardSQL) { it.next(); it.getLong(1) }!!

            val alreadyStarred = count > 0

            if (alreadyStarred) return@trnsctn Triple(true, board.channel, board.name)

            StarredMessage.new {
                this.guild = g
                this.messageID = messageID
                this.starboard = board
                this.pointsEarned = points
            }
            return@trnsctn Triple(false, board.channel, board.name)
        }

        if (alreadyStarred || starboardChannelID == null) return

        val message = channel.retrieveMessageById(messageID).await() ?: return

        // would be nice if this could be put in the above transaction, but oh well
        bot.leveling.addPoints(message.author, points, channel)

        val attachments = message.attachments.filter { it.isImage }.map { it.url } + url.findAll(message.contentRaw).filter { it.value.substringAfterLast('.') in mediaExtensions }.map { it.value }
        val embed = Commands.embed(name, message.jumpUrl,
            description = "${message.member?.asMention}:\n" + message.contentRaw.ifBlank { message.embeds.firstOrNull()?.description ?: "" },
            color = message.member?.color, thumbnail = message.member?.effectiveAvatarUrl,
            imgUrl = attachments.randomOrNull(),
            timestamp = message.timeCreated,
            stripPings = false
        )
        val starboardChannel = message.guild.getTextChannelById(starboardChannelID) ?: return
        starboardChannel.sendMessageEmbeds(embed).await()
    }
}
