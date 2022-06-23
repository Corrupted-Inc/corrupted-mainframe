package com.github.corruptedinc.corruptedmainframe.discord

import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase
import com.github.corruptedinc.corruptedmainframe.core.db.ExposedDatabase.Companion.m
import com.github.corruptedinc.corruptedmainframe.repostdetector.ImageHasher
import dev.minn.jda.ktx.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.TextChannel
import java.net.URL

class ImageCrawler(private val bot: Bot) {
    @OptIn(DelicateCoroutinesApi::class)  // this is fine
    private val crawlerScope = CoroutineScope(newFixedThreadPoolContext(4, "image-crawler"))
    private val hasher = ImageHasher()

    fun crawl(guild: Guild) {
        val jobs = bot.database.trnsctn {
            val g = guild.m
            val channels = guild.textChannels
                .filter { guild.selfMember.hasPermission(it, Permission.MESSAGE_HISTORY, Permission.VIEW_CHANNEL) }
                .map { it.idLong }

            val missingCrawlJobs = channels.toMutableSet()
            missingCrawlJobs.removeAll(g.crawlJobs.map { it.channel }.toSet())
            missingCrawlJobs
        }

        for (job in jobs) {
            bot.scope.launch {
                doCrawl(guild.getTextChannelById(job)!!)
            }
        }
    }

    private suspend fun doCrawl(channel: TextChannel) {
        val chanID = channel.idLong
        val guildID = channel.guild.idLong
        val firstMessage = channel.getHistoryFromBeginning(5).await().retrievedHistory.firstOrNull() ?: return

        var isDone = false

        val jobID = bot.database.trnsctn {
            val g = channel.guild.m
            val existing = ExposedDatabase.ImageHashJob.find { ExposedDatabase.ImageHashJobs.channel eq chanID }
                .toList()
            if (existing.size == 1) {
                isDone = existing.single().done
                return@trnsctn existing.single().id
            }

            return@trnsctn ExposedDatabase.ImageHashJob.new {
                this.channel = chanID
                this.guild = g.id
                this.lastMessage = -1
                this.done = false
            }.id
        }

        if (isDone) return

        var messageID = firstMessage.idLong
        while (true) {
            // get new references to the channels to avoid them expiring while looping
            val messages = bot.jda.getGuildById(guildID)!!
                .getTextChannelById(chanID)!!
                .getHistoryAfter(messageID, 100).await().retrievedHistory
            if (messages.size <= 1) break
            messageID = messages.last().idLong
            for (item in messages) {
                val urls = mutableListOf<String>()
                item.attachments.forEach { if (it.isImage) urls.add(it.url) }
                item.embeds.forEach { urls.add(it.image?.url ?: return@forEach) }

                for ((index, embed) in urls.withIndex()) {
                    crawlerScope.launch {
                        val hash = hasher.hash(embed) ?: return@launch
                        bot.database.trnsctn {
                            val g = bot.database.guild(guildID)
                            ExposedDatabase.ImageHash.new {
                                this.channel = chanID
                                this.guild = g.id
                                this.hash = hash
                                this.message = item.idLong
                                this.embedNumber = index.toByte()
                            }
                        }
                    }
                }
            }

            bot.database.trnsctn {
                ExposedDatabase.ImageHashJob[jobID].lastMessage = messageID
            }
        }
        bot.database.trnsctn {
            ExposedDatabase.ImageHashJob[jobID].done = true
        }
    }
}
