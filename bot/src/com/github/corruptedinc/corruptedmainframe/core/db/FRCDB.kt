package com.github.corruptedinc.corruptedmainframe.core.db

import com.github.corruptedinc.corruptedmainframe.discord.Bot
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable

class FRCDB(private val database: ExposedDatabase, private val bot: Bot) {
    fun tables() = arrayOf(CachedZebras)

    object CachedZebras : LongIdTable(name = "cached_zebras") {
        val matchKey = varchar("match_key", ExposedDatabase.VARCHAR_MAX_LENGTH).index()
        val team = integer("team").index()
        val eventKey = varchar("event_key", ExposedDatabase.VARCHAR_MAX_LENGTH)
        val year = integer("year")
        val fullPath = text("full_path")
        val autoPath = text("auto_path")
    }

    class CachedZebra(id: EntityID<Long>) : LongEntity(id) {
        companion object : LongEntityClass<CachedZebra>(CachedZebras)

        var matchKey by CachedZebras.matchKey
        var team     by CachedZebras.team
        var eventKey by CachedZebras.eventKey
        var year     by CachedZebras.year
        var fullPath by CachedZebras.fullPath
        var autoPath by CachedZebras.autoPath
    }
}
