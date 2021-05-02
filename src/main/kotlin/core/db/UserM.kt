package core.db

import javax.persistence.*

@Entity
@Table(name = "users")
class UserM private constructor(
    @get:Id
    @get:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long?,

    var discordId: Long,

    @get:ManyToMany
    @get:JoinTable(
        name = "guild_users",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "guild_id")]
    )
    var servers: MutableList<GuildM>
) {
    constructor(discordId: Long) : this(null, discordId, mutableListOf())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UserM

        if (id != other.id) return false

        return true
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun toString(): String {
        return "UserM(id=$id, discordId=$discordId)"
    }
}
