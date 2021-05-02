package core.db

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import javax.persistence.NoResultException

class DatabaseWrapper(val db: Database) {

    fun getGuild(guild: Guild): GuildM? {
        return db.transaction {
            val criteria = criteriaBuilder.createQuery(GuildM::class.java)
            val from = criteria.from(GuildM::class.java)
            criteria.select(from)
            criteria.where(criteriaBuilder.equal(from.get<Long>("discordId"), guild.idLong))
            val typed = createQuery(criteria)
            try {
                typed.singleResult
            } catch (e: NoResultException) {
                null
            }
        }
    }

    fun getUser(user: User): UserM? {
        return db.transaction {
            val criteria = criteriaBuilder.createQuery(UserM::class.java)
            val from = criteria.from(UserM::class.java)
            criteria.select(from)
            criteria.where(criteriaBuilder.equal(from.get<Long>("discordId"), user.idLong))
            val typed = createQuery(criteria)
            try {
                typed.singleResult
            } catch (e: NoResultException) {
                null
            }
        }
    }

    fun listGuilds(): List<GuildM> {
        return db.transaction {
            val criteria = criteriaBuilder.createQuery(GuildM::class.java)
            val from = criteria.from(GuildM::class.java)
            criteria.select(from)
            val typed = createQuery(criteria)
            typed.resultList
        }
    }

    fun listUsers(): List<UserM> {
        return db.transaction {
            val criteria = criteriaBuilder.createQuery(UserM::class.java)
            val from = criteria.from(UserM::class.java)
            criteria.select(from)
            val typed = createQuery(criteria)
            typed.resultList
        }
    }

    fun addLink(user: User, guild: Guild) {
        val originalUserEntry = getUser(user)
        val originalGuildEntry = getGuild(guild)
        var userm: UserM
        var guildm: GuildM
        db.transaction {
            if (originalUserEntry == null) {
                userm = UserM(user.idLong)
                persist(userm)
            } else {
                userm = originalUserEntry
            }
            if (originalGuildEntry == null) {
                guildm = GuildM(guild.idLong)
                persist(guildm)
            } else {
                guildm = originalGuildEntry
            }

            if (!userm.servers.contains(guildm)) {
                userm.servers.add(guildm)
            }
            if (!guildm.users.contains(userm)) {
                guildm.users.add(userm)
            }
            detach(userm)
            detach(guildm)
            merge(userm)
            merge(guildm)
        }
    }

    fun addGuild(guild: Guild) {
        val originalEntry = getGuild(guild)
        if (originalEntry == null) {
            db.transaction {
                val guildm = GuildM(guild.idLong)
                persist(guildm)
            }
        }
    }

    fun addUser(user: User) {
        val originalEntry = getUser(user)
        if (originalEntry == null) {
            db.transaction {
                val userm = UserM(user.idLong)
                persist(userm)
            }
        }
    }
}
