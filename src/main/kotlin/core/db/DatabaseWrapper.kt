package core.db

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.User
import javax.persistence.EntityManager
import javax.persistence.NoResultException

class DatabaseWrapper(val db: Database) {

    fun getGuild(guild: Guild, entityManager: EntityManager): GuildM? {
        val criteria = entityManager.criteriaBuilder.createQuery(GuildM::class.java)
        val from = criteria.from(GuildM::class.java)
        criteria.select(from)
        criteria.where(entityManager.criteriaBuilder.equal(from.get<Long>("discordId"), guild.idLong))
        val typed = entityManager.createQuery(criteria)
        return try {
            typed.singleResult
        } catch (e: NoResultException) {
            null
        }
    }

    fun getUser(user: User, entityManager: EntityManager): UserM? {
        val criteria = entityManager.criteriaBuilder.createQuery(UserM::class.java)
        val from = criteria.from(UserM::class.java)
        criteria.select(from)
        criteria.where(entityManager.criteriaBuilder.equal(from.get<Long>("discordId"), user.idLong))
        val typed = entityManager.createQuery(criteria)
        return try {
            typed.singleResult
        } catch (e: NoResultException) {
            null
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

    fun addLink(user: User, guild: Guild, manager: EntityManager) {
        val originalUserEntry = getUser(user, manager)
        val originalGuildEntry = getGuild(guild, manager)
        val userm: UserM
        val guildm: GuildM
        if (originalUserEntry == null) {
            userm = UserM(user.idLong)
            manager.persist(userm)
        } else {
            userm = originalUserEntry
        }
        if (originalGuildEntry == null) {
            guildm = GuildM(guild.idLong)
            manager.persist(guildm)
        } else {
            guildm = originalGuildEntry
        }

        if (!userm.servers.contains(guildm)) {
            userm.servers.add(guildm)
        }
        if (!guildm.users.contains(userm)) {
            guildm.users.add(userm)
        }
        manager.detach(userm)
        manager.detach(guildm)
        manager.merge(userm)
        manager.merge(guildm)
    }

    fun addGuild(guild: Guild, manager: EntityManager) {
        val originalEntry = getGuild(guild, manager)
        if (originalEntry == null) {
            val guildm = GuildM(guild.idLong)
            manager.persist(guildm)
        }
    }

    fun addUser(user: User, manager: EntityManager) {
        val originalEntry = getUser(user, manager)
        if (originalEntry == null) {
            val userm = UserM(user.idLong)
            manager.persist(userm)
        }
    }
}
